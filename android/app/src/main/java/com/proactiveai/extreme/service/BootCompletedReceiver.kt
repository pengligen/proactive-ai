package com.proactiveai.extreme.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.sync.SyncScheduler

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isRecoveryTrigger = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isRecoveryTrigger) return

        SyncScheduler.ensurePeriodic(context)
        if (AppPrefs.isCollectionEnabled(context) && AppPrefs.isMasterEnabled(context)) {
            ProactiveCollectionService.start(context)
        }
    }
}
