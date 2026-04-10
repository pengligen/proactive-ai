package com.proactiveai.extreme.service

import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.storage.ContextEventStore
import com.proactiveai.extreme.sync.SyncScheduler
import java.util.UUID

class NotificationCaptureService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName
        val extras: Bundle = notification.extras
        val title = extras.getCharSequence("android.title")?.toString()?.take(120)
        val text = extras.getCharSequence("android.text")?.toString()?.take(200)

        val summary = buildString {
            append("Notification from ")
            append(packageName)
            if (!title.isNullOrBlank()) {
                append(": ")
                append(title)
            }
        }

        val payload = buildMap<String, Any> {
            put("packageName", packageName)
            put("postTime", sbn.postTime)
            put("key", sbn.key)
            put("id", sbn.id)
            put("ongoing", sbn.isOngoing)
            put("clearable", sbn.isClearable)
            if (!title.isNullOrBlank()) {
                put("title", title)
            }
            if (!text.isNullOrBlank()) {
                put("text", text)
            }
        }

        val event = ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = System.currentTimeMillis(),
            source = "notification_listener",
            category = "notification",
            summary = summary,
            payload = payload,
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 86_400,
        )

        ContextEventStore.getInstance(applicationContext).insert(event)
        SyncScheduler.enqueueImmediate(applicationContext)
    }
}
