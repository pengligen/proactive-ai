package com.proactiveai.extreme.ui.permission

internal data class CollectionServiceStatusUi(
    val statusLabel: String,
    val statusTone: SignalTone,
    val detail: String,
    val startActionLabel: String,
    val startActionEnabled: Boolean,
    val stopActionLabel: String,
    val stopActionEnabled: Boolean,
)

internal fun collectionServiceStatusUi(collectionEnabled: Boolean): CollectionServiceStatusUi {
    return if (collectionEnabled) {
        CollectionServiceStatusUi(
            statusLabel = "Running",
            statusTone = SignalTone.Success,
            detail = "Foreground collection is live. Android should keep an ongoing notification visible while capture stays active.",
            startActionLabel = "Service Running",
            startActionEnabled = false,
            stopActionLabel = "Stop Service",
            stopActionEnabled = true,
        )
    } else {
        CollectionServiceStatusUi(
            statusLabel = "Idle",
            statusTone = SignalTone.Warning,
            detail = "Collection is idle. Tap Start Service to resume background capture and restore the ongoing notification.",
            startActionLabel = "Start Service",
            startActionEnabled = true,
            stopActionLabel = "Service Idle",
            stopActionEnabled = false,
        )
    }
}

internal data class ManualSyncStatusUi(
    val actionLabel: String,
    val actionEnabled: Boolean,
)

internal fun manualSyncStatusUi(syncInFlight: Boolean): ManualSyncStatusUi {
    return if (syncInFlight) {
        ManualSyncStatusUi(
            actionLabel = "Syncing...",
            actionEnabled = false,
        )
    } else {
        ManualSyncStatusUi(
            actionLabel = "Sync Now",
            actionEnabled = true,
        )
    }
}

internal fun manualSyncResultMessage(queuedBefore: Int, queuedAfter: Int): String {
    return when {
        queuedBefore <= 0 && queuedAfter <= 0 -> "Queue already empty. Nothing to sync."
        queuedAfter <= 0 -> "Synced $queuedBefore uploads. Queue is now empty."
        queuedAfter < queuedBefore -> "Synced ${queuedBefore - queuedAfter} uploads. $queuedAfter still pending."
        else -> "Sync request sent, but queue is still $queuedAfter. Backend may still be processing."
    }
}
