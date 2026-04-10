package com.proactiveai.extreme.orchestrator

import android.os.Build

enum class OrchestratorEnvironment {
    LOCAL,
    CLOUD,
}

object OrchestratorConfig {
    private const val LOCAL_EMULATOR_BASE_URL: String = "http://10.0.2.2:8080"
    private const val LOCAL_USB_REVERSE_BASE_URL: String = "http://127.0.0.1:8080"

    // Replace this after App Runner deployment.
    const val CLOUD_BASE_URL: String = "https://replace-with-app-runner-url"

    var environment: OrchestratorEnvironment = OrchestratorEnvironment.LOCAL

    fun baseUrl(): String {
        return when (environment) {
            OrchestratorEnvironment.LOCAL -> {
                if (isProbablyEmulator()) {
                    LOCAL_EMULATOR_BASE_URL
                } else {
                    LOCAL_USB_REVERSE_BASE_URL
                }
            }
            OrchestratorEnvironment.CLOUD -> CLOUD_BASE_URL
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
}
