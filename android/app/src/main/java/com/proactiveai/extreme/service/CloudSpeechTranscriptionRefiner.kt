package com.proactiveai.extreme.service

import android.content.Context
import com.proactiveai.extreme.BuildConfig
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.orchestrator.toMap
import com.proactiveai.extreme.storage.ContextEventStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

object CloudSpeechTranscriptionRefiner {
    private const val SESSION_WINDOW_MS = 15 * 60 * 1000L
    private const val MODEL_ID = "gpt-4o-transcribe"
    private const val ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
    private const val MAX_CLIPS_PER_RUN = 8
    private const val WAV_HEADER_BYTES = 44L

    data class SingleClipOutcome(
        val status: String,
        val transcript: String,
        val detail: String,
    )

    suspend fun runIfDue(context: Context) {
        if (AppPrefs.isGlobalLockEnabled(context)) return

        val apiKey = resolveApiKey(context)
        if (apiKey.isBlank()) return

        val bucket = System.currentTimeMillis() / SESSION_WINDOW_MS
        if (AppPrefs.getAudioRefineLastBucket(context) >= bucket) return

        val audioDir = File(context.filesDir, "audio_capture")
        if (!audioDir.exists()) {
            AppPrefs.setAudioRefineLastBucket(context, bucket)
            return
        }

        val store = ContextEventStore.getInstance(context)
        val candidates = loadCandidates(audioDir).take(MAX_CLIPS_PER_RUN)
        if (candidates.isEmpty()) {
            AppPrefs.setAudioRefineLastBucket(context, bucket)
            return
        }

        var recognized = 0
        var errors = 0
        var emittedEvents = 0
        val refreshedSessionBuckets = mutableSetOf<Long>()

        candidates.forEach { clip ->
            val startedAt = System.currentTimeMillis()
            val result = kotlin.runCatching {
                transcribeClip(clip.wavFile, apiKey)
            }

            result.onSuccess { transcript ->
                val normalized = normalizeTranscript(transcript)
                if (normalized.isBlank() || isNoSpeechText(normalized)) {
                    updateMeta(
                        clip = clip,
                        status = "no_speech",
                        transcript = "",
                        error = "empty_or_no_speech",
                        latencyMs = System.currentTimeMillis() - startedAt,
                    )
                    return@onSuccess
                }

                val textHash = stableHash(normalized)
                val shouldEmitEvent = clip.openAiContextHash != textHash
                if (shouldEmitEvent) {
                    store.insert(
                        buildContextEvent(
                            clip = clip,
                            refinedTranscript = normalized,
                            manualTrigger = false,
                        )
                    )
                    emittedEvents += 1
                    refreshedSessionBuckets += (clip.createdAt / SESSION_WINDOW_MS)
                }

                updateMeta(
                    clip = clip,
                    status = "recognized",
                    transcript = normalized,
                    error = "",
                    latencyMs = System.currentTimeMillis() - startedAt,
                    contextHash = if (shouldEmitEvent) textHash else clip.openAiContextHash,
                )
                recognized += 1
            }

            result.onFailure { throwable ->
                updateMeta(
                    clip = clip,
                    status = "error",
                    transcript = clip.openAiTranscript,
                    error = throwable.message.orEmpty().take(280),
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                errors += 1
            }
        }

        store.insert(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = "audio_refiner",
                category = "audio",
                summary = "Cloud STT refine run: total=${candidates.size}, recognized=$recognized, errors=$errors, contextEvents=$emittedEvents",
                payload = mapOf(
                    "status" to "cloud_refine_batch",
                    "strategy" to MODEL_ID,
                    "total" to candidates.size,
                    "recognized" to recognized,
                    "errors" to errors,
                    "emittedContextEvents" to emittedEvents,
                    "bucket" to bucket,
                    "dir" to audioDir.absolutePath,
                ),
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 7 * 24 * 3600,
            )
        )

        refreshedSessionBuckets.forEach { sessionBucket ->
            AssistantSessionAutoRunner.refreshSessionForTimestamp(
                context = context,
                occurredAtMs = sessionBucket * SESSION_WINDOW_MS,
            )
        }

        AppPrefs.setAudioRefineLastBucket(context, bucket)
    }

    suspend fun transcribeSingleClip(
        context: Context,
        wavPath: String,
        manualTrigger: Boolean = true,
    ): SingleClipOutcome {
        if (AppPrefs.isGlobalLockEnabled(context)) {
            return SingleClipOutcome(
                status = "blocked",
                transcript = "",
                detail = "Global lock enabled: cloud transcription is blocked.",
            )
        }

        val apiKey = resolveApiKey(context)
        if (apiKey.isBlank()) {
            return SingleClipOutcome(
                status = "error",
                transcript = "",
                detail = "OpenAI API key missing. Set it in Models -> Model Config.",
            )
        }

        val wavFile = File(wavPath)
        if (!wavFile.exists() || !wavFile.isFile) {
            return SingleClipOutcome(
                status = "error",
                transcript = "",
                detail = "WAV file not found: $wavPath",
            )
        }

        val clip = loadSingleClipCandidate(wavFile) ?: return SingleClipOutcome(
            status = "error",
            transcript = "",
            detail = "Invalid clip metadata or unsupported wav.",
        )

        val store = ContextEventStore.getInstance(context)
        val startedAt = System.currentTimeMillis()
        return kotlin.runCatching {
            transcribeClip(wavFile, apiKey)
        }.fold(
            onSuccess = { transcript ->
                val normalized = normalizeTranscript(transcript)
                if (normalized.isBlank() || isNoSpeechText(normalized)) {
                    updateMeta(
                        clip = clip,
                        status = "no_speech",
                        transcript = "",
                        error = "empty_or_no_speech",
                        latencyMs = System.currentTimeMillis() - startedAt,
                    )
                    return@fold SingleClipOutcome(
                        status = "no_speech",
                        transcript = "",
                        detail = "Model returned no speech.",
                    )
                }

                val textHash = stableHash(normalized)
                store.insert(
                    buildContextEvent(
                        clip = clip,
                        refinedTranscript = normalized,
                        manualTrigger = manualTrigger,
                    )
                )
                AssistantSessionAutoRunner.refreshSessionForTimestamp(
                    context = context,
                    occurredAtMs = clip.createdAt,
                )
                updateMeta(
                    clip = clip,
                    status = "recognized",
                    transcript = normalized,
                    error = "",
                    latencyMs = System.currentTimeMillis() - startedAt,
                    contextHash = textHash,
                )
                SingleClipOutcome(
                    status = "recognized",
                    transcript = normalized,
                    detail = "Cloud transcription updated.",
                )
            },
            onFailure = { throwable ->
                updateMeta(
                    clip = clip,
                    status = "error",
                    transcript = clip.openAiTranscript,
                    error = throwable.message.orEmpty().take(280),
                    latencyMs = System.currentTimeMillis() - startedAt,
                )
                SingleClipOutcome(
                    status = "error",
                    transcript = clip.openAiTranscript,
                    detail = throwable.message.orEmpty().ifBlank { "Unknown cloud STT error." },
                )
            },
        )
    }

    private suspend fun transcribeClip(wavFile: File, apiKey: String): String = withContext(Dispatchers.IO) {
        val boundary = "----ProactiveAiBoundary${System.currentTimeMillis()}"
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { output ->
                val writer = OutputStreamWriter(output, Charsets.UTF_8)

                fun writeField(name: String, value: String) {
                    writer.write("--$boundary\r\n")
                    writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    writer.write(value)
                    writer.write("\r\n")
                }

                writeField("model", MODEL_ID)
                writeField("response_format", "json")
                writeField("temperature", "0")

                writer.write("--$boundary\r\n")
                writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${wavFile.name}\"\r\n")
                writer.write("Content-Type: audio/wav\r\n\r\n")
                writer.flush()

                wavFile.inputStream().use { input -> input.copyTo(output) }
                output.flush()

                writer.write("\r\n--$boundary--\r\n")
                writer.flush()
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                InputStreamReader(input, Charsets.UTF_8).readText()
            }.orEmpty()

            if (code !in 200..299) {
                error("OpenAI STT failed: http=$code body=${body.take(280)}")
            }

            val json = JSONObject(body)
            val text = json.optString("text")
                .ifBlank { json.optString("transcript") }
                .trim()

            if (text.isBlank()) {
                error("OpenAI STT empty transcript.")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun loadCandidates(audioDir: File): List<ClipCandidate> {
        val wavFiles = audioDir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        return wavFiles.mapNotNull { wav ->
            val metaFile = File(wav.parentFile, "${wav.nameWithoutExtension}.json")
            val metaJson = if (metaFile.exists()) {
                kotlin.runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
            } else {
                JSONObject()
            }

            val metaMap = kotlin.runCatching { metaJson.toMap() }.getOrDefault(emptyMap())
            val createdAt = (metaMap["createdAt"] as? Number)?.toLong() ?: wav.lastModified()

            val durationMs = (metaMap["durationMs"] as? Number)?.toLong() ?: estimateWavDurationMs(wav.length())
            val clipRmsDb = (metaMap["clipRmsDb"] as? Number)?.toDouble()
            val voiceLikely = (metaMap["voiceLikely"] as? Boolean) == true

            val localStatus = metaMap["status"]?.toString().orEmpty()
            if (localStatus == "no_speech") return@mapNotNull null
            val localTranscript = metaMap["transcript"]?.toString().orEmpty()
            val localStitched = metaMap["stitchedTranscript"]?.toString().orEmpty()
            val existingCloudStatus = metaMap["openAiRefinedStatus"]?.toString().orEmpty()
            val existingCloudTranscript = metaMap["openAiRefinedTranscript"]?.toString().orEmpty()
            val existingCloudHash = metaMap["openAiContextHash"]?.toString().orEmpty()

            val hasSignal = durationMs >= 1_200L &&
                wav.length() > WAV_HEADER_BYTES + 1024L &&
                (voiceLikely || (clipRmsDb ?: -120.0) > -54.0 || localTranscript.isNotBlank() || localStitched.isNotBlank())

            val needsRefine = existingCloudStatus != "recognized" || existingCloudTranscript.isBlank()
            if (!hasSignal || !needsRefine) return@mapNotNull null

            ClipCandidate(
                wavFile = wav,
                metaFile = metaFile,
                metaJson = metaJson,
                createdAt = createdAt,
                durationMs = durationMs,
                localStatus = localStatus,
                localTranscript = localTranscript,
                localStitched = localStitched,
                openAiTranscript = existingCloudTranscript,
                openAiContextHash = existingCloudHash,
            )
        }
    }

    private fun loadSingleClipCandidate(wav: File): ClipCandidate? {
        val metaFile = File(wav.parentFile ?: return null, "${wav.nameWithoutExtension}.json")
        val metaJson = if (metaFile.exists()) {
            kotlin.runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: JSONObject()
        } else {
            JSONObject()
        }
        val metaMap = kotlin.runCatching { metaJson.toMap() }.getOrDefault(emptyMap())
        val createdAt = (metaMap["createdAt"] as? Number)?.toLong() ?: wav.lastModified()
        val durationMs = (metaMap["durationMs"] as? Number)?.toLong() ?: estimateWavDurationMs(wav.length())
        val localStatus = metaMap["status"]?.toString().orEmpty()
        val localTranscript = metaMap["transcript"]?.toString().orEmpty()
        val localStitched = metaMap["stitchedTranscript"]?.toString().orEmpty()
        val existingCloudTranscript = metaMap["openAiRefinedTranscript"]?.toString().orEmpty()
        val existingCloudHash = metaMap["openAiContextHash"]?.toString().orEmpty()
        val hasSignal = durationMs >= 800L && wav.length() > WAV_HEADER_BYTES + 512L
        if (!hasSignal) return null

        return ClipCandidate(
            wavFile = wav,
            metaFile = metaFile,
            metaJson = metaJson,
            createdAt = createdAt,
            durationMs = durationMs,
            localStatus = localStatus,
            localTranscript = localTranscript,
            localStitched = localStitched,
            openAiTranscript = existingCloudTranscript,
            openAiContextHash = existingCloudHash,
        )
    }

    private fun updateMeta(
        clip: ClipCandidate,
        status: String,
        transcript: String,
        error: String,
        latencyMs: Long,
        contextHash: String = clip.openAiContextHash,
    ) {
        clip.metaJson.put("openAiRefinedStatus", status)
        clip.metaJson.put("openAiRefinedTranscript", transcript)
        clip.metaJson.put("openAiRefinedModel", MODEL_ID)
        clip.metaJson.put("openAiRefinedAt", System.currentTimeMillis())
        clip.metaJson.put("openAiRefinedError", error)
        clip.metaJson.put("openAiLatencyMs", latencyMs)
        clip.metaJson.put("openAiContextHash", contextHash)
        clip.metaJson.put("openAiSource", "cloud")
        kotlin.runCatching {
            clip.metaFile.writeText(clip.metaJson.toString())
        }
    }

    private fun buildContextEvent(
        clip: ClipCandidate,
        refinedTranscript: String,
        manualTrigger: Boolean,
    ): ContextEvent {
        val stitched = refinedTranscript.take(MAX_STITCHED_TRANSCRIPT_CHARS)
        val refinedAt = System.currentTimeMillis()
        return ContextEvent(
            eventId = UUID.randomUUID().toString(),
            occurredAt = clip.createdAt,
            source = "audio_refiner",
            category = "audio",
            summary = "Ambient speech transcript (cloud-refined): $stitched",
            payload = mapOf(
                "status" to "recognized",
                "strategy" to MODEL_ID,
                "transcript" to refinedTranscript,
                "stitchedTranscript" to stitched,
                "modelStatus" to "cloud_refined",
                "wavPath" to clip.wavFile.absolutePath,
                "metaPath" to clip.metaFile.absolutePath,
                "durationMs" to clip.durationMs,
                "clipOccurredAt" to clip.createdAt,
                "transcriptionEventAt" to refinedAt,
                "localStatus" to clip.localStatus,
                "localTranscript" to clip.localTranscript,
                "localStitchedTranscript" to clip.localStitched,
                "refinedByCloud" to true,
                "manualTrigger" to manualTrigger,
            ),
            sensitivity = Sensitivity.HIGH,
            ttlSeconds = 7 * 24 * 3600,
        )
    }

    private fun resolveApiKey(context: Context): String {
        return AppPrefs.getOpenAiApiKey(context)
            .ifBlank { BuildConfig.OPENAI_API_KEY.orEmpty() }
            .trim()
    }

    private fun normalizeTranscript(raw: String): String {
        return raw
            .trim()
            .replace("\u0000", "")
            .replace(Regex("\\s+"), " ")
            .take(MAX_STITCHED_TRANSCRIPT_CHARS)
    }

    private fun isNoSpeechText(text: String): Boolean {
        val lower = text.lowercase(Locale.US).trim()
        return lower == "<no-speech>" ||
            lower == "no speech" ||
            lower == "no_speech" ||
            lower == "[silence]" ||
            lower.contains("no clear speech")
    }

    private fun stableHash(text: String): String {
        val value = text.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")
        return value.hashCode().toUInt().toString(16)
    }

    private fun estimateWavDurationMs(fileSizeBytes: Long): Long {
        val pcmBytes = (fileSizeBytes - WAV_HEADER_BYTES).coerceAtLeast(0L)
        val bytesPerSecond = 16_000L * 2L
        if (bytesPerSecond <= 0L) return 0L
        return (pcmBytes * 1000L) / bytesPerSecond
    }

    private data class ClipCandidate(
        val wavFile: File,
        val metaFile: File,
        val metaJson: JSONObject,
        val createdAt: Long,
        val durationMs: Long,
        val localStatus: String,
        val localTranscript: String,
        val localStitched: String,
        val openAiTranscript: String,
        val openAiContextHash: String,
    )

    private const val MAX_STITCHED_TRANSCRIPT_CHARS = 1_400
}
