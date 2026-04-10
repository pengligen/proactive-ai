package com.proactiveai.extreme.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ActionHistoryStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_ACTION_HISTORY (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                plan_id TEXT,
                step_id TEXT,
                summary TEXT NOT NULL,
                detail TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ACTION_HISTORY")
        onCreate(db)
    }

    fun insert(
        eventType: String,
        planId: String?,
        stepId: String?,
        summary: String,
        detail: String?,
    ) {
        val values = ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("event_type", eventType)
            put("plan_id", planId)
            put("step_id", stepId)
            put("summary", summary)
            put("detail", detail)
        }
        writableDatabase.insert(TABLE_ACTION_HISTORY, null, values)
    }

    fun recent(limit: Int = 40): List<ActionHistoryEntry> {
        val cursor = readableDatabase.query(
            TABLE_ACTION_HISTORY,
            arrayOf("id", "created_at", "event_type", "plan_id", "step_id", "summary", "detail"),
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString(),
        )

        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        ActionHistoryEntry(
                            id = it.getLong(0),
                            createdAt = it.getLong(1),
                            eventType = it.getString(2),
                            planId = it.getString(3),
                            stepId = it.getString(4),
                            summary = it.getString(5),
                            detail = it.getString(6),
                        )
                    )
                }
            }
        }
    }

    fun clear() {
        writableDatabase.delete(TABLE_ACTION_HISTORY, null, null)
    }

    companion object {
        private const val DB_NAME = "proactive_action_history.db"
        private const val DB_VERSION = 1
        private const val TABLE_ACTION_HISTORY = "action_history"

        @Volatile
        private var instance: ActionHistoryStore? = null

        fun getInstance(context: Context): ActionHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: ActionHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
