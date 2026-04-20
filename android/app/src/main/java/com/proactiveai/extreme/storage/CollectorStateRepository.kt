package com.proactiveai.extreme.storage

class CollectorStateRepository(
    private val state: CollectorStateStore,
) {
    constructor(store: ContextEventStore) : this(
        object : MutableCollectorStateStore {
            override fun get(key: String): String? = store.getCollectorState(key)

            override fun put(key: String, value: String) {
                store.putCollectorState(key, value)
            }

            override fun remove(key: String) {
                store.removeCollectorState(key)
            }
        }
    )

    fun get(key: String): String? = state.get(key)

    fun put(key: String, value: String) {
        state.put(key, value)
    }

    fun remove(key: String) {
        if (state is MutableCollectorStateStore) state.remove(key)
    }

    fun putAll(entries: Map<String, String>) {
        entries.forEach { (key, value) ->
            put(key, value)
        }
    }

    fun markDirty(source: String, atMs: Long = System.currentTimeMillis()) {
        put("incremental.$source.dirty_at", atMs.toString())
    }

    fun getDirtyAt(source: String): Long? {
        return get("incremental.$source.dirty_at")?.toLongOrNull()
    }

    fun markBootstrapStatus(
        source: String,
        status: String,
        atMs: Long = System.currentTimeMillis(),
    ) {
        put("bootstrap.$source.status", status)
        when (status) {
            "running" -> put("bootstrap.$source.started_at", atMs.toString())
            "done", "failed" -> put("bootstrap.$source.finished_at", atMs.toString())
        }
    }

    fun getBootstrapStatus(source: String): String? {
        return get("bootstrap.$source.status")?.takeIf { it.isNotBlank() }
    }

    fun putBootstrapCursor(source: String, cursor: Map<String, Any?>) {
        put("bootstrap.$source.cursor", BootstrapCursorCodec.encode(cursor))
    }

    fun getBootstrapCursor(source: String): Map<String, Any> {
        return BootstrapCursorCodec.decode(get("bootstrap.$source.cursor"))
    }

    fun putIncrementalCursor(source: String, cursor: Map<String, Any?>) {
        put("incremental.$source.cursor", BootstrapCursorCodec.encode(cursor))
    }

    fun getIncrementalCursor(source: String): Map<String, Any> {
        return BootstrapCursorCodec.decode(get("incremental.$source.cursor"))
    }

    fun requestSummaryRefresh(source: String, atMs: Long = System.currentTimeMillis()) {
        put("summary.force_once_after.$source", atMs.toString())
    }

    fun consumeSummaryRefresh(source: String): Long? {
        val key = "summary.force_once_after.$source"
        val value = get(key)?.toLongOrNull() ?: return null
        remove(key)
        return value
    }
}

interface MutableCollectorStateStore : CollectorStateStore {
    fun remove(key: String)
}
