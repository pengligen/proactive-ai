package com.proactiveai.extreme.orchestrator

import android.content.Context
import com.proactiveai.extreme.app.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

interface MobileItemsGateway {
    fun ingestItems(payload: MobileItemBatchPayload): Result<Int>
}

class HttpMobileItemsGateway(
    private val context: Context,
) : MobileItemsGateway {
    override fun ingestItems(payload: MobileItemBatchPayload): Result<Int> {
        if (AppPrefs.isGlobalLockEnabled(context)) {
            return Result.failure(IllegalStateException("Global lock enabled"))
        }

        return runCatching {
            val requestBody = JSONObject().apply {
                put("deviceId", payload.deviceId)
                put("items", JSONArray().apply {
                    payload.items.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("itemType", item.itemType)
                                put("title", item.title)
                                put("summary", item.summary)
                                put("payload", item.payload.toJsonObject())
                                put("salience", item.salience)
                                put("confidence", item.confidence)
                                put("dedupeKey", item.dedupeKey)
                                put("occurredAt", isoString(item.occurredAt))
                                put("availableAt", isoString(item.availableAt))
                                put("expiresAt", isoString(item.expiresAt))
                            }
                        )
                    }
                })
            }.toString()

            val connection = URL(MobileSyncConfig.ingestItemsUrl(context))
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                error("Mobile item ingest failed with status $code: $body")
            }
            JSONObject(body).optInt("accepted", 0)
        }
    }

    private fun isoString(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis).toString()
    }
}
