package com.proactiveai.extreme.service

import com.proactiveai.extreme.storage.AppInventoryRow
import com.proactiveai.extreme.storage.ContactDirectoryRow
import com.proactiveai.extreme.storage.ContactIdentityRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapSnapshotPayloadBuilderTest {
    @Test
    fun `contacts payload includes masked identities and top contacts ordered by starred then recency`() {
        val payload = BootstrapSnapshotPayloadBuilder.buildContactsPayload(
            scanId = "contacts-1",
            rows = listOf(
                contactRow(
                    contactId = 2L,
                    displayName = "Beta",
                    starred = true,
                    organization = "Pin",
                    title = "Founder",
                    note = "Important founder note",
                    groupsJson = """["VIP","Family"]""",
                    relationsJson = """[{"name":"Spouse","type":1}]""",
                    lastUpdatedTs = 2000L,
                ),
                contactRow(
                    contactId = 1L,
                    displayName = "Alpha",
                    starred = true,
                    lastUpdatedTs = 1000L,
                ),
                contactRow(
                    contactId = 3L,
                    displayName = "Gamma",
                    starred = false,
                    lastUpdatedTs = 3000L,
                ),
            ),
            identities = listOf(
                ContactIdentityRow(
                    contactId = 2L,
                    kind = "phone",
                    normalizedValue = "+8613912345678",
                    normalizedHash = "phone_hash_beta",
                    label = "mobile",
                    isPrimary = true,
                ),
                ContactIdentityRow(
                    contactId = 2L,
                    kind = "email",
                    normalizedValue = "beta@example.com",
                    normalizedHash = "email_hash_beta",
                    label = "work",
                    isPrimary = false,
                ),
                ContactIdentityRow(
                    contactId = 1L,
                    kind = "phone",
                    normalizedValue = "+12025550123",
                    normalizedHash = "phone_hash_alpha",
                    label = "mobile",
                    isPrimary = true,
                ),
            ),
            maxLastUpdatedTs = 3000L,
        )

        assertEquals("bootstrap", payload["scanMode"])
        assertEquals("contacts", payload["bootstrapSource"])
        assertEquals("contacts-1", payload["bootstrapSessionId"])
        assertEquals(3, payload["contactsCount"])
        assertEquals(3, payload["contactsIncluded"])
        assertEquals(false, payload["contactsTruncated"])

        @Suppress("UNCHECKED_CAST")
        val contacts = payload["contacts"] as List<Map<String, Any>>
        assertEquals(listOf("Beta", "Alpha", "Gamma"), contacts.map { it["displayName"] })

        val beta = contacts.first()
        assertEquals("Pin", beta["organization"])
        assertEquals("Founder", beta["title"])
        assertEquals(listOf("VIP", "Family"), beta["groups"])
        assertEquals(listOf("Spouse"), beta["relationLabels"])
        assertEquals("Important founder note", beta["noteSnippet"])
        assertEquals(listOf("+8***78"), beta["phonesMasked"])
        assertEquals(listOf("be***om"), beta["emailsMasked"])
        assertEquals(listOf("email_hash_beta", "phone_hash_beta"), beta["identityHashes"])
    }

    @Test
    fun `contacts payload truncates after five hundred contacts`() {
        val rows = (1L..501L).map { index ->
            contactRow(
                contactId = index,
                displayName = "Contact $index",
                starred = index == 501L,
                lastUpdatedTs = index,
            )
        }

        val payload = BootstrapSnapshotPayloadBuilder.buildContactsPayload(
            scanId = "contacts-2",
            rows = rows,
            identities = emptyList(),
            maxLastUpdatedTs = 501L,
        )

        @Suppress("UNCHECKED_CAST")
        val contacts = payload["contacts"] as List<Map<String, Any>>
        assertEquals(501, payload["contactsCount"])
        assertEquals(500, payload["contactsIncluded"])
        assertEquals(true, payload["contactsTruncated"])
        assertEquals(500, contacts.size)
        assertEquals("Contact 501", contacts.first()["displayName"])
    }

    @Test
    fun `apps payload orders by last update then label and truncates after five hundred rows`() {
        val rows = buildList {
            add(appRow("pkg.beta", "Beta", 20L))
            add(appRow("pkg.alpha", "Alpha", 20L))
            add(appRow("pkg.old", "Old", 0L))
            for (index in 1..499) {
                add(appRow("pkg.extra.$index", "Extra $index", 1L))
            }
        }

        val payload = BootstrapSnapshotPayloadBuilder.buildAppsPayload(
            scanId = "apps-1",
            rows = rows,
            lastUpdateTimeMax = 20L,
        )

        @Suppress("UNCHECKED_CAST")
        val apps = payload["apps"] as List<Map<String, Any>>
        assertEquals(502, payload["appCount"])
        assertEquals(500, payload["appsIncluded"])
        assertEquals(true, payload["appsTruncated"])
        assertEquals("Alpha", apps[0]["label"])
        assertEquals("Beta", apps[1]["label"])
        assertFalse(apps.any { it["packageName"] == "pkg.old" })
    }

    private fun contactRow(
        contactId: Long,
        displayName: String,
        starred: Boolean,
        organization: String? = null,
        title: String? = null,
        note: String? = null,
        groupsJson: String = "[]",
        relationsJson: String = "[]",
        lastUpdatedTs: Long? = null,
    ): ContactDirectoryRow {
        return ContactDirectoryRow(
            contactId = contactId,
            lookupKey = "lookup-$contactId",
            displayName = displayName,
            displayNameAlt = null,
            photoUri = null,
            starred = starred,
            organization = organization,
            title = title,
            note = note,
            relationsJson = relationsJson,
            eventsJson = "[]",
            groupsJson = groupsJson,
            accountType = "com.test",
            accountName = "default",
            lastUpdatedTs = lastUpdatedTs,
            lastContactedTs = null,
            timesContacted = null,
            lastSeenScanId = "scan-$contactId",
            contentHash = "hash-$contactId",
        )
    }

    private fun appRow(packageName: String, label: String, lastUpdateTime: Long): AppInventoryRow {
        return AppInventoryRow(
            packageName = packageName,
            appLabel = label,
            versionName = "1.0",
            versionCode = 1L,
            firstInstallTime = 1L,
            lastUpdateTime = lastUpdateTime,
            contentHash = "hash-$packageName",
        )
    }
}
