package com.proactiveai.extreme.orchestrator

import android.content.Context
import android.os.Build
import com.proactiveai.extreme.app.AppPrefs

object MobileSyncConfig {
    private const val LOCAL_PORT: Int = 3000
    private const val LOCAL_EMULATOR_BASE_URL: String = "http://10.0.2.2:$LOCAL_PORT"
    private const val LOCAL_USB_REVERSE_BASE_URL: String = "http://127.0.0.1:$LOCAL_PORT"
    private const val HEALTH_PATH: String = "/health"
    private const val MOBILE_ITEMS_INGEST_PATH: String = "/api/mobile/items/ingest"

    fun baseUrl(context: Context): String {
        val override = AppPrefs.getMobileApiBaseUrl(context)
        if (override.isNotBlank()) return override
        return if (isProbablyEmulator()) {
            LOCAL_EMULATOR_BASE_URL
        } else {
            LOCAL_USB_REVERSE_BASE_URL
        }
    }

    private fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fingerprint.contains("generic")
            || fingerprint.contains("emulator")
            || model.contains("emulator")
            || model.contains("sdk")
            || product.contains("sdk")
            || product.contains("emulator")
    }

    fun ingestItemsUrl(context: Context): String {
        return buildIngestItemsUrl(baseUrl(context))
    }

    fun healthUrl(context: Context): String {
        return buildHealthUrl(baseUrl(context))
    }

    fun buildHealthUrl(baseUrl: String): String {
        return baseUrl.trimEnd('/') + HEALTH_PATH
    }

    fun buildIngestItemsUrl(baseUrl: String): String {
        return baseUrl.trimEnd('/') + MOBILE_ITEMS_INGEST_PATH
    }
}
