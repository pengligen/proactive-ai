package com.proactiveai.extreme.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.proactiveai.extreme.core.context.ContextEvent
import org.json.JSONObject

class ContextEventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT NOT NULL UNIQUE,
                occurred_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                category TEXT NOT NULL,
                summary TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                sensitivity TEXT NOT NULL,
                ttl_seconds INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                inserted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_EVENTS")
        onCreate(db)
    }

    fun insert(event: ContextEvent) {
        val values = ContentValues().apply {
            put("event_id", event.eventId)
            put("occurred_at", event.occurredAt)
            put("source", event.source)
            put("category", event.category)
            put("summary", event.summary)
            put("payload_json", JSONObject(event.payload).toString())
            put("sensitivity", event.sensitivity.name)
            put("ttl_seconds", event.ttlSeconds)
            put("synced", 0)
            put("inserted_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun insertAll(events: List<ContextEvent>) {
        writableDatabase.beginTransaction()
        try {
            events.forEach { insert(it) }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getUnsynced(limit: Int): List<StoredContextEvent> {
        val cursor = readableDatabase.query(
            TABLE_EVENTS,
            arrayOf(
                "id",
                "event_id",
                "occurred_at",
                "source",
                "category",
                "summary",
                "payload_json",
                "sensitivity",
                "ttl_seconds",
                "synced",
            ),
            "synced = 0",
            null,
            null,
            null,
            "occurred_at ASC",
            limit.toString(),
        )

        return cursor.use { mapCursorToEvents(it) }
    }

    fun getRecent(limit: Int): List<StoredContextEvent> {
        val cursor = readableDatabase.query(
            TABLE_EVENTS,
            arrayOf(
                "id",
                "event_id",
                "occurred_at",
                "source",
                "category",
                "summary",
                "payload_json",
                "sensitivity",
                "ttl_seconds",
                "synced",
            ),
            null,
            null,
            null,
            null,
            "occurred_at DESC",
            limit.toString(),
        )

        return cursor.use { mapCursorToEvents(it) }
    }

    fun markSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val args = ids.joinToString(separator = ",") { "?" }
        val selectionArgs = ids.map { it.toString() }.toTypedArray()
        writableDatabase.execSQL(
            "UPDATE $TABLE_EVENTS SET synced = 1 WHERE id IN ($args)",
            selectionArgs,
        )
    }

    fun pruneExpired(nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_EVENTS WHERE occurred_at + (ttl_seconds * 1000) < ?",
            arrayOf(nowMs),
        )
    }

    fun countUnsynced(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(1) FROM $TABLE_EVENTS WHERE synced = 0", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun mapCursorToEvents(cursor: android.database.Cursor): List<StoredContextEvent> {
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    StoredContextEvent(
                        id = cursor.getLong(0),
                        eventId = cursor.getString(1),
                        occurredAt = cursor.getLong(2),
                        source = cursor.getString(3),
                        category = cursor.getString(4),
                        summary = cursor.getString(5),
                        payloadJson = cursor.getString(6),
                        sensitivity = cursor.getString(7),
                        ttlSeconds = cursor.getInt(8),
                        synced = cursor.getInt(9) == 1,
                    )
                )
            }
        }
    }

    companion object {
        private const val DB_NAME = "proactive_events.db"
        private const val DB_VERSION = 1
        private const val TABLE_EVENTS = "context_events"

        @Volatile
        private var instance: ContextEventStore? = null

        fun getInstance(context: Context): ContextEventStore {
            return instance ?: synchronized(this) {
                instance ?: ContextEventStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
