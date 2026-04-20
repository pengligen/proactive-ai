package com.proactiveai.extreme.service

import com.proactiveai.extreme.storage.AppInventoryRow
import com.proactiveai.extreme.storage.ContactDirectoryRow
import com.proactiveai.extreme.storage.ContactIdentityRow
import java.util.Locale

object BootstrapSnapshotPayloadBuilder {
    private const val MAX_CONTACTS = 500
    private const val MAX_APPS = 500
    private val quotedJsonStringRegex = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
    private val relationNameRegex = Regex("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun buildContactsPayload(
        scanId: String,
        rows: List<ContactDirectoryRow>,
        identities: List<ContactIdentityRow>,
        maxLastUpdatedTs: Long,
    ): Map<String, Any> {
        val identitiesByContactId = identities
            .sortedWith(compareBy<ContactIdentityRow>({ it.kind }, { it.normalizedValue }))
            .groupBy { it.contactId }
        val sortedRows = rows.sortedWith(
            compareByDescending<ContactDirectoryRow> { it.starred }
                .thenByDescending { it.lastUpdatedTs ?: 0L }
                .thenBy { it.displayName.lowercase(Locale.US) },
        )
        val contacts = sortedRows.take(MAX_CONTACTS).map { row ->
            val rowIdentities = identitiesByContactId[row.contactId].orEmpty()
            linkedMapOf<String, Any>(
                "contactId" to row.contactId.toString(),
                "displayName" to row.displayName,
                "organization" to row.organization.orEmpty(),
                "title" to row.title.orEmpty(),
                "starred" to row.starred,
                "groups" to parseStringList(row.groupsJson),
                "relationLabels" to parseRelationLabels(row.relationsJson),
                "noteSnippet" to sanitizeNote(row.note),
                "phonesMasked" to rowIdentities.filter { it.kind == "phone" }.map { maskValue(it.normalizedValue) }.distinct(),
                "emailsMasked" to rowIdentities.filter { it.kind == "email" }.map { maskValue(it.normalizedValue) }.distinct(),
                "identityHashes" to rowIdentities.map { it.normalizedHash }.distinct().sorted(),
                "lastUpdatedTs" to (row.lastUpdatedTs ?: 0L),
            )
        }
        return linkedMapOf(
            "scanMode" to "bootstrap",
            "bootstrapSource" to "contacts",
            "bootstrapSessionId" to scanId,
            "contactsCount" to rows.size,
            "identityCount" to identities.size,
            "maxLastUpdatedTs" to maxLastUpdatedTs,
            "contactsIncluded" to contacts.size,
            "contactsTruncated" to (rows.size > MAX_CONTACTS),
            "contacts" to contacts,
        )
    }

    fun buildAppsPayload(
        scanId: String,
        rows: List<AppInventoryRow>,
        lastUpdateTimeMax: Long,
    ): Map<String, Any> {
        val apps = rows.sortedWith(
            compareByDescending<AppInventoryRow> { it.lastUpdateTime ?: 0L }
                .thenBy { it.appLabel.lowercase(Locale.US) }
                .thenBy { it.packageName },
        ).take(MAX_APPS).map { row ->
            linkedMapOf<String, Any>(
                "packageName" to row.packageName,
                "label" to row.appLabel,
                "versionName" to row.versionName.orEmpty(),
                "versionCode" to (row.versionCode ?: 0L),
                "firstInstallTime" to (row.firstInstallTime ?: 0L),
                "lastUpdateTime" to (row.lastUpdateTime ?: 0L),
            )
        }
        return linkedMapOf(
            "scanMode" to "bootstrap",
            "bootstrapSource" to "app_inventory",
            "bootstrapSessionId" to scanId,
            "appCount" to rows.size,
            "lastUpdateTimeMax" to lastUpdateTimeMax,
            "appsIncluded" to apps.size,
            "appsTruncated" to (rows.size > MAX_APPS),
            "apps" to apps,
        )
    }

    private fun parseStringList(raw: String): List<String> {
        return quotedJsonStringRegex.findAll(raw)
            .mapNotNull { decodeJsonString(it.groupValues[1]).trim().takeIf(String::isNotBlank) }
            .toList()
    }

    private fun parseRelationLabels(raw: String): List<String> {
        return relationNameRegex.findAll(raw)
            .mapNotNull { decodeJsonString(it.groupValues[1]).trim().takeIf(String::isNotBlank) }
            .toList()
            .distinct()
    }

    private fun sanitizeNote(raw: String?): String {
        return raw.orEmpty().replace(Regex("\\s+"), " ").trim().take(200)
    }

    private fun maskValue(raw: String): String {
        return if (raw.length <= 4) raw else "${raw.take(2)}***${raw.takeLast(2)}"
    }

    private fun decodeJsonString(raw: String): String {
        return raw
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }
}
