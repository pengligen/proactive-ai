package com.proactiveai.extreme.orchestrator

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

fun interface MobileSyncHealthGateway {
    fun health(): Result<Boolean>
}

class HttpMobileSyncHealthGateway(
    private val context: Context? = null,
    private val baseUrl: String? = null,
    private val transport: (method: String, url: String) -> Pair<Int, String> = ::defaultTransport,
) : MobileSyncHealthGateway {
    override fun health(): Result<Boolean> {
        return runCatching {
            val resolvedBaseUrl = when {
                !baseUrl.isNullOrBlank() -> baseUrl
                context != null -> MobileSyncConfig.baseUrl(context)
                else -> ""
            }
            val (code, body) = transport("GET", MobileSyncConfig.buildHealthUrl(resolvedBaseUrl))
            code in 200..299 && parseOkFlag(body)
        }
    }

    companion object {
        private val okTrueRegex = Regex("""(^|[,{]\s*)"ok"\s*:\s*true(?=\s*[,}])""")

        private fun parseOkFlag(body: String): Boolean {
            return okTrueRegex.containsMatchIn(body)
        }

        private fun defaultTransport(method: String, url: String): Pair<Int, String> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            connection.disconnect()
            return code to body
        }
    }
}
