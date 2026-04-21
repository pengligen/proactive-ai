package com.proactiveai.extreme.core.edge

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes > 0) ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
}

object RemoteModelDownloader {
    suspend fun downloadToAppStorage(
        context: Context,
        url: String,
        huggingFaceToken: String?,
        preferredFileName: String?,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = URL(url)
                val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 300_000
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "ProactiveAI-Android/0.1")
                    if (!huggingFaceToken.isNullOrBlank()) {
                        setRequestProperty("Authorization", "Bearer $huggingFaceToken")
                    }
                    instanceFollowRedirects = true
                }

                connection.connect()
                val code = connection.responseCode
                if (code !in 200..299) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                    error("Model download failed (HTTP $code): ${errorBody.take(180)}")
                }

                val totalBytes = connection.contentLengthLong
                val fileName = resolveFileName(
                    url = url,
                    contentDisposition = connection.getHeaderField("Content-Disposition"),
                    preferred = preferredFileName,
                )
                val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                val destination = File(modelsDir, fileName)
                val temp = File(modelsDir, "$fileName.part")
                if (temp.exists()) {
                    temp.delete()
                }

                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var downloaded = 0L
                            var read = input.read(buffer)
                            while (read >= 0) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                onProgress(DownloadProgress(downloaded, totalBytes))
                                read = input.read(buffer)
                            }
                            output.flush()
                        }
                    }

                    if (destination.exists()) {
                        destination.delete()
                    }
                    if (!temp.renameTo(destination)) {
                        error("Failed to move downloaded model into place.")
                    }
                    destination.absolutePath
                } catch (error: Throwable) {
                    temp.delete()
                    throw error
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    internal fun describeFailure(error: Throwable): String {
        val message = error.message?.trim().orEmpty()
        if (message.isNotBlank()) {
            return message
        }

        val simpleName = error::class.java.simpleName.orEmpty()
        return simpleName.ifBlank { "unknown_error" }
    }

    internal fun resolveFileName(
        url: String,
        contentDisposition: String?,
        preferred: String?,
    ): String {
        if (!preferred.isNullOrBlank()) {
            return sanitize(preferred)
        }

        val headerName = contentDisposition
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
        if (!headerName.isNullOrBlank()) {
            return sanitize(headerName)
        }

        val pathName = url.substringAfterLast("/").substringBefore("?").ifBlank { "downloaded_model.task" }
        return sanitize(pathName)
    }

    internal fun sanitize(fileName: String): String {
        val cleaned = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (cleaned.isBlank()) "downloaded_model.task" else cleaned
    }
}
