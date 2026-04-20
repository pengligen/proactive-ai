package com.proactiveai.extreme.service

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.context.identity.IdentityNormalizer
import com.proactiveai.extreme.storage.AppInventoryRow
import com.proactiveai.extreme.storage.CanonicalPayloadHasher
import com.proactiveai.extreme.storage.CollectorStateRepository
import com.proactiveai.extreme.storage.ContactDirectoryRow
import com.proactiveai.extreme.storage.ContactIdentityRow
import com.proactiveai.extreme.storage.ContextEventStore
import org.json.JSONArray
import java.util.Locale
import java.util.UUID

object InitialDataBootstrapRunner {
    private val identityNormalizer = IdentityNormalizer()
    private const val BOOTSTRAP_HISTORY_TTL_SECONDS = 180 * 24 * 3600

    fun runPending(context: Context) {
        val store = ContextEventStore.getInstance(context)
        val state = CollectorStateRepository(store)

        runContactsBootstrap(context, store, state)
        runAppInventoryBootstrap(context, store, state)
        runSmsBootstrap(context, store, state)
        runCallLogBootstrap(context, store, state)
        runCalendarBootstrap(context, store, state)
        runMediaBootstrap(context, store, state)
        store.backfillDerivedItemsForCategories(
            categories = setOf(
                "contact_bootstrap",
                "app_inventory_bootstrap",
                "sms_bootstrap",
                "call_log_bootstrap",
                "calendar_bootstrap",
            ),
        )
    }

    private fun runContactsBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "contacts"
        if (state.getBootstrapStatus(source) == "done") return
        if (!hasPermission(context, android.Manifest.permission.READ_CONTACTS)) {
            state.markBootstrapStatus(source, "blocked_permission")
            return
        }

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "contacts-$now"

        val result = scanContacts(context, scanId, now)
        store.replaceContactDirectory(result.rows, result.identities, nowMs = now)
        val payload = BootstrapSnapshotPayloadBuilder.buildContactsPayload(
            scanId = scanId,
            rows = result.rows,
            identities = result.identities,
            maxLastUpdatedTs = result.maxLastUpdatedTs,
        )

        val event = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = now,
            source = "bootstrap_runner",
            category = "contact_bootstrap",
            summary = "Bootstrap contacts snapshot: ${result.rows.size} contacts, ${result.identities.size} identities",
            payload = payload,
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 30 * 24 * 3600,
        )
        store.insert(event)

        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "contactsCount" to result.rows.size,
                "identityCount" to result.identities.size,
                "maxLastUpdatedTs" to result.maxLastUpdatedTs,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastUpdatedTs" to result.maxLastUpdatedTs,
                "contactsCount" to result.rows.size,
            ),
        )
        state.requestSummaryRefresh(source, atMs = now)
        state.markBootstrapStatus(source, "done")
    }

    private fun runAppInventoryBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "app_inventory"
        if (state.getBootstrapStatus(source) == "done") return

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "apps-$now"

        val rows = scanInstalledApps(context)
        store.replaceAppInventory(rows, nowMs = now)

        val lastUpdateMax = rows.maxOfOrNull { it.lastUpdateTime ?: 0L } ?: 0L
        val payload = BootstrapSnapshotPayloadBuilder.buildAppsPayload(
            scanId = scanId,
            rows = rows,
            lastUpdateTimeMax = lastUpdateMax,
        )
        val event = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = now,
            source = "bootstrap_runner",
            category = "app_inventory_bootstrap",
            summary = "Bootstrap app inventory: ${rows.size} installed packages",
            payload = payload,
            sensitivity = Sensitivity.MEDIUM,
            ttlSeconds = 30 * 24 * 3600,
        )
        store.insert(event)

        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "appCount" to rows.size,
                "lastUpdateTimeMax" to lastUpdateMax,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastUpdateTimeMax" to lastUpdateMax,
                "appCount" to rows.size,
            ),
        )
        state.requestSummaryRefresh(source, atMs = now)
        state.markBootstrapStatus(source, "done")
    }

    private fun runSmsBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "sms"
        if (state.getBootstrapStatus(source) == "done" && store.countEventsByCategory("sms_bootstrap") > 0) return
        if (!hasPermission(context, android.Manifest.permission.READ_SMS)) {
            state.markBootstrapStatus(source, "blocked_permission")
            return
        }

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "sms-$now"
        val result = scanSmsEvents(context, scanId)
        store.insertAll(result.events)
        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "rowCount" to result.events.size,
                "lastSmsId" to result.lastId,
                "lastSmsDateMs" to result.lastDateMs,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastSmsId" to result.lastId,
                "lastSmsDateMs" to result.lastDateMs,
            ),
        )
        state.markBootstrapStatus(source, "done")
    }

    private fun runCallLogBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "call_log"
        if (state.getBootstrapStatus(source) == "done" && store.countEventsByCategory("call_log_bootstrap") > 0) return
        if (!hasPermission(context, android.Manifest.permission.READ_CALL_LOG)) {
            state.markBootstrapStatus(source, "blocked_permission")
            return
        }

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "call-$now"
        val result = scanCallEvents(context, scanId)
        store.insertAll(result.events)
        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "rowCount" to result.events.size,
                "lastCallId" to result.lastId,
                "lastCallDateMs" to result.lastDateMs,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastCallId" to result.lastId,
                "lastCallDateMs" to result.lastDateMs,
            ),
        )
        state.markBootstrapStatus(source, "done")
    }

    private fun runCalendarBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "calendar"
        if (state.getBootstrapStatus(source) == "done" && store.countEventsByCategory("calendar_bootstrap") > 0) return
        if (!hasPermission(context, android.Manifest.permission.READ_CALENDAR)) {
            state.markBootstrapStatus(source, "blocked_permission")
            return
        }

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "calendar-$now"
        val result = scanCalendarEvents(context, scanId, now)
        store.insertAll(result.events)
        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "rowCount" to result.events.size,
                "lastBeginMs" to result.lastBeginMs,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastBeginMs" to result.lastBeginMs,
            ),
        )
        state.markBootstrapStatus(source, "done")
    }

    private fun runMediaBootstrap(
        context: Context,
        store: ContextEventStore,
        state: CollectorStateRepository,
    ) {
        val source = "media"
        if (state.getBootstrapStatus(source) == "done" && store.countEventsByCategory("media_bootstrap") > 0) return

        state.markBootstrapStatus(source, "running")
        val now = System.currentTimeMillis()
        val scanId = "media-$now"
        val result = scanMediaEvents(context, scanId)
        store.insertAll(result.events)
        state.putBootstrapCursor(
            source = source,
            cursor = mapOf(
                "scanId" to scanId,
                "rowCount" to result.events.size,
                "lastDateAddedSeconds" to result.lastDateAddedSeconds,
            ),
        )
        state.putIncrementalCursor(
            source = source,
            cursor = mapOf(
                "lastDateAddedSeconds" to result.lastDateAddedSeconds,
            ),
        )
        state.markBootstrapStatus(source, "done")
    }

    private fun scanContacts(
        context: Context,
        scanId: String,
        nowMs: Long,
    ): ContactScanResult {
        val contactsById = linkedMapOf<Long, MutableContactAggregate>()
        val contactsCursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
                ContactsContract.Contacts.LAST_TIME_CONTACTED,
                ContactsContract.Contacts.TIMES_CONTACTED,
            ),
            null,
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC",
        )

        contactsCursor?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(0)
                contactsById[contactId] = MutableContactAggregate(
                    contactId = contactId,
                    lookupKey = cursor.getString(1),
                    displayName = cursor.getString(2).orEmpty().ifBlank { "Unknown Contact #$contactId" },
                    displayNameAlt = null,
                    photoUri = cursor.getString(3),
                    starred = cursor.getInt(4) == 1,
                    lastUpdatedTs = cursor.getLong(5).takeIf { it > 0 },
                    lastContactedTs = cursor.getLong(6).takeIf { it > 0 },
                    timesContacted = cursor.getInt(7).takeIf { it > 0 },
                )
            }
        }

        val groupTitlesById = linkedMapOf<Long, String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups._ID,
                ContactsContract.Groups.TITLE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                groupTitlesById[cursor.getLong(0)] = cursor.getString(1).orEmpty()
            }
        }

        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.ACCOUNT_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(0)
                val aggregate = contactsById[contactId] ?: continue
                if (aggregate.accountType.isNullOrBlank()) {
                    aggregate.accountType = cursor.getString(1)
                }
                if (aggregate.accountName.isNullOrBlank()) {
                    aggregate.accountName = cursor.getString(2)
                }
            }
        }

        val mimeSelection = buildMimeSelection(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
        )
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.IS_PRIMARY,
            ),
            mimeSelection.first,
            mimeSelection.second,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contactId = cursor.getLong(0)
                val aggregate = contactsById[contactId] ?: continue
                val mimeType = cursor.getString(1).orEmpty()
                val data1 = cursor.getString(2)
                val data2 = cursor.getInt(3)
                val data3 = cursor.getString(4)
                val data4 = cursor.getString(5)
                val isPrimary = cursor.getInt(6) == 1

                when (mimeType) {
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val normalized = identityNormalizer.normalizePhone(data1.orEmpty()) ?: continue
                        aggregate.identities += ContactIdentityRow(
                            contactId = contactId,
                            kind = normalized.kind,
                            normalizedValue = normalized.normalizedValue,
                            normalizedHash = normalized.normalizedHash,
                            label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                                context.resources,
                                data2,
                                data3,
                            ).toString(),
                            isPrimary = isPrimary,
                        )
                    }

                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val normalized = identityNormalizer.normalizeEmail(data1.orEmpty()) ?: continue
                        aggregate.identities += ContactIdentityRow(
                            contactId = contactId,
                            kind = normalized.kind,
                            normalizedValue = normalized.normalizedValue,
                            normalizedHash = normalized.normalizedHash,
                            label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                                context.resources,
                                data2,
                                data3,
                            ).toString(),
                            isPrimary = isPrimary,
                        )
                    }

                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        if (aggregate.organization.isNullOrBlank()) {
                            aggregate.organization = data1
                        }
                        if (aggregate.title.isNullOrBlank()) {
                            aggregate.title = data4
                        }
                    }

                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE -> {
                        if (aggregate.note.isNullOrBlank()) {
                            aggregate.note = data1
                        }
                    }

                    ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE -> {
                        if (!data1.isNullOrBlank()) {
                            aggregate.relations += linkedMapOf(
                                "name" to data1,
                                "type" to data2,
                            )
                        }
                    }

                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE -> {
                        if (!data1.isNullOrBlank()) {
                            aggregate.events += linkedMapOf(
                                "date" to data1,
                                "type" to data2,
                            )
                        }
                    }

                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE -> {
                        val groupId = data1?.toLongOrNull() ?: continue
                        val title = groupTitlesById[groupId].orEmpty()
                        if (title.isBlank()) continue
                        aggregate.groups += title
                    }
                }
            }
        }

        val rows = contactsById.values.map { aggregate ->
            val relationJson = JSONArray(aggregate.relations).toString()
            val eventJson = JSONArray(aggregate.events).toString()
            val groupsJson = JSONArray(aggregate.groups.sorted()).toString()
            val identities = aggregate.identities
                .sortedWith(compareBy<ContactIdentityRow>({ it.kind }, { it.normalizedValue }))
                .map {
                    mapOf(
                        "kind" to it.kind,
                        "value" to it.normalizedValue,
                        "hash" to it.normalizedHash,
                        "label" to it.label,
                        "primary" to it.isPrimary,
                    )
                }
            val contentHash = CanonicalPayloadHasher.sha256(
                mapOf(
                    "contactId" to aggregate.contactId,
                    "lookupKey" to aggregate.lookupKey,
                    "displayName" to aggregate.displayName,
                    "displayNameAlt" to aggregate.displayNameAlt,
                    "photoUri" to aggregate.photoUri,
                    "starred" to aggregate.starred,
                    "organization" to aggregate.organization,
                    "title" to aggregate.title,
                    "note" to aggregate.note,
                    "relations" to aggregate.relations,
                    "events" to aggregate.events,
                    "groups" to aggregate.groups.sorted(),
                    "accountType" to aggregate.accountType,
                    "accountName" to aggregate.accountName,
                    "lastUpdatedTs" to aggregate.lastUpdatedTs,
                    "lastContactedTs" to aggregate.lastContactedTs,
                    "timesContacted" to aggregate.timesContacted,
                    "identities" to identities,
                ),
            )

            ContactDirectoryRow(
                contactId = aggregate.contactId,
                lookupKey = aggregate.lookupKey,
                displayName = aggregate.displayName,
                displayNameAlt = aggregate.displayNameAlt,
                photoUri = aggregate.photoUri,
                starred = aggregate.starred,
                organization = aggregate.organization,
                title = aggregate.title,
                note = aggregate.note,
                relationsJson = relationJson,
                eventsJson = eventJson,
                groupsJson = groupsJson,
                accountType = aggregate.accountType,
                accountName = aggregate.accountName,
                lastUpdatedTs = aggregate.lastUpdatedTs,
                lastContactedTs = aggregate.lastContactedTs,
                timesContacted = aggregate.timesContacted,
                lastSeenScanId = scanId,
                contentHash = contentHash,
            )
        }

        val identities = contactsById.values.flatMap { aggregate ->
            aggregate.identities.distinctBy { "${it.contactId}|${it.kind}|${it.normalizedValue}" }
        }
        val maxUpdated = rows.maxOfOrNull { it.lastUpdatedTs ?: 0L } ?: 0L
        return ContactScanResult(rows = rows, identities = identities, maxLastUpdatedTs = maxUpdated)
    }

    private fun scanInstalledApps(context: Context): List<AppInventoryRow> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        return packages
            .sortedBy { packageInfo ->
                val label = packageInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
                label.ifBlank { packageInfo.packageName }
            }
            .map { packageInfo ->
                val label = packageInfo.applicationInfo?.loadLabel(pm)?.toString().orEmpty()
                    .ifBlank { packageInfo.packageName }
                val versionCode = packageInfoCompatVersionCode(packageInfo)
                val contentHash = CanonicalPayloadHasher.sha256(
                    mapOf(
                        "packageName" to packageInfo.packageName,
                        "appLabel" to label,
                        "versionName" to packageInfo.versionName,
                        "versionCode" to versionCode,
                        "firstInstallTime" to packageInfo.firstInstallTime,
                        "lastUpdateTime" to packageInfo.lastUpdateTime,
                    ),
                )
                AppInventoryRow(
                    packageName = packageInfo.packageName,
                    appLabel = label,
                    versionName = packageInfo.versionName,
                    versionCode = versionCode,
                    firstInstallTime = packageInfo.firstInstallTime.takeIf { it > 0L },
                    lastUpdateTime = packageInfo.lastUpdateTime.takeIf { it > 0L },
                    contentHash = contentHash,
                )
            }
    }

    private fun scanSmsEvents(
        context: Context,
        scanId: String,
    ): BootstrapEventResult {
        val events = mutableListOf<ContextEvent>()
        var lastId = -1L
        var lastDateMs = 0L
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.DATE,
                Telephony.Sms.BODY,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < MAX_SMS_BOOTSTRAP_ROWS) {
                val smsId = cursor.getLong(0)
                val address = identityNormalizer.normalizePhone(cursor.getString(1).orEmpty())?.displayMasked
                    ?: maskAddress(cursor.getString(1).orEmpty())
                val dateMs = cursor.getLong(2)
                val bodySnippet = sanitizeSnippet(cursor.getString(3).orEmpty(), 180)
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = dateMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    source = "bootstrap_runner",
                    category = "sms_bootstrap",
                    summary = "Bootstrap SMS from $address",
                    payload = mapOf(
                        "scanMode" to "bootstrap",
                        "bootstrapSource" to "sms",
                        "bootstrapSessionId" to scanId,
                        "smsId" to smsId,
                        "address" to address,
                        "dateMs" to dateMs,
                        "bodySnippet" to bodySnippet,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = BOOTSTRAP_HISTORY_TTL_SECONDS,
                )
                lastId = maxOf(lastId, smsId)
                lastDateMs = maxOf(lastDateMs, dateMs)
                count += 1
            }
        }
        return BootstrapEventResult(events = events, lastId = lastId, lastDateMs = lastDateMs)
    }

    private fun scanCallEvents(
        context: Context,
        scanId: String,
    ): BootstrapEventResult {
        val events = mutableListOf<ContextEvent>()
        var lastId = -1L
        var lastDateMs = 0L
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < MAX_CALL_BOOTSTRAP_ROWS) {
                val callId = cursor.getLong(0)
                val number = identityNormalizer.normalizePhone(cursor.getString(1).orEmpty())?.displayMasked
                    ?: maskAddress(cursor.getString(1).orEmpty())
                val type = callTypeLabel(cursor.getInt(2))
                val dateMs = cursor.getLong(3)
                val durationSec = cursor.getLong(4)
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = dateMs.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    source = "bootstrap_runner",
                    category = "call_log_bootstrap",
                    summary = "Bootstrap call: $type $number (${durationSec}s)",
                    payload = mapOf(
                        "scanMode" to "bootstrap",
                        "bootstrapSource" to "call_log",
                        "bootstrapSessionId" to scanId,
                        "callId" to callId,
                        "number" to number,
                        "type" to type,
                        "dateMs" to dateMs,
                        "durationSec" to durationSec,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = BOOTSTRAP_HISTORY_TTL_SECONDS,
                )
                lastId = maxOf(lastId, callId)
                lastDateMs = maxOf(lastDateMs, dateMs)
                count += 1
            }
        }
        return BootstrapEventResult(events = events, lastId = lastId, lastDateMs = lastDateMs)
    }

    private fun scanCalendarEvents(
        context: Context,
        scanId: String,
        nowMs: Long,
    ): CalendarBootstrapResult {
        val beginWindow = nowMs - 7 * 24 * 60 * 60_000L
        val endWindow = nowMs + 30 * 24 * 60 * 60_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginWindow)
        ContentUris.appendId(builder, endWindow)

        val events = mutableListOf<ContextEvent>()
        var lastBeginMs = 0L
        context.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < MAX_CALENDAR_BOOTSTRAP_ROWS) {
                val eventId = cursor.getLong(0)
                val beginMs = cursor.getLong(1)
                val endMs = cursor.getLong(2)
                val title = cursor.getString(3).orEmpty().ifBlank { "Untitled event" }
                val calendarName = cursor.getString(4).orEmpty()
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = beginMs.takeIf { it > 0 } ?: nowMs,
                    source = "bootstrap_runner",
                    category = "calendar_bootstrap",
                    summary = "Bootstrap calendar: $title",
                    payload = mapOf(
                        "scanMode" to "bootstrap",
                        "bootstrapSource" to "calendar",
                        "bootstrapSessionId" to scanId,
                        "eventId" to eventId,
                        "title" to title,
                        "calendarName" to calendarName,
                        "beginMs" to beginMs,
                        "endMs" to endMs,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = BOOTSTRAP_HISTORY_TTL_SECONDS,
                )
                lastBeginMs = maxOf(lastBeginMs, beginMs)
                count += 1
            }
        }
        return CalendarBootstrapResult(events = events, lastBeginMs = lastBeginMs)
    }

    private fun scanMediaEvents(
        context: Context,
        scanId: String,
    ): MediaBootstrapResult {
        val events = mutableListOf<ContextEvent>()
        var lastDateAddedSeconds = 0L

        scanMediaKind(
            context = context,
            kind = "image",
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = imagePermission(),
            limit = MAX_MEDIA_BOOTSTRAP_ROWS_PER_KIND,
            scanId = scanId,
        ).also {
            events += it.events
            lastDateAddedSeconds = maxOf(lastDateAddedSeconds, it.lastDateAddedSeconds)
        }
        scanMediaKind(
            context = context,
            kind = "video",
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = videoPermission(),
            limit = MAX_MEDIA_BOOTSTRAP_ROWS_PER_KIND,
            scanId = scanId,
        ).also {
            events += it.events
            lastDateAddedSeconds = maxOf(lastDateAddedSeconds, it.lastDateAddedSeconds)
        }
        scanMediaKind(
            context = context,
            kind = "audio",
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = audioPermission(),
            limit = MAX_MEDIA_BOOTSTRAP_ROWS_PER_KIND,
            scanId = scanId,
        ).also {
            events += it.events
            lastDateAddedSeconds = maxOf(lastDateAddedSeconds, it.lastDateAddedSeconds)
        }

        return MediaBootstrapResult(events = events, lastDateAddedSeconds = lastDateAddedSeconds)
    }

    private fun scanMediaKind(
        context: Context,
        kind: String,
        uri: Uri,
        requiredPermission: String,
        limit: Int,
        scanId: String,
    ): MediaBootstrapResult {
        if (!hasPermission(context, requiredPermission)) {
            return MediaBootstrapResult(emptyList(), 0L)
        }

        val events = mutableListOf<ContextEvent>()
        var lastDateAdded = 0L
        context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
            ),
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val mediaId = cursor.getLong(0)
                val dateAddedSeconds = cursor.getLong(1)
                val name = cursor.getString(2) ?: "unknown"
                val mimeType = cursor.getString(3) ?: "unknown"
                val sizeBytes = cursor.getLong(4)
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = dateAddedSeconds.takeIf { it > 0 }?.times(1000) ?: System.currentTimeMillis(),
                    source = "bootstrap_runner",
                    category = "media_bootstrap",
                    summary = "Bootstrap $kind: $name",
                    payload = mapOf(
                        "scanMode" to "bootstrap",
                        "bootstrapSource" to "media",
                        "bootstrapSessionId" to scanId,
                        "kind" to kind,
                        "mediaId" to mediaId,
                        "dateAddedSeconds" to dateAddedSeconds,
                        "name" to name,
                        "mimeType" to mimeType,
                        "sizeBytes" to sizeBytes,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = BOOTSTRAP_HISTORY_TTL_SECONDS,
                )
                lastDateAdded = maxOf(lastDateAdded, dateAddedSeconds)
                count += 1
            }
        }
        return MediaBootstrapResult(events = events, lastDateAddedSeconds = lastDateAdded)
    }

    private fun buildMimeSelection(vararg mimeTypes: String): Pair<String, Array<String>> {
        val placeholders = mimeTypes.joinToString(",") { "?" }
        return "${ContactsContract.Data.MIMETYPE} IN ($placeholders)" to mimeTypes.toList().toTypedArray()
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun imagePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun videoPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun audioPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun sanitizeSnippet(input: String, max: Int): String {
        return input.replace(Regex("\\s+"), " ").trim().take(max)
    }

    private fun maskAddress(address: String): String {
        val clean = address.trim()
        if (clean.length <= 4) return clean
        return "${clean.take(2)}***${clean.takeLast(2)}"
    }

    private fun callTypeLabel(type: Int): String {
        return when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            else -> "other"
        }
    }

    private fun packageInfoCompatVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private data class MutableContactAggregate(
        val contactId: Long,
        val lookupKey: String?,
        val displayName: String,
        val displayNameAlt: String?,
        val photoUri: String?,
        val starred: Boolean,
        val lastUpdatedTs: Long?,
        val lastContactedTs: Long?,
        val timesContacted: Int?,
        var organization: String? = null,
        var title: String? = null,
        var note: String? = null,
        var accountType: String? = null,
        var accountName: String? = null,
        val relations: MutableList<Map<String, Any>> = mutableListOf(),
        val events: MutableList<Map<String, Any>> = mutableListOf(),
        val groups: MutableSet<String> = linkedSetOf(),
        val identities: MutableList<ContactIdentityRow> = mutableListOf(),
    )

    private data class ContactScanResult(
        val rows: List<ContactDirectoryRow>,
        val identities: List<ContactIdentityRow>,
        val maxLastUpdatedTs: Long,
    )

    private data class BootstrapEventResult(
        val events: List<ContextEvent>,
        val lastId: Long,
        val lastDateMs: Long,
    )

    private data class CalendarBootstrapResult(
        val events: List<ContextEvent>,
        val lastBeginMs: Long,
    )

    private data class MediaBootstrapResult(
        val events: List<ContextEvent>,
        val lastDateAddedSeconds: Long,
    )

    private const val MAX_SMS_BOOTSTRAP_ROWS = 200
    private const val MAX_CALL_BOOTSTRAP_ROWS = 200
    private const val MAX_CALENDAR_BOOTSTRAP_ROWS = 200
    private const val MAX_MEDIA_BOOTSTRAP_ROWS_PER_KIND = 120
}
