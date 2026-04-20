package com.proactiveai.extreme.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.orchestrator.toMap
import org.json.JSONObject

class ContextEventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createContextEventsTable(db)
        createMobileItemsOutboxTable(db)
        createCollectorStateTable(db)
        createContactDirectoryTable(db)
        createContactIdentityIndexTable(db)
        createAppInventoryStateTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createMobileItemsOutboxTable(db)
            createCollectorStateTable(db)
        }
        if (oldVersion in 2 until 3) {
            db.execSQL("ALTER TABLE $TABLE_MOBILE_ITEMS ADD COLUMN synced_at INTEGER")
        }
        if (oldVersion < 4) {
            createContactDirectoryTable(db)
            createContactIdentityIndexTable(db)
            createAppInventoryStateTable(db)
        }
    }

    fun insert(event: ContextEvent): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val inserted = insertInternal(event, db)
            db.setTransactionSuccessful()
            return inserted
        } finally {
            db.endTransaction()
        }
    }

    fun insertAll(events: List<ContextEvent>): Int {
        val db = writableDatabase
        var insertedItems = 0
        db.beginTransaction()
        try {
            events.forEach { event ->
                if (insertInternal(event, db)) {
                    insertedItems += 1
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return insertedItems
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

    fun countEventsByCategory(category: String): Int {
        val cursor = readableDatabase.rawQuery(
            "SELECT COUNT(1) FROM $TABLE_EVENTS WHERE category = ?",
            arrayOf(category),
        )
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun pruneExpired(nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_EVENTS WHERE occurred_at + (ttl_seconds * 1000) < ?",
            arrayOf(nowMs),
        )
    }

    fun getUnsyncedMobileItems(limit: Int): List<StoredMobileSyncItem> {
        val cursor = readableDatabase.query(
            TABLE_MOBILE_ITEMS,
            arrayOf(
                "id",
                "item_id",
                "item_type",
                "title",
                "summary",
                "payload_json",
                "salience",
                "confidence",
                "dedupe_key",
                "occurred_at",
                "available_at",
                "expires_at",
                "synced",
            ),
            "synced = 0",
            null,
            null,
            null,
            "available_at ASC, occurred_at ASC",
            limit.toString(),
        )
        return cursor.use { mapCursorToMobileItems(it) }
    }

    fun markMobileItemsSynced(ids: List<Long>) {
        if (ids.isEmpty()) return
        val args = ids.joinToString(separator = ",") { "?" }
        val selectionArgs = buildList {
            add(System.currentTimeMillis().toString())
            ids.forEach { add(it.toString()) }
        }.toTypedArray()
        writableDatabase.execSQL(
            "UPDATE $TABLE_MOBILE_ITEMS SET synced = 1, synced_at = ? WHERE id IN ($args)",
            selectionArgs,
        )
    }

    fun pruneSyncedMobileItems(nowMs: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL(
            "DELETE FROM $TABLE_MOBILE_ITEMS WHERE synced = 1 AND synced_at IS NOT NULL AND synced_at < ?",
            arrayOf(nowMs - SYNCED_ITEM_RETENTION_MS),
        )
    }

    fun countUnsyncedMobileItems(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(1) FROM $TABLE_MOBILE_ITEMS WHERE synced = 0", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun backfillDerivedItemsForCategories(categories: Set<String>, limit: Int = 600): Int {
        if (categories.isEmpty()) return 0
        val placeholders = categories.joinToString(separator = ",") { "?" }
        val args = categories.toTypedArray()
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
            "category IN ($placeholders)",
            args,
            null,
            null,
            "occurred_at DESC",
            limit.toString(),
        )
        val events = cursor.use { mapCursorToEvents(it) }
            .mapNotNull { stored ->
                kotlin.runCatching {
                    val payload = JSONObject(stored.payloadJson).toMap()
                    val compactPayload = buildMap<String, Any> {
                        payload.forEach { (key, value) ->
                            if (key.isNotBlank() && value != null) {
                                put(key, value)
                            }
                        }
                    }
                    ContextEvent(
                        eventId = stored.eventId,
                        occurredAt = stored.occurredAt,
                        source = stored.source,
                        category = stored.category,
                        summary = stored.summary,
                        payload = compactPayload,
                        sensitivity = kotlin.runCatching {
                            com.proactiveai.extreme.core.context.Sensitivity.valueOf(stored.sensitivity)
                        }.getOrDefault(com.proactiveai.extreme.core.context.Sensitivity.MEDIUM),
                        ttlSeconds = stored.ttlSeconds,
                    )
                }.getOrNull()
            }
        if (events.isEmpty()) return 0

        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            events.forEach { event ->
                inserted += enqueueDerivedItems(event, db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun getCollectorState(key: String): String? {
        val cursor = readableDatabase.query(
            TABLE_COLLECTOR_STATE,
            arrayOf("value"),
            "state_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1",
        )
        return cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun putCollectorState(key: String, value: String) {
        val values = ContentValues().apply {
            put("state_key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(TABLE_COLLECTOR_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeCollectorState(key: String) {
        writableDatabase.delete(TABLE_COLLECTOR_STATE, "state_key = ?", arrayOf(key))
    }

    fun replaceContactDirectory(
        contacts: List<ContactDirectoryRow>,
        identities: List<ContactIdentityRow>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        withWritableTransaction { db ->
            db.delete(TABLE_CONTACT_IDENTITY_INDEX, null, null)
            db.delete(TABLE_CONTACT_DIRECTORY, null, null)

            contacts.forEach { row ->
                val values = ContentValues().apply {
                    put("contact_id", row.contactId)
                    put("lookup_key", row.lookupKey)
                    put("display_name", row.displayName)
                    put("display_name_alt", row.displayNameAlt)
                    put("photo_uri", row.photoUri)
                    put("starred", if (row.starred) 1 else 0)
                    put("organization", row.organization)
                    put("title", row.title)
                    put("note", row.note)
                    put("relations_json", row.relationsJson)
                    put("events_json", row.eventsJson)
                    put("groups_json", row.groupsJson)
                    put("account_type", row.accountType)
                    put("account_name", row.accountName)
                    put("last_updated_ts", row.lastUpdatedTs)
                    put("last_contacted_ts", row.lastContactedTs)
                    put("times_contacted", row.timesContacted)
                    put("last_seen_scan_id", row.lastSeenScanId)
                    put("content_hash", row.contentHash)
                    put("deleted", 0)
                    put("updated_at", nowMs)
                }
                db.insertWithOnConflict(TABLE_CONTACT_DIRECTORY, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }

            identities.forEach { row ->
                val values = ContentValues().apply {
                    put("contact_id", row.contactId)
                    put("kind", row.kind)
                    put("normalized_value", row.normalizedValue)
                    put("normalized_hash", row.normalizedHash)
                    put("label", row.label)
                    put("is_primary", if (row.isPrimary) 1 else 0)
                    put("updated_at", nowMs)
                }
                db.insertWithOnConflict(TABLE_CONTACT_IDENTITY_INDEX, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun replaceAppInventory(
        apps: List<AppInventoryRow>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        withWritableTransaction { db ->
            db.delete(TABLE_APP_INVENTORY_STATE, null, null)
            apps.forEach { row ->
                val values = ContentValues().apply {
                    put("package_name", row.packageName)
                    put("app_label", row.appLabel)
                    put("version_name", row.versionName)
                    put("version_code", row.versionCode)
                    put("first_install_time", row.firstInstallTime)
                    put("last_update_time", row.lastUpdateTime)
                    put("content_hash", row.contentHash)
                    put("deleted", 0)
                    put("updated_at", nowMs)
                }
                db.insertWithOnConflict(TABLE_APP_INVENTORY_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun <T> withWritableTransaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun <T> withReadableDatabase(block: (SQLiteDatabase) -> T): T {
        return block(readableDatabase)
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

    private fun mapCursorToMobileItems(cursor: android.database.Cursor): List<StoredMobileSyncItem> {
        return buildList {
            while (cursor.moveToNext()) {
                add(
                    StoredMobileSyncItem(
                        id = cursor.getLong(0),
                        itemId = cursor.getString(1),
                        itemType = cursor.getString(2),
                        title = cursor.getString(3),
                        summary = cursor.getString(4),
                        payloadJson = cursor.getString(5),
                        salience = cursor.getDouble(6),
                        confidence = cursor.getDouble(7),
                        dedupeKey = cursor.getString(8),
                        occurredAt = cursor.getLong(9),
                        availableAt = cursor.getLong(10),
                        expiresAt = cursor.getLong(11),
                        synced = cursor.getInt(12) == 1,
                    ),
                )
            }
        }
    }

    private fun enqueueDerivedItems(event: ContextEvent, db: SQLiteDatabase): Int {
        var inserted = 0
        ItemDerivationEngine.derive(event).forEach { item ->
            val values = ContentValues().apply {
                put("item_id", item.itemId)
                put("item_type", item.itemType)
                put("title", item.title)
                put("summary", item.summary)
                put("payload_json", JSONObject(item.payload).toString())
                put("salience", item.salience)
                put("confidence", item.confidence)
                put("dedupe_key", item.dedupeKey)
                put("occurred_at", item.occurredAt)
                put("available_at", item.availableAt)
                put("expires_at", item.expiresAt)
                put("synced", 0)
                put("inserted_at", System.currentTimeMillis())
            }
            val rowId = db.insertWithOnConflict(TABLE_MOBILE_ITEMS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            if (rowId != -1L) {
                inserted += 1
            }
        }
        return inserted
    }

    private fun insertInternal(event: ContextEvent, db: SQLiteDatabase): Boolean {
        val filtered = EventFilterEngine.filter(
            event = event,
            state = SQLiteCollectorStateStore(db),
        ) ?: return false

        val values = ContentValues().apply {
            put("event_id", filtered.eventId)
            put("occurred_at", filtered.occurredAt)
            put("source", filtered.source)
            put("category", filtered.category)
            put("summary", filtered.summary)
            put("payload_json", JSONObject(filtered.payload).toString())
            put("sensitivity", filtered.sensitivity.name)
            put("ttl_seconds", filtered.ttlSeconds)
            put("synced", 0)
            put("inserted_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE_EVENTS, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        val insertedItems = enqueueDerivedItems(filtered, db)
        return insertedItems > 0
    }

    private class SQLiteCollectorStateStore(
        private val db: SQLiteDatabase,
    ) : CollectorStateStore {
        override fun get(key: String): String? {
            val cursor = db.query(
                TABLE_COLLECTOR_STATE,
                arrayOf("value"),
                "state_key = ?",
                arrayOf(key),
                null,
                null,
                null,
                "1",
            )
            return cursor.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }

        override fun put(key: String, value: String) {
            val values = ContentValues().apply {
                put("state_key", key)
                put("value", value)
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(TABLE_COLLECTOR_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun createContextEventsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
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

    private fun createMobileItemsOutboxTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MOBILE_ITEMS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id TEXT NOT NULL UNIQUE,
                item_type TEXT NOT NULL,
                title TEXT NOT NULL,
                summary TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                salience REAL NOT NULL,
                confidence REAL NOT NULL,
                dedupe_key TEXT NOT NULL UNIQUE,
                occurred_at INTEGER NOT NULL,
                available_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                synced_at INTEGER,
                inserted_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createCollectorStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_COLLECTOR_STATE (
                state_key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createContactDirectoryTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACT_DIRECTORY (
                contact_id INTEGER PRIMARY KEY,
                lookup_key TEXT,
                display_name TEXT NOT NULL,
                display_name_alt TEXT,
                photo_uri TEXT,
                starred INTEGER NOT NULL DEFAULT 0,
                organization TEXT,
                title TEXT,
                note TEXT,
                relations_json TEXT NOT NULL DEFAULT '[]',
                events_json TEXT NOT NULL DEFAULT '[]',
                groups_json TEXT NOT NULL DEFAULT '[]',
                account_type TEXT,
                account_name TEXT,
                last_updated_ts INTEGER,
                last_contacted_ts INTEGER,
                times_contacted INTEGER,
                last_seen_scan_id TEXT,
                content_hash TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_contact_directory_last_seen_scan
            ON $TABLE_CONTACT_DIRECTORY(last_seen_scan_id)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_contact_directory_deleted
            ON $TABLE_CONTACT_DIRECTORY(deleted)
            """.trimIndent()
        )
    }

    private fun createContactIdentityIndexTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CONTACT_IDENTITY_INDEX (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                normalized_value TEXT NOT NULL,
                normalized_hash TEXT NOT NULL,
                label TEXT,
                is_primary INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                UNIQUE(contact_id, kind, normalized_value)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_contact_identity_hash
            ON $TABLE_CONTACT_IDENTITY_INDEX(normalized_hash)
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_contact_identity_contact
            ON $TABLE_CONTACT_IDENTITY_INDEX(contact_id)
            """.trimIndent()
        )
    }

    private fun createAppInventoryStateTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_APP_INVENTORY_STATE (
                package_name TEXT PRIMARY KEY,
                app_label TEXT NOT NULL,
                version_name TEXT,
                version_code INTEGER,
                first_install_time INTEGER,
                last_update_time INTEGER,
                content_hash TEXT NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_app_inventory_deleted
            ON $TABLE_APP_INVENTORY_STATE(deleted)
            """.trimIndent()
        )
    }

    companion object {
        private const val DB_NAME = "proactive_events.db"
        private const val DB_VERSION = 4
        private const val TABLE_EVENTS = "context_events"
        private const val TABLE_MOBILE_ITEMS = "mobile_items_outbox"
        private const val TABLE_COLLECTOR_STATE = "collector_state"
        private const val TABLE_CONTACT_DIRECTORY = "contact_directory"
        private const val TABLE_CONTACT_IDENTITY_INDEX = "contact_identity_index"
        private const val TABLE_APP_INVENTORY_STATE = "app_inventory_state"
        private const val SYNCED_ITEM_RETENTION_MS = 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: ContextEventStore? = null

        fun getInstance(context: Context): ContextEventStore {
            return instance ?: synchronized(this) {
                instance ?: ContextEventStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
