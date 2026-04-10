package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.model.PluginDescriptor
import java.util.UUID

class MediaNotebookPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private var lastImageDateAdded: Long = 0L
    private var lastVideoDateAdded: Long = 0L
    private var lastAudioDateAdded: Long = 0L

    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> {
        val events = mutableListOf<ContextEvent>()

        latestMediaItem(
            kind = "image",
            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = imagePermission(),
            lastSeen = lastImageDateAdded,
        )?.let { item ->
            lastImageDateAdded = maxOf(lastImageDateAdded, item.dateAdded)
            events += item.toEvent(descriptor.id, "media")
        }

        latestMediaItem(
            kind = "video",
            uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = videoPermission(),
            lastSeen = lastVideoDateAdded,
        )?.let { item ->
            lastVideoDateAdded = maxOf(lastVideoDateAdded, item.dateAdded)
            events += item.toEvent(descriptor.id, "media")
        }

        latestMediaItem(
            kind = "audio",
            uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            requiredPermission = audioPermission(),
            lastSeen = lastAudioDateAdded,
        )?.let { item ->
            lastAudioDateAdded = maxOf(lastAudioDateAdded, item.dateAdded)
            events += item.toEvent(descriptor.id, "media")
        }

        return events
    }

    private fun latestMediaItem(
        kind: String,
        uri: android.net.Uri,
        requiredPermission: String,
        lastSeen: Long,
    ): MediaItem? {
        if (
            ContextCompat.checkSelfPermission(
                context,
                requiredPermission,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
        )

        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        ) ?: return null

        cursor.use {
            while (it.moveToNext()) {
                val dateAddedSeconds = it.getLong(1)
                if (dateAddedSeconds <= lastSeen) {
                    break
                }

                val id = it.getLong(0)
                val name = it.getString(2) ?: "unknown"
                val mimeType = it.getString(3) ?: "unknown"
                val size = it.getLong(4)

                return MediaItem(
                    kind = kind,
                    id = id,
                    dateAdded = dateAddedSeconds,
                    name = name,
                    mimeType = mimeType,
                    size = size,
                )
            }
        }

        return null
    }

    private fun imagePermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun videoPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_VIDEO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun audioPermission(): String {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private data class MediaItem(
        val kind: String,
        val id: Long,
        val dateAdded: Long,
        val name: String,
        val mimeType: String,
        val size: Long,
    ) {
        fun toEvent(source: String, category: String): ContextEvent {
            val now = System.currentTimeMillis()
            return ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = now,
                source = source,
                category = category,
                summary = "New $kind detected: $name",
                payload = mapOf(
                    "kind" to kind,
                    "mediaId" to id,
                    "dateAddedSeconds" to dateAdded,
                    "name" to name,
                    "mimeType" to mimeType,
                    "sizeBytes" to size,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        }
    }
}
