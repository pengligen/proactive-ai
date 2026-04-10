package com.proactiveai.extreme.core.context.plugins

import android.content.ContentUris
import android.content.Context
import android.provider.CallLog
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class CommunicationContextPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private var lastCalendarSignature: String = ""
    private var lastSmsId: Long = -1L
    private var lastCallSignature: String = ""
    private var lastContactsCount: Int = -1
    private var lastPermissionSummarySignature: String = ""
    private var lastPermissionSummaryAt: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        val events = mutableListOf<ContextEvent>()
        val now = System.currentTimeMillis()

        latestCalendarContext(now)?.let { calendar ->
            val signature = "${calendar.eventId}|${calendar.beginMs}|${calendar.endMs}"
            if (signature != lastCalendarSignature) {
                lastCalendarSignature = signature
                val startText = formatClock(calendar.beginMs)
                val state = if (now in calendar.beginMs..calendar.endMs) "ongoing" else "upcoming"
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = now,
                    source = descriptor.id,
                    category = "calendar",
                    summary = "Calendar $state: ${calendar.title} @ $startText",
                    payload = mapOf(
                        "eventId" to calendar.eventId,
                        "title" to calendar.title,
                        "calendarName" to calendar.calendarName,
                        "beginMs" to calendar.beginMs,
                        "endMs" to calendar.endMs,
                        "state" to state,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = 86_400,
                )
            }
        }

        latestSms()?.let { sms ->
            if (sms.id != lastSmsId) {
                lastSmsId = sms.id
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = now,
                    source = descriptor.id,
                    category = "communication",
                    summary = "Latest SMS from ${sms.addressMasked}",
                    payload = mapOf(
                        "smsId" to sms.id,
                        "address" to sms.addressMasked,
                        "dateMs" to sms.dateMs,
                        "bodySnippet" to sms.bodySnippet,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = 86_400,
                )
            }
        }

        latestCall()?.let { call ->
            val signature = "${call.id}|${call.dateMs}|${call.typeLabel}"
            if (signature != lastCallSignature) {
                lastCallSignature = signature
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = now,
                    source = descriptor.id,
                    category = "communication",
                    summary = "Latest call: ${call.typeLabel} ${call.numberMasked} (${call.durationSec}s)",
                    payload = mapOf(
                        "callId" to call.id,
                        "number" to call.numberMasked,
                        "type" to call.typeLabel,
                        "dateMs" to call.dateMs,
                        "durationSec" to call.durationSec,
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = 86_400,
                )
            }
        }

        contactsCount()?.let { count ->
            if (lastContactsCount == -1 || count != lastContactsCount) {
                val previous = lastContactsCount
                lastContactsCount = count
                events += ContextEvent(
                    eventId = UUID.randomUUID().toString(),
                    occurredAt = now,
                    source = descriptor.id,
                    category = "communication",
                    summary = "Contacts accessible: total=$count",
                    payload = mapOf(
                        "contactsCount" to count,
                        "deltaFromLast" to if (previous == -1) 0 else (count - previous),
                    ),
                    sensitivity = Sensitivity.HIGH,
                    ttlSeconds = 7 * 24 * 3600,
                )
            }
        }

        val permissionSummary = buildPermissionSummary()
        val permissionSignature = permissionSummary.joinToString("|")
        val shouldEmitPermissionSummary =
            permissionSignature != lastPermissionSummarySignature ||
                now - lastPermissionSummaryAt > PERMISSION_SUMMARY_INTERVAL_MS
        if (shouldEmitPermissionSummary) {
            lastPermissionSummarySignature = permissionSignature
            lastPermissionSummaryAt = now
            events += ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = descriptor.id,
                category = "capability_status",
                summary = "Communication capability: ${permissionSummary.joinToString(", ")}",
                payload = mapOf(
                    "permissionSummary" to permissionSummary,
                ),
                sensitivity = Sensitivity.MEDIUM,
                ttlSeconds = 7 * 24 * 3600,
            )
        }

        return events
    }

    private fun latestCalendarContext(nowMs: Long): CalendarSnapshot? {
        if (!hasPermission(android.Manifest.permission.READ_CALENDAR)) return null

        val beginWindow = nowMs - 30 * 60_000L
        val endWindow = nowMs + 24 * 60 * 60_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginWindow)
        ContentUris.appendId(builder, endWindow)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        val cursor = context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(0)
                val beginMs = it.getLong(1)
                val endMs = it.getLong(2)
                val title = it.getString(3)?.trim().orEmpty().ifBlank { "Untitled event" }
                val calendarName = it.getString(4)?.trim().orEmpty()
                return CalendarSnapshot(eventId, beginMs, endMs, title, calendarName)
            }
        }
        return null
    }

    private fun latestSms(): SmsSnapshot? {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) return null
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY,
        )
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val id = it.getLong(0)
            val address = it.getString(1).orEmpty()
            val dateMs = it.getLong(2)
            val body = it.getString(3).orEmpty()
            return SmsSnapshot(
                id = id,
                addressMasked = maskAddress(address),
                dateMs = dateMs,
                bodySnippet = sanitizeSnippet(body, 140),
            )
        }
    }

    private fun latestCall(): CallSnapshot? {
        if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) return null
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        ) ?: return null

        cursor.use {
            if (!it.moveToFirst()) return null
            val id = it.getLong(0)
            val number = it.getString(1).orEmpty()
            val type = it.getInt(2)
            val dateMs = it.getLong(3)
            val duration = it.getLong(4)
            return CallSnapshot(
                id = id,
                numberMasked = maskAddress(number),
                typeLabel = callTypeLabel(type),
                dateMs = dateMs,
                durationSec = duration,
            )
        }
    }

    private fun contactsCount(): Int? {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) return null
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null,
        ) ?: return null
        cursor.use { return it.count }
    }

    private fun buildPermissionSummary(): List<String> {
        val parts = mutableListOf<String>()
        parts += "calendar=${if (hasPermission(android.Manifest.permission.READ_CALENDAR)) "on" else "off"}"
        parts += "contacts=${if (hasPermission(android.Manifest.permission.READ_CONTACTS)) "on" else "off"}"
        parts += "sms=${if (hasPermission(android.Manifest.permission.READ_SMS)) "on" else "off"}"
        parts += "call_log=${if (hasPermission(android.Manifest.permission.READ_CALL_LOG)) "on" else "off"}"
        return parts
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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

    private fun formatClock(timeMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.US).format(Date(timeMs))
    }

    private fun sanitizeSnippet(input: String, max: Int): String {
        return input.replace(Regex("\\s+"), " ").trim().take(max)
    }

    private fun maskAddress(address: String): String {
        val clean = address.trim()
        if (clean.length <= 4) return clean
        return "${clean.take(2)}***${clean.takeLast(2)}"
    }

    private data class CalendarSnapshot(
        val eventId: Long,
        val beginMs: Long,
        val endMs: Long,
        val title: String,
        val calendarName: String,
    )

    private data class SmsSnapshot(
        val id: Long,
        val addressMasked: String,
        val dateMs: Long,
        val bodySnippet: String,
    )

    private data class CallSnapshot(
        val id: Long,
        val numberMasked: String,
        val typeLabel: String,
        val dateMs: Long,
        val durationSec: Long,
    )

    companion object {
        private const val PERMISSION_SUMMARY_INTERVAL_MS = 10 * 60_000L
    }
}
