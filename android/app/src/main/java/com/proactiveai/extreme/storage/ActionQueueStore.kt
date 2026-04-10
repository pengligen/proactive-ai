package com.proactiveai.extreme.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.proactiveai.extreme.orchestrator.ActionStepPayload
import com.proactiveai.extreme.orchestrator.toJsonObject

class ActionQueueStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_QUEUE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                plan_id TEXT NOT NULL,
                step_id TEXT NOT NULL,
                connector TEXT NOT NULL,
                operation TEXT NOT NULL,
                args_json TEXT NOT NULL,
                status TEXT NOT NULL,
                attempt_count INTEGER NOT NULL DEFAULT 0,
                max_attempts INTEGER NOT NULL DEFAULT 3,
                next_retry_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                last_result TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUEUE")
        onCreate(db)
    }

    fun enqueue(
        planId: String,
        step: ActionStepPayload,
        maxAttempts: Int = 3,
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("created_at", now)
            put("updated_at", now)
            put("plan_id", planId)
            put("step_id", step.stepId)
            put("connector", step.connector)
            put("operation", step.operation)
            put("args_json", step.args.toJsonObject().toString())
            put("status", STATUS_PENDING)
            put("attempt_count", 0)
            put("max_attempts", maxAttempts)
            put("next_retry_at", 0L)
            putNull("last_error")
            putNull("last_result")
        }
        return writableDatabase.insert(TABLE_QUEUE, null, values)
    }

    fun recent(limit: Int = 50): List<QueuedAction> {
        val cursor = readableDatabase.query(
            TABLE_QUEUE,
            COLUMNS,
            null,
            null,
            null,
            null,
            "updated_at DESC",
            limit.toString(),
        )
        return cursor.use { mapCursor(it) }
    }

    fun runnable(limit: Int = 10, nowMs: Long = System.currentTimeMillis()): List<QueuedAction> {
        val cursor = readableDatabase.query(
            TABLE_QUEUE,
            COLUMNS,
            "(status = ? OR status = ?) AND attempt_count < max_attempts AND next_retry_at <= ?",
            arrayOf(STATUS_PENDING, STATUS_FAILED, nowMs.toString()),
            null,
            null,
            "updated_at ASC",
            limit.toString(),
        )
        return cursor.use { mapCursor(it) }
    }

    fun markRunning(id: Long): Int {
        val current = getById(id) ?: return 0
        val values = ContentValues().apply {
            put("status", STATUS_RUNNING)
            put("updated_at", System.currentTimeMillis())
            put("attempt_count", current.attemptCount + 1)
        }
        return writableDatabase.update(TABLE_QUEUE, values, "id = ?", arrayOf(id.toString()))
    }

    fun markSucceeded(id: Long, result: String?) {
        val values = ContentValues().apply {
            put("status", STATUS_SUCCEEDED)
            put("updated_at", System.currentTimeMillis())
            put("next_retry_at", Long.MAX_VALUE)
            put("last_result", result)
            putNull("last_error")
        }
        writableDatabase.update(TABLE_QUEUE, values, "id = ?", arrayOf(id.toString()))
    }

    fun markFailed(id: Long, error: String?, nextRetryAt: Long) {
        val values = ContentValues().apply {
            put("status", STATUS_FAILED)
            put("updated_at", System.currentTimeMillis())
            put("next_retry_at", nextRetryAt)
            put("last_error", error)
        }
        writableDatabase.update(TABLE_QUEUE, values, "id = ?", arrayOf(id.toString()))
    }

    fun retryNow(id: Long): Int {
        val values = ContentValues().apply {
            put("status", STATUS_PENDING)
            put("updated_at", System.currentTimeMillis())
            put("next_retry_at", 0L)
        }
        return writableDatabase.update(TABLE_QUEUE, values, "id = ?", arrayOf(id.toString()))
    }

    fun clearFinished(): Int {
        return writableDatabase.delete(
            TABLE_QUEUE,
            "status = ?",
            arrayOf(STATUS_SUCCEEDED),
        )
    }

    fun countByStatus(status: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(1) FROM $TABLE_QUEUE WHERE status = ?",
            arrayOf(status),
        )
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun getById(id: Long): QueuedAction? {
        val cursor = readableDatabase.query(
            TABLE_QUEUE,
            COLUMNS,
            "id = ?",
            arrayOf(id.toString()),
            null,
            null,
            null,
            "1",
        )
        return cursor.use { mapCursor(it).firstOrNull() }
    }

    private fun mapCursor(cursor: android.database.Cursor): List<QueuedAction> {
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    QueuedAction(
                        id = cursor.getLong(0),
                        createdAt = cursor.getLong(1),
                        updatedAt = cursor.getLong(2),
                        planId = cursor.getString(3),
                        stepId = cursor.getString(4),
                        connector = cursor.getString(5),
                        operation = cursor.getString(6),
                        argsJson = cursor.getString(7),
                        status = cursor.getString(8),
                        attemptCount = cursor.getInt(9),
                        maxAttempts = cursor.getInt(10),
                        nextRetryAt = cursor.getLong(11),
                        lastError = cursor.getString(12),
                        lastResult = cursor.getString(13),
                    )
                )
            }
        }
    }

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCEEDED = "SUCCEEDED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"

        private const val DB_NAME = "proactive_action_queue.db"
        private const val DB_VERSION = 1
        private const val TABLE_QUEUE = "queued_actions"

        private val COLUMNS = arrayOf(
            "id",
            "created_at",
            "updated_at",
            "plan_id",
            "step_id",
            "connector",
            "operation",
            "args_json",
            "status",
            "attempt_count",
            "max_attempts",
            "next_retry_at",
            "last_error",
            "last_result",
        )

        @Volatile
        private var instance: ActionQueueStore? = null

        fun getInstance(context: Context): ActionQueueStore {
            return instance ?: synchronized(this) {
                instance ?: ActionQueueStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
