package com.proactiveai.extreme.core.context.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource as MlKitAudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition as MlKitSpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as MlKitSpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions as MlKitSpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest as MlKitSpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse as MlKitSpeechRecognizerResponse
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.context.Sensitivity
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LiteRtLmRuntime
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.core.model.PluginDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt
import org.json.JSONObject

class AudioAmbientPlugin(
    private val context: Context,
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    private var workerScope: CoroutineScope? = null
    private var workerJob: Job? = null

    private val pendingEvents = ArrayDeque<ContextEvent>()
    private val queueLock = Any()

    private var lastNoSpeechEventAt = 0L
    private var lastErrorEventAt = 0L
    private var lastPermissionEventAt = 0L
    private var lastRecognizedAt = 0L
    private var lastRecognizedFingerprint = ""
    private var stitchedTranscriptBuffer = ""
    private var stitchedTranscriptLastAt = 0L

    override suspend fun start(): Boolean {
        if (workerJob?.isActive == true) return true

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        workerScope = scope
        workerJob = scope.launch {
            val sttMode = when {
                ENABLE_EXPERIMENTAL_LITERT_AUDIO_STT -> "litert_lm_audio_stt"
                ENABLE_MLKIT_GENAI_STT -> "mlkit_genai_file_stt"
                ENABLE_ANDROID_FILE_STT -> "android_file_stt"
                else -> "capture_only"
            }
            enqueueEvent(
                summary = "Audio worker started with $sttMode.",
                payload = mapOf(
                    "status" to "worker_started",
                    "mode" to "continuous_vad_stt",
                    "sttMode" to sttMode,
                    "probeWindowMs" to PROBE_WINDOW_MS,
                    "captureMaxMs" to CAPTURE_MAX_MS,
                )
            )
            continuousCaptureLoop()
        }
        return true
    }

    override suspend fun stop() {
        workerJob?.cancelAndJoin()
        workerScope?.cancel()
        workerJob = null
        workerScope = null
    }

    override suspend fun poll(): List<ContextEvent> {
        return drainPendingEvents(MAX_EVENTS_PER_POLL)
    }

    suspend fun reprocessExistingWavClip(wavPath: String): ReplayOutcome {
        val wavFile = File(wavPath)
        if (!wavFile.exists() || !wavFile.isFile) {
            return ReplayOutcome(
                status = "error",
                strategy = "replay",
                reason = "wav_not_found",
                modelStatus = "Missing wav: $wavPath",
                transcript = null,
                metaPath = null,
            )
        }

        val clip = clipFromExistingWav(wavFile)
        if (clip == null) {
            return ReplayOutcome(
                status = "error",
                strategy = "replay",
                reason = "wav_invalid",
                modelStatus = "Invalid wav format or empty clip: $wavPath",
                transcript = null,
                metaPath = null,
            )
        }

        val probe = AmbientProbe(
            rmsDb = clip.rmsDb,
            peakDb = clip.peakDb,
            voiceLikely = true,
            noisyLikely = false,
        )
        val transcription = when {
            ENABLE_ANDROID_FILE_STT -> transcribeWithAndroidRecognizer(clip)
            ENABLE_EXPERIMENTAL_LITERT_AUDIO_STT -> transcribeWithLiteRt(clip)
            ENABLE_MLKIT_GENAI_STT -> transcribeWithMlKitGenAi(clip, allowFallback = false)
            else -> TranscriptionResult.Skipped(
                reason = "all_audio_stt_disabled",
                strategy = "capture_only",
            )
        }

        return when (transcription) {
            is TranscriptionResult.Recognized -> {
                val stitchedTranscript = transcription.transcript
                val metaPath = persistClipMeta(
                    clip = clip,
                    probe = probe,
                    status = "recognized",
                    strategy = transcription.strategy,
                    transcript = transcription.transcript,
                    stitchedTranscript = stitchedTranscript,
                    reason = null,
                    modelStatus = transcription.modelStatus,
                )
                ReplayOutcome(
                    status = "recognized",
                    strategy = transcription.strategy,
                    reason = null,
                    modelStatus = transcription.modelStatus,
                    transcript = transcription.transcript,
                    metaPath = metaPath,
                )
            }
            is TranscriptionResult.NoSpeech -> {
                val metaPath = persistClipMeta(
                    clip = clip,
                    probe = probe,
                    status = "no_speech",
                    strategy = transcription.strategy,
                    transcript = null,
                    reason = "no_clear_speech",
                    modelStatus = null,
                )
                ReplayOutcome(
                    status = "no_speech",
                    strategy = transcription.strategy,
                    reason = "no_clear_speech",
                    modelStatus = "",
                    transcript = null,
                    metaPath = metaPath,
                )
            }
            is TranscriptionResult.Error -> {
                val metaPath = persistClipMeta(
                    clip = clip,
                    probe = probe,
                    status = "error",
                    strategy = transcription.strategy,
                    transcript = null,
                    reason = transcription.reason,
                    modelStatus = transcription.modelStatus,
                )
                ReplayOutcome(
                    status = "error",
                    strategy = transcription.strategy,
                    reason = transcription.reason,
                    modelStatus = transcription.modelStatus,
                    transcript = null,
                    metaPath = metaPath,
                )
            }
            is TranscriptionResult.Skipped -> {
                val metaPath = persistClipMeta(
                    clip = clip,
                    probe = probe,
                    status = "captured_untranscribed",
                    strategy = transcription.strategy,
                    transcript = null,
                    reason = transcription.reason,
                    modelStatus = null,
                )
                ReplayOutcome(
                    status = "captured_untranscribed",
                    strategy = transcription.strategy,
                    reason = transcription.reason,
                    modelStatus = "",
                    transcript = null,
                    metaPath = metaPath,
                )
            }
        }
    }

    private suspend fun continuousCaptureLoop() {
        while (workerJob?.isActive == true) {
            if (!hasMicrophonePermission()) {
                maybeEmitPermissionEvent()
                delay(NO_PERMISSION_RETRY_MS)
                continue
            }

            val probe = captureAmbientProbe()
            if (probe == null) {
                maybeEmitErrorEvent(
                    reason = "audio_probe_failed",
                    payload = emptyMap(),
                )
                delay(ERROR_RETRY_MS)
                continue
            }

            if (!probe.voiceLikely) {
                maybeEmitNoSpeechEvent(probe)
                delay(SILENT_PROBE_INTERVAL_MS)
                continue
            }

            val clip = recordSpeechClipToWav()
            if (clip == null) {
                maybeEmitErrorEvent(
                    reason = "audio_clip_capture_failed",
                    payload = mapOf(
                        "rmsDb" to probe.rmsDb,
                        "peakDb" to probe.peakDb,
                        "voiceLikely" to probe.voiceLikely,
                        "noisyLikely" to probe.noisyLikely,
                    ),
                )
                delay(ERROR_RETRY_MS)
                continue
            }

            val transcription = when {
                ENABLE_ANDROID_FILE_STT -> transcribeWithAndroidRecognizer(clip)
                ENABLE_EXPERIMENTAL_LITERT_AUDIO_STT -> transcribeWithLiteRt(clip)
                ENABLE_MLKIT_GENAI_STT -> transcribeWithMlKitGenAi(clip, allowFallback = true)
                else -> TranscriptionResult.Skipped(
                    reason = "all_audio_stt_disabled",
                    strategy = "capture_only",
                )
            }

            when (transcription) {
                is TranscriptionResult.Recognized -> {
                    val stitchedTranscript = buildStitchedTranscript(
                        transcript = transcription.transcript,
                        now = System.currentTimeMillis(),
                    )
                    val metaPath = persistClipMeta(
                        clip = clip,
                        probe = probe,
                        status = "recognized",
                        strategy = transcription.strategy,
                        transcript = transcription.transcript,
                        stitchedTranscript = stitchedTranscript,
                        reason = null,
                        modelStatus = transcription.modelStatus,
                    )
                    emitRecognizedEvent(
                        transcription = transcription,
                        stitchedTranscript = stitchedTranscript,
                        probe = probe,
                        clip = clip,
                        metaPath = metaPath,
                    )
                }
                is TranscriptionResult.NoSpeech -> {
                    val metaPath = persistClipMeta(
                        clip = clip,
                        probe = probe,
                        status = "no_speech",
                        strategy = transcription.strategy,
                        transcript = null,
                        reason = "no_clear_speech",
                        modelStatus = null,
                    )
                    if (probe.noisyLikely) {
                        enqueueEvent(
                            summary = "Ambient audio detected (likely outdoor/noisy), local STT found no clean transcript.",
                            payload = mapOf(
                                "status" to "noisy_no_transcript",
                                "strategy" to transcription.strategy,
                                "rmsDb" to probe.rmsDb,
                                "peakDb" to probe.peakDb,
                                "clipMs" to clip.durationMs,
                                "wavPath" to clip.file.absolutePath,
                                "metaPath" to metaPath.orEmpty(),
                            ),
                        )
                    } else {
                        maybeEmitNoSpeechEvent(
                            probe = probe,
                            strategy = transcription.strategy,
                            wavPath = clip.file.absolutePath,
                            metaPath = metaPath,
                            clipMs = clip.durationMs,
                        )
                    }
                }

                is TranscriptionResult.Error -> {
                    val metaPath = persistClipMeta(
                        clip = clip,
                        probe = probe,
                        status = "error",
                        strategy = transcription.strategy,
                        transcript = null,
                        reason = transcription.reason,
                        modelStatus = transcription.modelStatus,
                    )
                    maybeEmitErrorEvent(
                        reason = transcription.reason,
                        payload = mapOf(
                            "strategy" to transcription.strategy,
                            "modelStatus" to transcription.modelStatus,
                            "rmsDb" to probe.rmsDb,
                            "peakDb" to probe.peakDb,
                            "voiceLikely" to probe.voiceLikely,
                            "noisyLikely" to probe.noisyLikely,
                            "clipMs" to clip.durationMs,
                            "wavPath" to clip.file.absolutePath,
                            "metaPath" to metaPath.orEmpty(),
                        )
                    )
                }

                is TranscriptionResult.Skipped -> {
                    val metaPath = persistClipMeta(
                        clip = clip,
                        probe = probe,
                        status = "captured_untranscribed",
                        strategy = transcription.strategy,
                        transcript = null,
                        reason = transcription.reason,
                        modelStatus = null,
                    )
                    enqueueEvent(
                        summary = "Audio clip captured for debug (transcription disabled for stability).",
                        payload = mapOf(
                            "status" to "captured_untranscribed",
                            "strategy" to transcription.strategy,
                            "reason" to transcription.reason,
                            "rmsDb" to probe.rmsDb,
                            "peakDb" to probe.peakDb,
                            "clipMs" to clip.durationMs,
                            "wavPath" to clip.file.absolutePath,
                            "metaPath" to metaPath.orEmpty(),
                        ),
                    )
                }
            }

            enforceClipRetention()
            delay(AFTER_CYCLE_DELAY_MS)
        }
    }

    private fun emitRecognizedEvent(
        transcription: TranscriptionResult.Recognized,
        stitchedTranscript: String,
        probe: AmbientProbe,
        clip: AudioClip,
        metaPath: String?,
    ) {
        val now = System.currentTimeMillis()
        val normalized = transcription.transcript
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), " ")

        if (
            normalized.isNotBlank() &&
            normalized == lastRecognizedFingerprint &&
            now - lastRecognizedAt < RECOGNIZED_DEDUP_MS
        ) {
            return
        }

        lastRecognizedAt = now
        lastRecognizedFingerprint = normalized

        enqueueEvent(
            summary = "Ambient speech transcript: $stitchedTranscript",
            payload = mapOf(
                "status" to "recognized",
                "transcript" to transcription.transcript,
                "stitchedTranscript" to stitchedTranscript,
                "strategy" to transcription.strategy,
                "modelStatus" to transcription.modelStatus,
                "rmsDb" to probe.rmsDb,
                "peakDb" to probe.peakDb,
                "clipRmsDb" to clip.rmsDb,
                "clipPeakDb" to clip.peakDb,
                "clipMs" to clip.durationMs,
                "voiceLikely" to probe.voiceLikely,
                "noisyLikely" to probe.noisyLikely,
                "wavPath" to clip.file.absolutePath,
                "metaPath" to metaPath.orEmpty(),
            )
        )
    }

    private fun buildStitchedTranscript(transcript: String, now: Long): String {
        val clean = transcript.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return transcript

        val gapMs = if (stitchedTranscriptLastAt > 0L) now - stitchedTranscriptLastAt else Long.MAX_VALUE
        val shouldReset =
            stitchedTranscriptBuffer.isBlank() ||
                gapMs > STITCH_GAP_RESET_MS ||
                endsWithSentencePunctuation(stitchedTranscriptBuffer)

        stitchedTranscriptBuffer = if (shouldReset) {
            clean
        } else {
            mergeTranscriptSegments(stitchedTranscriptBuffer, clean)
        }

        if (stitchedTranscriptBuffer.length > MAX_STITCHED_TRANSCRIPT_CHARS) {
            stitchedTranscriptBuffer = stitchedTranscriptBuffer.takeLast(MAX_STITCHED_TRANSCRIPT_CHARS).trim()
        }
        stitchedTranscriptLastAt = now
        return stitchedTranscriptBuffer
    }

    private fun mergeTranscriptSegments(previous: String, next: String): String {
        if (next.isBlank()) return previous
        if (previous.isBlank()) return next

        val prev = previous.trim()
        val nxt = next.trim()
        val prevLower = prev.lowercase(Locale.US)
        val nextLower = nxt.lowercase(Locale.US)

        if (prevLower == nextLower) return prev
        if (nextLower.startsWith(prevLower)) return nxt
        if (prevLower.endsWith(nextLower)) return prev

        val maxOverlap = minOf(prev.length, nxt.length, 96)
        for (size in maxOverlap downTo 8) {
            val left = prevLower.takeLast(size)
            val right = nextLower.take(size)
            if (left == right) {
                return (prev + nxt.drop(size)).trim()
            }
        }
        return "$prev $nxt".replace(Regex("\\s+"), " ").trim()
    }

    private fun pickBetterSuccess(
        current: AndroidSpeechOutcome.Success?,
        candidate: AndroidSpeechOutcome.Success,
    ): AndroidSpeechOutcome.Success {
        if (current == null) return candidate
        val currentScore = transcriptQualityScore(current.text)
        val candidateScore = transcriptQualityScore(candidate.text)
        return if (candidateScore >= currentScore) candidate else current
    }

    private fun transcriptQualityScore(text: String): Int {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return Int.MIN_VALUE / 4
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return Int.MIN_VALUE / 4

        val total = tokens.size
        val unique = tokens.map { it.lowercase(Locale.US) }.toSet().size
        val repeatPenalty = (total - unique).coerceAtLeast(0) * 2
        val punctuationBonus = if (endsWithSentencePunctuation(cleaned)) 8 else 0
        val lengthScore = minOf(total, 48) * 2
        val shortPenalty = if (total < 4) 8 else 0
        return lengthScore + punctuationBonus - repeatPenalty - shortPenalty
    }

    private fun endsWithSentencePunctuation(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isBlank()) return false
        return when (trimmed.last()) {
            '.', '!', '?', '。', '！', '？' -> true
            else -> false
        }
    }

    private fun transcribeWithLiteRt(clip: AudioClip): TranscriptionResult {
        val profile = EdgeModelProfile.fromId(AppPrefs.getEdgeModel(context))
        val config = localRuntimeConfig()
        if (!config.enabled || config.backend != LocalModelBackend.LITERT_LM) {
            return TranscriptionResult.Error(
                reason = "local_litert_model_disabled",
                strategy = "local_litert_stt",
                modelStatus = "Local LiteRT-LM is disabled in config.",
            )
        }

        val prompt = """
            You are an on-device ASR engine.
            Transcribe the spoken audio exactly.
            Rules:
            - Return plain transcript text only.
            - No explanation.
            - If there is no clear speech, return exactly: <no-speech>
        """.trimIndent()

        val result = LiteRtLmRuntime.getInstance(context).transcribeAudio(
            profile = profile,
            audioFilePath = clip.file.absolutePath,
            prompt = prompt,
            config = config,
        )

        if (!result.usedNativeModel) {
            return TranscriptionResult.Error(
                reason = "local_stt_inference_failed",
                strategy = "local_litert_stt",
                modelStatus = result.message,
            )
        }

        val raw = result.output.orEmpty().trim()
        if (raw.isBlank()) {
            return TranscriptionResult.NoSpeech(
                strategy = "local_litert_stt_empty",
            )
        }

        val transcript = normalizeTranscription(raw)
        if (transcript == null) {
            return TranscriptionResult.NoSpeech(
                strategy = "local_litert_stt_no_speech",
            )
        }

        return TranscriptionResult.Recognized(
            transcript = transcript,
            strategy = "local_litert_stt",
            modelStatus = result.message,
        )
    }

    private suspend fun transcribeWithMlKitGenAi(
        clip: AudioClip,
        allowFallback: Boolean,
    ): TranscriptionResult {
        val strategy = "mlkit_genai_file_stt"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return if (allowFallback && ENABLE_ANDROID_FILE_STT && ENABLE_ANDROID_STT_FALLBACK_AFTER_MLKIT) {
                val fallback = transcribeWithAndroidRecognizer(clip)
                attachMlKitFallbackMetadata(
                    fallback = fallback,
                    mlKitErrors = listOf("api_below_31"),
                )
            } else {
                TranscriptionResult.Error(
                    reason = "mlkit_requires_api_31",
                    strategy = strategy,
                    modelStatus = "ML Kit GenAI speech requires Android 12/API 31+.",
                )
            }
        }

        val attempts = buildMlKitAttempts()

        val mlKitErrors = mutableListOf<String>()
        var noSpeechResult: TranscriptionResult.NoSpeech? = null

        for ((index, attempt) in attempts.withIndex()) {
            Log.i(
                LOG_TAG,
                "ML Kit attempt ${index + 1}/${attempts.size}: mode=${attempt.label}, locale=${attempt.localeLabel}",
            )
            val result = transcribeWithMlKitMode(
                wavFile = clip.file,
                locale = attempt.locale,
                mode = attempt.mode,
                modeLabel = attempt.label,
                localeLabel = attempt.localeLabel,
            )

            when (result) {
                is TranscriptionResult.Recognized -> return result
                is TranscriptionResult.NoSpeech -> {
                    noSpeechResult = result
                    if (index == attempts.lastIndex) {
                        return result
                    }
                }
                is TranscriptionResult.Error -> {
                    val compactStatus = result.modelStatus.replace("\n", " ").take(180)
                    mlKitErrors += "${attempt.label}/${attempt.localeLabel}:${result.reason}[${compactStatus}]"
                    Log.w(
                        LOG_TAG,
                        "ML Kit attempt failed: mode=${attempt.label}, locale=${attempt.localeLabel}, reason=${result.reason}, status=${result.modelStatus}",
                    )
                    val isLastAttempt = index == attempts.lastIndex
                    if (isLastAttempt) break
                    if (shouldRetryMlKitMode(result.reason)) {
                        delay(RETRY_ATTEMPT_DELAY_MS)
                    }
                }
                is TranscriptionResult.Skipped -> {
                    mlKitErrors += "${attempt.label}/${attempt.localeLabel}:${result.reason}"
                }
            }
        }

        if (noSpeechResult != null) {
            return noSpeechResult
        }

        if (allowFallback && ENABLE_ANDROID_FILE_STT && ENABLE_ANDROID_STT_FALLBACK_AFTER_MLKIT) {
            val fallback = transcribeWithAndroidRecognizer(clip)
            return attachMlKitFallbackMetadata(
                fallback = fallback,
                mlKitErrors = mlKitErrors,
            )
        }

        return TranscriptionResult.Error(
            reason = "mlkit_stt_failed",
            strategy = strategy,
            modelStatus = if (mlKitErrors.isEmpty()) {
                "ML Kit GenAI returned no usable result."
            } else {
                mlKitErrors.joinToString(",")
            },
        )
    }

    private suspend fun transcribeWithMlKitMode(
        wavFile: File,
        locale: Locale?,
        mode: Int,
        modeLabel: String,
        localeLabel: String,
    ): TranscriptionResult {
        val strategy = "mlkit_genai_file_stt_${modeLabel}_${localeLabel.replace('-', '_')}"
        val options = kotlin.runCatching {
            MlKitSpeechRecognizerOptions.builder().apply {
                if (locale != null) {
                    this.locale = locale
                }
                preferredMode = mode
            }.build()
        }.getOrElse {
            return TranscriptionResult.Error(
                reason = "mlkit_options_build_failed",
                strategy = strategy,
                modelStatus = "Failed to build ML Kit options: ${it.message.orEmpty()}",
            )
        }

        val recognizer = kotlin.runCatching {
            MlKitSpeechRecognition.getClient(options)
        }.getOrElse {
            return TranscriptionResult.Error(
                reason = "mlkit_client_create_failed",
                strategy = strategy,
                modelStatus = "Failed to create ML Kit speech client: ${it.message.orEmpty()}",
            )
        }

        return try {
            ensureMlKitModelReady(recognizer, modeLabel, mode)?.let { error ->
                return TranscriptionResult.Error(
                    reason = error.reason,
                    strategy = strategy,
                    modelStatus = error.detail,
                )
            }

            var outcome = runMlKitRecognitionOnce(
                recognizer = recognizer,
                audioFile = wavFile,
                modeLabel = modeLabel,
                localeLabel = localeLabel,
                inputLabel = "wav",
            )
            if (outcome is MlKitRecognitionOutcome.Error && shouldRetryMlKitWithPcm(outcome.reason)) {
                val pcmFile = writeTempPcmFromWav(wavFile)
                if (pcmFile != null) {
                    val pcmOutcome = runMlKitRecognitionOnce(
                        recognizer = recognizer,
                        audioFile = pcmFile,
                        modeLabel = modeLabel,
                        localeLabel = localeLabel,
                        inputLabel = "pcm",
                    )
                    kotlin.runCatching { pcmFile.delete() }
                    if (pcmOutcome !is MlKitRecognitionOutcome.Error) {
                        outcome = pcmOutcome
                    } else {
                        outcome = MlKitRecognitionOutcome.Error(
                            reason = pcmOutcome.reason,
                            detail = "${pcmOutcome.detail} | wavAttempt=${outcome.detail}",
                        )
                    }
                }
            }

            when (outcome) {
                is MlKitRecognitionOutcome.Recognized -> {
                    val transcript = normalizeTranscription(outcome.text)
                    if (transcript == null) {
                        TranscriptionResult.NoSpeech(strategy = "${strategy}_no_speech")
                    } else {
                        TranscriptionResult.Recognized(
                            transcript = transcript,
                            strategy = strategy,
                            modelStatus = outcome.engine,
                        )
                    }
                }
                is MlKitRecognitionOutcome.NoSpeech -> {
                    TranscriptionResult.NoSpeech(strategy = "${strategy}_no_speech")
                }
                is MlKitRecognitionOutcome.Error -> {
                    TranscriptionResult.Error(
                        reason = outcome.reason,
                        strategy = strategy,
                        modelStatus = outcome.detail,
                    )
                }
            }
        } catch (e: GenAiException) {
            val mapped = mapMlKitException(e)
            TranscriptionResult.Error(
                reason = mapped.reason,
                strategy = strategy,
                modelStatus = mapped.detail,
            )
        } catch (t: Throwable) {
            TranscriptionResult.Error(
                reason = "mlkit_runtime_exception",
                strategy = strategy,
                modelStatus = "ML Kit runtime exception: ${t.message.orEmpty()}",
            )
        } finally {
            kotlin.runCatching { recognizer.close() }
        }
    }

    private suspend fun runMlKitRecognitionOnce(
        recognizer: MlKitSpeechRecognizer,
        audioFile: File,
        modeLabel: String,
        localeLabel: String,
        inputLabel: String,
    ): MlKitRecognitionOutcome {
        val audioPfd = kotlin.runCatching {
            ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrElse {
            return MlKitRecognitionOutcome.Error(
                reason = "mlkit_audio_open_failed",
                detail = "Cannot open $inputLabel file: ${it.message.orEmpty()}",
            )
        }

        return try {
            val request = MlKitSpeechRecognizerRequest.builder().apply {
                audioSource = MlKitAudioSource.fromPfd(audioPfd)
            }.build()

            withTimeoutOrNull(FILE_STT_TIMEOUT_MS) {
                collectMlKitTranscript(
                    recognizer = recognizer,
                    request = request,
                    modeLabel = modeLabel,
                    localeLabel = localeLabel,
                    inputLabel = inputLabel,
                )
            } ?: MlKitRecognitionOutcome.Error(
                reason = "mlkit_stt_timeout",
                detail = "ML Kit transcription timed out after ${FILE_STT_TIMEOUT_MS}ms in $modeLabel/$localeLabel with $inputLabel.",
            )
        } finally {
            kotlin.runCatching { audioPfd.close() }
        }
    }

    private suspend fun ensureMlKitModelReady(
        recognizer: MlKitSpeechRecognizer,
        modeLabel: String,
        mode: Int,
    ): MlKitRecognitionOutcome.Error? {
        val status = recognizer.checkStatus()
        val env = collectMlKitEnvDiagnostics()
        Log.i(LOG_TAG, "ML Kit checkStatus mode=$modeLabel status=$status")
        when (status) {
            FeatureStatus.AVAILABLE -> return null
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING,
            -> {
                var downloadFailed: MlKitRecognitionOutcome.Error? = null
                val completed = withTimeoutOrNull(MLKIT_DOWNLOAD_TIMEOUT_MS) {
                    recognizer.download().collect { downloadStatus ->
                        when (downloadStatus) {
                            DownloadStatus.DownloadCompleted -> Unit
                            is DownloadStatus.DownloadFailed -> {
                                Log.w(LOG_TAG, "ML Kit download failed mode=$modeLabel code=${downloadStatus.e.errorCode} msg=${downloadStatus.e.message}")
                                downloadFailed = mapMlKitException(downloadStatus.e)
                            }
                            is DownloadStatus.DownloadProgress -> {
                                Log.i(LOG_TAG, "ML Kit downloading mode=$modeLabel bytes=${downloadStatus.totalBytesDownloaded}")
                            }
                            is DownloadStatus.DownloadStarted -> {
                                Log.i(LOG_TAG, "ML Kit download started mode=$modeLabel")
                            }
                        }
                    }
                    true
                } ?: false

                if (!completed) {
                    Log.w(LOG_TAG, "ML Kit download timeout mode=$modeLabel")
                    return MlKitRecognitionOutcome.Error(
                        reason = "mlkit_download_timeout",
                        detail = "ML Kit model download timed out after ${MLKIT_DOWNLOAD_TIMEOUT_MS}ms in $modeLabel mode. | $env",
                    )
                }
                if (downloadFailed != null) {
                    return downloadFailed
                }
                val postStatus = recognizer.checkStatus()
                Log.i(LOG_TAG, "ML Kit post-download status mode=$modeLabel status=$postStatus")
                if (postStatus != FeatureStatus.AVAILABLE) {
                    return MlKitRecognitionOutcome.Error(
                        reason = "mlkit_model_not_ready",
                        detail = "ML Kit model status after download is $postStatus in $modeLabel mode. | $env",
                    )
                }
                return null
            }
            FeatureStatus.UNAVAILABLE -> {
                if (mode == MlKitSpeechRecognizerOptions.Mode.MODE_BASIC) {
                    Log.w(LOG_TAG, "ML Kit checkStatus UNAVAILABLE in basic mode, trying startRecognition anyway.")
                    return null
                }
                Log.w(LOG_TAG, "ML Kit unavailable mode=$modeLabel on this device")
                return MlKitRecognitionOutcome.Error(
                    reason = "mlkit_feature_unavailable",
                    detail = "ML Kit speech is unavailable for $modeLabel mode on this device. | $env",
                )
            }
            else -> {
                return MlKitRecognitionOutcome.Error(
                    reason = "mlkit_unknown_status_$status",
                    detail = "Unexpected ML Kit feature status=$status in $modeLabel mode. | $env",
                )
            }
        }
    }

    private suspend fun collectMlKitTranscript(
        recognizer: MlKitSpeechRecognizer,
        request: MlKitSpeechRecognizerRequest,
        modeLabel: String,
        localeLabel: String,
        inputLabel: String,
    ): MlKitRecognitionOutcome {
        var finalText = ""
        var partialText = ""
        var error: MlKitRecognitionOutcome.Error? = null

        recognizer.startRecognition(request).collect { response ->
            when (response) {
                is MlKitSpeechRecognizerResponse.FinalTextResponse -> {
                    val text = response.text.trim()
                    if (text.isNotBlank()) {
                        finalText = text
                    }
                }
                is MlKitSpeechRecognizerResponse.PartialTextResponse -> {
                    val text = response.text.trim()
                    if (text.isNotBlank()) {
                        partialText = text
                    }
                }
                is MlKitSpeechRecognizerResponse.ErrorResponse -> {
                    error = mapMlKitException(response.e)
                }
                MlKitSpeechRecognizerResponse.CompletedResponse -> Unit
            }
        }

        if (error != null) {
            return error!!
        }

        val text = finalText.ifBlank { partialText }.trim()
        if (text.isBlank()) {
            return MlKitRecognitionOutcome.NoSpeech(
                reason = "mlkit_no_text_response",
            )
        }

        return MlKitRecognitionOutcome.Recognized(
            text = text,
            engine = "ML Kit GenAI Speech ($modeLabel mode, locale=$localeLabel, input=$inputLabel)",
        )
    }

    private fun mapMlKitException(exception: GenAiException): MlKitRecognitionOutcome.Error {
        val reason = when (exception.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> "mlkit_background_use_blocked"
            GenAiException.ErrorCode.BUSY -> "mlkit_busy"
            GenAiException.ErrorCode.CANCELLED -> "mlkit_cancelled"
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> "mlkit_needs_system_update"
            GenAiException.ErrorCode.NOT_AVAILABLE -> "mlkit_not_available"
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> "mlkit_not_enough_disk_space"
            GenAiException.ErrorCode.REQUEST_TOO_SMALL -> "mlkit_request_too_small"
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> "mlkit_request_too_large"
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> "mlkit_request_processing_error"
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> "mlkit_response_generation_error"
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR -> "mlkit_response_processing_error"
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> "mlkit_aicore_incompatible"
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> "mlkit_battery_quota_exceeded"
            GenAiException.ErrorCode.CACHE_PROCESSING_ERROR -> "mlkit_cache_processing_error"
            GenAiException.ErrorCode.INVALID_INPUT_IMAGE -> "mlkit_invalid_input"
            else -> "mlkit_error_${exception.errorCode}"
        }
        val env = collectMlKitEnvDiagnostics()
        return MlKitRecognitionOutcome.Error(
            reason = reason,
            detail = "GenAiException(code=${exception.errorCode}): ${exception.message.orEmpty()} | $env",
        )
    }

    private fun collectMlKitEnvDiagnostics(): String {
        val sdk = Build.VERSION.SDK_INT
        val locale = Locale.getDefault().toLanguageTag().ifBlank { "unknown" }
        val aicore = packageVersionLabel("com.google.android.aicore")
        val gms = packageVersionLabel("com.google.android.gms")
        return "sdk=$sdk locale=$locale aicore=$aicore gms=$gms"
    }

    private fun packageVersionLabel(packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val version = info.versionName?.takeIf { it.isNotBlank() } ?: "unknown"
            "installed:$version"
        } catch (_: Throwable) {
            "missing"
        }
    }

    private fun shouldRetryMlKitMode(reason: String): Boolean {
        return when (reason) {
            "mlkit_busy",
            "mlkit_cancelled",
            "mlkit_response_generation_error",
            "mlkit_response_processing_error",
            "mlkit_request_processing_error",
            "mlkit_stt_timeout",
            "mlkit_model_not_ready",
            -> true
            else -> false
        }
    }

    private fun shouldRetryMlKitWithPcm(reason: String): Boolean {
        return when (reason) {
            "mlkit_request_processing_error",
            "mlkit_response_processing_error",
            "mlkit_request_too_small",
            "mlkit_request_too_large",
            "mlkit_invalid_input",
            "mlkit_not_available",
            "mlkit_runtime_exception",
            -> true
            else -> false
        }
    }

    private fun buildMlKitAttempts(): List<MlKitModeAttempt> {
        val defaultLocale = Locale.getDefault()
        val zhCn = Locale.forLanguageTag("zh-CN")
        val localeCandidates = linkedSetOf<Locale?>(
            defaultLocale,
            Locale.US,
            zhCn,
            null, // Let engine decide when locale routing is unstable.
        )

        val attempts = mutableListOf<MlKitModeAttempt>()
        localeCandidates.forEach { locale ->
            val localeLabel = locale?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "auto"
            attempts += MlKitModeAttempt(
                mode = MlKitSpeechRecognizerOptions.Mode.MODE_BASIC,
                label = "basic",
                locale = locale,
                localeLabel = localeLabel,
            )
        }
        return attempts
    }

    private fun attachMlKitFallbackMetadata(
        fallback: TranscriptionResult,
        mlKitErrors: List<String>,
    ): TranscriptionResult {
        val extra = buildString {
            append("ML Kit GenAI failed, fallback to Android SpeechRecognizer.")
            if (mlKitErrors.isNotEmpty()) {
                append(" attempts=")
                append(mlKitErrors.joinToString(","))
            }
        }
        return when (fallback) {
            is TranscriptionResult.Recognized -> fallback.copy(
                strategy = "mlkit_genai_fallback_${fallback.strategy}",
                modelStatus = mergeStatus(fallback.modelStatus, extra),
            )
            is TranscriptionResult.NoSpeech -> fallback.copy(
                strategy = "mlkit_genai_fallback_${fallback.strategy}",
            )
            is TranscriptionResult.Error -> fallback.copy(
                reason = if (
                    mlKitErrors.isNotEmpty() &&
                    mlKitErrors.all { it.contains("mlkit_feature_unavailable") }
                ) {
                    "mlkit_unavailable_fallback_failed"
                } else {
                    fallback.reason
                },
                strategy = "mlkit_genai_fallback_${fallback.strategy}",
                modelStatus = mergeStatus(fallback.modelStatus, extra),
            )
            is TranscriptionResult.Skipped -> fallback.copy(
                strategy = "mlkit_genai_fallback_${fallback.strategy}",
            )
        }
    }

    private fun mergeStatus(base: String?, extra: String?): String {
        val a = base?.trim().orEmpty()
        val b = extra?.trim().orEmpty()
        return when {
            a.isBlank() && b.isBlank() -> ""
            a.isBlank() -> b
            b.isBlank() -> a
            else -> "$a | $b"
        }
    }

    private suspend fun transcribeWithAndroidRecognizer(clip: AudioClip): TranscriptionResult {
        val strategy = "android_file_stt"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return TranscriptionResult.Error(
                reason = "android_file_stt_requires_api_33",
                strategy = strategy,
                modelStatus = "Device API ${Build.VERSION.SDK_INT} is below 33.",
            )
        }

        if (!hasMicrophonePermission()) {
            return TranscriptionResult.Error(
                reason = "speech_permission_missing",
                strategy = strategy,
                modelStatus = "RECORD_AUDIO is required for system speech recognition.",
            )
        }

        val defaultLanguageTag = Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() }
        val normalizedDefaultLang = defaultLanguageTag?.lowercase(Locale.US)
        val defaultIsChinese =
            normalizedDefaultLang?.startsWith("zh") == true ||
                normalizedDefaultLang?.startsWith("cmn") == true
        val languageCandidates = buildList<String?> {
            if (!defaultLanguageTag.isNullOrBlank()) add(defaultLanguageTag)
            add(null) // Do not assume locale support; allow auto routing.
            if (defaultIsChinese) {
                if (normalizedDefaultLang != "zh-cn") add("zh-CN")
                if (normalizedDefaultLang != "cmn-hans-cn") add("cmn-Hans-CN")
                if (normalizedDefaultLang != "zh-tw") add("zh-TW")
                if (normalizedDefaultLang != "en-us") add("en-US")
            } else {
                if (normalizedDefaultLang != "en-us") add("en-US")
                if (normalizedDefaultLang != "zh-cn") add("zh-CN")
                if (normalizedDefaultLang != "cmn-hans-cn") add("cmn-Hans-CN")
                if (normalizedDefaultLang != "zh-tw") add("zh-TW")
            }
        }.distinct()

        val engineSequence = if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            listOf(true, false) // Layer 1: on-device, then layer 2: default service.
        } else {
            listOf(false)
        }
        val attempts = mutableListOf<SttAttempt>()
        engineSequence.forEach { useOnDevice ->
            val prefix = if (useOnDevice) "on_device" else "default_service"
            languageCandidates.forEach { lang ->
                val langLabel = lang?.replace('-', '_') ?: "auto"
                attempts += SttAttempt(
                    useOnDevice = useOnDevice,
                    preferOffline = useOnDevice,
                    languageTag = lang,
                    useSegmentedSession = false,
                    name = "${prefix}_$langLabel",
                )
            }
        }

        val pcmFile = writeTempPcmFromWav(clip.file)
        if (pcmFile == null) {
            return TranscriptionResult.Error(
                reason = "pcm_prepare_failed",
                strategy = strategy,
                modelStatus = "Failed to prepare PCM stream from wav clip.",
            )
        }

        val attemptErrors = mutableListOf<String>()
        val finalOutcome = try {
            var successOutcome: AndroidSpeechOutcome.Success? = null
            var bestOnDeviceSuccess: AndroidSpeechOutcome.Success? = null
            var bestPartialOutcome: AndroidSpeechOutcome.Partial? = null
            var sawNoMatch = false
            var lastErrorOutcome: AndroidSpeechOutcome.Error? = null
            for ((index, attempt) in attempts.withIndex()) {
                val outcome = withContext(Dispatchers.Main) {
                    transcribePcmWithSpeechRecognizer(
                        pcmFile = pcmFile,
                        attempt = attempt,
                    )
                }
                when (outcome) {
                    is AndroidSpeechOutcome.Success -> {
                        if (attempt.useOnDevice) {
                            bestOnDeviceSuccess = pickBetterSuccess(bestOnDeviceSuccess, outcome)
                            val hasDefaultRemaining = attempts
                                .subList(index + 1, attempts.size)
                                .any { !it.useOnDevice }
                            if (hasDefaultRemaining) {
                                continue
                            }
                            successOutcome = bestOnDeviceSuccess
                            break
                        } else {
                            successOutcome = pickBetterSuccess(bestOnDeviceSuccess, outcome)
                            break
                        }
                    }
                    is AndroidSpeechOutcome.Partial -> {
                        val currentBest = bestPartialOutcome
                        if (currentBest == null || outcome.text.length > currentBest.text.length) {
                            bestPartialOutcome = outcome
                        }
                        attemptErrors += "${attempt.name}:partial(${outcome.reason})"
                        if (index != attempts.lastIndex) {
                            delay(RETRY_ATTEMPT_DELAY_MS)
                        }
                    }
                    is AndroidSpeechOutcome.NoMatch -> {
                        sawNoMatch = true
                        attemptErrors += "${attempt.name}:${outcome.reason}"
                        if (index != attempts.lastIndex) {
                            delay(RETRY_ATTEMPT_DELAY_MS)
                        }
                    }
                    is AndroidSpeechOutcome.Error -> {
                        lastErrorOutcome = outcome
                        attemptErrors += "${attempt.name}:${outcome.reason}"
                        if (!shouldRetrySttAttempt(outcome.reason) || index == attempts.lastIndex) {
                            break
                        }
                        delay(RETRY_ATTEMPT_DELAY_MS)
                    }
                }
            }
            when {
                successOutcome != null -> successOutcome
                bestOnDeviceSuccess != null -> bestOnDeviceSuccess
                bestPartialOutcome != null -> AndroidSpeechOutcome.Success(
                    text = bestPartialOutcome.text,
                    engine = "${bestPartialOutcome.engine}, partial-fallback",
                )
                sawNoMatch -> AndroidSpeechOutcome.NoMatch("speech_no_match")
                lastErrorOutcome != null -> lastErrorOutcome
                else -> AndroidSpeechOutcome.Error(
                    reason = "speech_unknown",
                    detail = "No STT attempt executed.",
                )
            }
        } finally {
            kotlin.runCatching { pcmFile.delete() }
        }

        return when (finalOutcome) {
            is AndroidSpeechOutcome.Success -> {
                val transcript = normalizeTranscription(finalOutcome.text)
                if (transcript == null) {
                    TranscriptionResult.NoSpeech(strategy = "${strategy}_no_speech")
                } else {
                    TranscriptionResult.Recognized(
                        transcript = transcript,
                        strategy = strategy,
                        modelStatus = finalOutcome.engine,
                    )
                }
            }
            is AndroidSpeechOutcome.Partial -> {
                val transcript = normalizeTranscription(finalOutcome.text)
                if (transcript == null) {
                    TranscriptionResult.NoSpeech(strategy = "${strategy}_partial_no_speech")
                } else {
                    TranscriptionResult.Recognized(
                        transcript = transcript,
                        strategy = "${strategy}_partial",
                        modelStatus = "${finalOutcome.engine}, reason=${finalOutcome.reason}",
                    )
                }
            }
            is AndroidSpeechOutcome.NoMatch -> {
                TranscriptionResult.NoSpeech(strategy = "${strategy}_no_match")
            }
            is AndroidSpeechOutcome.Error -> {
                val androidError = TranscriptionResult.Error(
                    reason = finalOutcome.reason,
                    strategy = strategy,
                    modelStatus = buildString {
                        append(finalOutcome.detail)
                        if (attemptErrors.isNotEmpty()) {
                            append(" | attempts=")
                            append(attemptErrors.joinToString(","))
                        }
                    },
                )
                if (!isLocalAsrReadyForFallback()) {
                    return androidError.copy(
                        modelStatus = mergeStatus(
                            androidError.modelStatus,
                            "local_asr_fallback=skipped_not_configured",
                        ),
                    )
                }
                // Layer 3: local ASR engine fallback.
                val localFallback = kotlin.runCatching { transcribeWithLiteRt(clip) }.getOrElse {
                    TranscriptionResult.Error(
                        reason = "local_fallback_exception",
                        strategy = "local_litert_stt",
                        modelStatus = "Local fallback crashed: ${it.message.orEmpty()}",
                    )
                }
                when (localFallback) {
                    is TranscriptionResult.Recognized -> {
                        localFallback.copy(
                            strategy = "android_fallback_${localFallback.strategy}",
                            modelStatus = mergeStatus(
                                localFallback.modelStatus,
                                "android_failed=${androidError.reason}",
                            ),
                        )
                    }
                    is TranscriptionResult.NoSpeech -> {
                        localFallback.copy(
                            strategy = "android_fallback_${localFallback.strategy}",
                        )
                    }
                    is TranscriptionResult.Error -> {
                        androidError.copy(
                            reason = "android_and_local_failed",
                            strategy = "${strategy}_and_local",
                            modelStatus = mergeStatus(
                                androidError.modelStatus,
                                "local_failed=${localFallback.reason}:${localFallback.modelStatus}",
                            ),
                        )
                    }
                    is TranscriptionResult.Skipped -> androidError
                }
            }
        }
    }

    private fun isLocalAsrReadyForFallback(): Boolean {
        val config = localRuntimeConfig()
        return config.enabled && config.backend == LocalModelBackend.LITERT_LM
    }

    private fun writeTempPcmFromWav(wavFile: File): File? {
        val cacheDir = File(context.cacheDir, "audio_stt").apply { mkdirs() }
        val pcmFile = File(cacheDir, "${wavFile.nameWithoutExtension}.pcm")
        return kotlin.runCatching {
            FileInputStream(wavFile).use { input ->
                FileOutputStream(pcmFile).use { output ->
                    var toSkip = WAV_HEADER_BYTES
                    while (toSkip > 0) {
                        val skipped = input.skip(toSkip.toLong()).toInt()
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                    input.copyTo(output)
                    output.flush()
                }
            }
            pcmFile
        }.getOrNull()
    }

    private fun clipFromExistingWav(wavFile: File): AudioClip? {
        if (!wavFile.exists() || !wavFile.isFile) return null
        val length = wavFile.length()
        if (length <= WAV_HEADER_BYTES) return null
        val dataBytes = length - WAV_HEADER_BYTES
        val totalSamples = dataBytes / 2L
        if (totalSamples <= 0L) return null
        val durationMs = (totalSamples * 1000L) / SAMPLE_RATE
        if (durationMs <= 0L) return null
        return AudioClip(
            file = wavFile,
            durationMs = durationMs,
            rmsDb = -120f,
            peakDb = -120f,
        )
    }

    private suspend fun transcribePcmWithSpeechRecognizer(
        pcmFile: File,
        attempt: SttAttempt,
    ): AndroidSpeechOutcome {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return AndroidSpeechOutcome.Error(
                reason = "speech_recognizer_unavailable",
                detail = "No SpeechRecognizer service available on device.",
            )
        }

        val useOnDevice = attempt.useOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val recognizer = kotlin.runCatching {
            if (useOnDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrElse {
            return AndroidSpeechOutcome.Error(
                reason = "speech_recognizer_create_failed",
                detail = "SpeechRecognizer create failed: ${it.message.orEmpty()}",
            )
        }

        val audioPfd = kotlin.runCatching {
            ParcelFileDescriptor.open(pcmFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrElse {
            kotlin.runCatching { recognizer.destroy() }
            return AndroidSpeechOutcome.Error(
                reason = "speech_audio_open_failed",
                detail = "Failed to open PCM for recognizer: ${it.message.orEmpty()}",
            )
        }

        val result = withTimeoutOrNull(FILE_STT_TIMEOUT_MS) {
            suspendCancellableCoroutine<AndroidSpeechOutcome> { continuation ->
                var finished = false
                var partialText = ""

                fun finish(outcome: AndroidSpeechOutcome) {
                    if (finished) return
                    finished = true
                    if (continuation.isActive) {
                        continuation.resume(outcome)
                    }
                }

                continuation.invokeOnCancellation {
                    if (finished) return@invokeOnCancellation
                    finished = true
                    kotlin.runCatching { recognizer.cancel() }
                    kotlin.runCatching { recognizer.destroy() }
                    kotlin.runCatching { audioPfd.close() }
                }

                val engineLabel = recognizerLabel(
                    useOnDevice = useOnDevice,
                    preferOffline = attempt.preferOffline,
                    languageTag = attempt.languageTag,
                    segmentedSession = attempt.useSegmentedSession,
                )
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        val reason = speechErrorReason(error)
                        val errorName = speechErrorName(error)
                        if (
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ) {
                            if (partialText.isNotBlank()) {
                                finish(
                                    AndroidSpeechOutcome.Partial(
                                        text = partialText,
                                        engine = engineLabel,
                                        reason = reason,
                                    )
                                )
                            } else {
                                finish(AndroidSpeechOutcome.NoMatch(reason))
                            }
                        } else {
                            finish(
                                AndroidSpeechOutcome.Error(
                                    reason = reason,
                                    detail = "SpeechRecognizer error=$error($errorName) [$engineLabel]",
                                )
                            )
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val list = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        val text = list.firstOrNull().orEmpty().trim()
                        if (text.isNotBlank()) {
                            finish(AndroidSpeechOutcome.Success(text, engineLabel))
                        } else if (partialText.isNotBlank()) {
                            finish(
                                AndroidSpeechOutcome.Partial(
                                    text = partialText,
                                    engine = engineLabel,
                                    reason = "speech_partial_results_only",
                                )
                            )
                        } else {
                            finish(AndroidSpeechOutcome.NoMatch("speech_empty_results"))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                            .trim()
                        if (text.isNotBlank()) {
                            partialText = text
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt.preferOffline)
                    attempt.languageTag?.let { lang ->
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    }
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioPfd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    if (attempt.useSegmentedSession) {
                        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    }
                }

                kotlin.runCatching {
                    recognizer.startListening(intent)
                }.onFailure {
                    finish(
                        AndroidSpeechOutcome.Error(
                            reason = "speech_start_failed",
                            detail = "SpeechRecognizer start failed: ${it.message.orEmpty()}",
                        )
                    )
                }
            }
        } ?: AndroidSpeechOutcome.Error(
            reason = "speech_stt_timeout",
            detail = "SpeechRecognizer timed out after ${FILE_STT_TIMEOUT_MS}ms",
        )

        kotlin.runCatching { recognizer.cancel() }
        kotlin.runCatching { recognizer.destroy() }
        kotlin.runCatching { audioPfd.close() }

        return result
    }

    private fun recognizerLabel(
        useOnDevice: Boolean,
        preferOffline: Boolean,
        languageTag: String?,
        segmentedSession: Boolean,
    ): String {
        val mode = if (useOnDevice) "on-device" else "default-service"
        val net = if (preferOffline) "offline-preferred" else "online-allowed"
        val lang = languageTag ?: "auto"
        val segment = if (segmentedSession) "segmented" else "single-shot"
        return "Android SpeechRecognizer ($mode, $net, lang=$lang, $segment)"
    }

    private fun shouldRetrySttAttempt(reason: String): Boolean {
        if (reason.startsWith("speech_error_")) {
            return true
        }
        return when (reason) {
            "speech_language_unavailable",
            "speech_language_not_supported",
            "speech_network_error",
            "speech_network_timeout",
            "speech_start_failed",
            "speech_stt_timeout",
            "speech_client_error",
            "speech_recognizer_busy",
            "speech_recognizer_create_failed",
            "speech_audio_open_failed",
            -> true
            else -> false
        }
    }

    private fun speechErrorReason(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "speech_audio_error"
            SpeechRecognizer.ERROR_CLIENT -> "speech_client_error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "speech_permission_error"
            SpeechRecognizer.ERROR_NETWORK -> "speech_network_error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech_network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "speech_no_match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech_recognizer_busy"
            SpeechRecognizer.ERROR_SERVER -> "speech_server_error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout_no_match"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "speech_language_not_supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "speech_language_unavailable"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "speech_too_many_requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "speech_server_disconnected"
            else -> "speech_error_$error"
        }
    }

    private fun speechErrorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            else -> "UNKNOWN"
        }
    }

    private fun normalizeTranscription(raw: String): String? {
        var text = raw
            .replace("```", "")
            .trim()

        val lower = text.lowercase(Locale.US)
        if (
            lower == "<no-speech>" ||
            lower == "no speech" ||
            lower == "no_speech" ||
            lower.contains("<no-speech>")
        ) {
            return null
        }

        val prefixes = listOf("transcript:", "output:", "result:")
        prefixes.forEach { prefix ->
            if (text.lowercase(Locale.US).startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
            }
        }

        text = text.trim('"', '\'', '“', '”')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return null
        if (text.length > MAX_TRANSCRIPT_CHARS) {
            text = text.take(MAX_TRANSCRIPT_CHARS).trim()
        }
        return text
    }

    private fun localRuntimeConfig(): LocalModelRuntimeConfig {
        return LocalModelRuntimeConfig(
            enabled = AppPrefs.isLocalModelEnabled(context),
            backend = LocalModelBackend.LITERT_LM,
            modelPath2B = AppPrefs.getLocalModelPath2B(context),
            modelPath4B = AppPrefs.getLocalModelPath4B(context),
            ggufPath2B = AppPrefs.getLocalGgufPath2B(context),
            ggufPath4B = AppPrefs.getLocalGgufPath4B(context),
            maxTokens = 96,
            topK = 32,
            temperature = 0.1f,
            llamaContextSize = AppPrefs.getLocalLlamaContextSize(context),
            llamaThreads = AppPrefs.getLocalLlamaThreads(context),
        )
    }

    private fun captureAmbientProbe(): AmbientProbe? {
        val sampleRate = SAMPLE_RATE
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null

        val bufferSize = maxOf(minBufferSize, sampleRate / 2)
        val audioRecord = kotlin.runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return null

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            kotlin.runCatching { audioRecord.release() }
            return null
        }

        val frame = ShortArray(bufferSize)
        var totalSamples = 0L
        var totalSquares = 0.0
        var peakAbs = 0

        try {
            audioRecord.startRecording()
            val endAt = SystemClock.elapsedRealtime() + PROBE_WINDOW_MS
            while (SystemClock.elapsedRealtime() < endAt) {
                val read = audioRecord.read(frame, 0, frame.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val sample = frame[i].toInt()
                    val absSample = kotlin.math.abs(sample)
                    if (absSample > peakAbs) peakAbs = absSample
                    totalSquares += sample.toDouble() * sample.toDouble()
                }
                totalSamples += read
            }
        } catch (_: Throwable) {
            return null
        } finally {
            kotlin.runCatching { audioRecord.stop() }
            kotlin.runCatching { audioRecord.release() }
        }

        if (totalSamples <= 0L) {
            return AmbientProbe(rmsDb = -120f, peakDb = -120f, voiceLikely = false, noisyLikely = false)
        }

        val rms = sqrt(totalSquares / totalSamples.toDouble()) / MAX_PCM_AMPLITUDE
        val peak = peakAbs.toDouble() / MAX_PCM_AMPLITUDE

        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-7))).toFloat()
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-7))).toFloat()

        val voiceLikely = rmsDb >= VOICE_RMS_DB_THRESHOLD || peakDb >= VOICE_PEAK_DB_THRESHOLD
        val noisyLikely = rmsDb >= NOISY_RMS_DB_THRESHOLD || peakDb >= NOISY_PEAK_DB_THRESHOLD

        return AmbientProbe(
            rmsDb = rmsDb,
            peakDb = peakDb,
            voiceLikely = voiceLikely,
            noisyLikely = noisyLikely,
        )
    }

    private fun recordSpeechClipToWav(): AudioClip? {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferSize <= 0) return null

        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE / 4)
        val audioRecord = kotlin.runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return null

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            kotlin.runCatching { audioRecord.release() }
            return null
        }

        val pcmBuffer = ByteArrayOutputStream(bufferSize * 4)
        val frame = ShortArray(bufferSize)

        var totalSamples = 0L
        var totalSquares = 0.0
        var peakAbs = 0
        var accumulatedSpeechMs = 0L
        var trailingSilenceMs = 0L

        try {
            audioRecord.startRecording()
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < CAPTURE_MAX_MS) {
                val read = audioRecord.read(frame, 0, frame.size)
                if (read <= 0) continue

                var sumSquares = 0.0
                var framePeakAbs = 0
                for (i in 0 until read) {
                    val sample = frame[i].toInt()
                    val absSample = kotlin.math.abs(sample)
                    if (absSample > framePeakAbs) framePeakAbs = absSample
                    if (absSample > peakAbs) peakAbs = absSample
                    sumSquares += sample.toDouble() * sample.toDouble()
                    totalSquares += sample.toDouble() * sample.toDouble()

                    val v = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort().toInt()
                    pcmBuffer.write(v and 0xFF)
                    pcmBuffer.write((v shr 8) and 0xFF)
                }
                totalSamples += read

                val frameRms = sqrt(sumSquares / read.toDouble()) / MAX_PCM_AMPLITUDE
                val frameRmsDb = (20.0 * log10(frameRms.coerceAtLeast(1e-7))).toFloat()
                val frameMs = (read * 1000L) / SAMPLE_RATE
                val frameSpeechLikely = frameRmsDb >= CAPTURE_FRAME_SPEECH_DB || framePeakAbs >= CAPTURE_FRAME_SPEECH_PEAK

                if (frameSpeechLikely) {
                    accumulatedSpeechMs += frameMs
                    trailingSilenceMs = 0L
                } else {
                    trailingSilenceMs += frameMs
                }

                val canStopForSilence =
                    accumulatedSpeechMs >= CAPTURE_MIN_SPEECH_MS && trailingSilenceMs >= CAPTURE_END_SILENCE_MS
                if (canStopForSilence) {
                    break
                }
            }
        } catch (_: Throwable) {
            return null
        } finally {
            kotlin.runCatching { audioRecord.stop() }
            kotlin.runCatching { audioRecord.release() }
        }

        if (totalSamples <= 0L) return null
        val durationMs = (totalSamples * 1000L) / SAMPLE_RATE
        if (durationMs < CAPTURE_MIN_TOTAL_MS) return null

        val pcmBytes = pcmBuffer.toByteArray()
        val outputDir = File(context.filesDir, "audio_capture").apply { mkdirs() }
        val outputFile = File(outputDir, "clip-${System.currentTimeMillis()}.wav")

        kotlin.runCatching {
            writeWavFile(
                file = outputFile,
                pcmData = pcmBytes,
                sampleRate = SAMPLE_RATE,
                channels = 1,
                bitsPerSample = 16,
            )
        }.getOrElse {
            return null
        }

        val rms = sqrt(totalSquares / totalSamples.toDouble()) / MAX_PCM_AMPLITUDE
        val peak = peakAbs.toDouble() / MAX_PCM_AMPLITUDE

        val rmsDb = (20.0 * log10(rms.coerceAtLeast(1e-7))).toFloat()
        val peakDb = (20.0 * log10(peak.coerceAtLeast(1e-7))).toFloat()

        return AudioClip(
            file = outputFile,
            durationMs = durationMs,
            rmsDb = rmsDb,
            peakDb = peakDb,
        )
    }

    private fun writeWavFile(
        file: File,
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            writeIntLE(out, chunkSize)
            out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))

            out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            writeIntLE(out, 16)
            writeShortLE(out, 1)
            writeShortLE(out, channels)
            writeIntLE(out, sampleRate)
            writeIntLE(out, byteRate)
            writeShortLE(out, blockAlign)
            writeShortLE(out, bitsPerSample)

            out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            writeIntLE(out, dataSize)
            out.write(pcmData)
            out.flush()
        }
    }

    private fun writeIntLE(out: BufferedOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeShortLE(out: BufferedOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun maybeEmitNoSpeechEvent(
        probe: AmbientProbe,
        strategy: String = "vad_probe",
        wavPath: String? = null,
        metaPath: String? = null,
        clipMs: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastNoSpeechEventAt < NO_SPEECH_EMIT_INTERVAL_MS) return
        lastNoSpeechEventAt = now
        enqueueEvent(
            summary = "No clear speech detected; audio monitor is idle waiting for voice.",
            payload = mapOf(
                "status" to "no_speech",
                "strategy" to strategy,
                "rmsDb" to probe.rmsDb,
                "peakDb" to probe.peakDb,
                "voiceLikely" to probe.voiceLikely,
                "noisyLikely" to probe.noisyLikely,
                "mode" to "continuous_vad_local_stt",
                "wavPath" to wavPath.orEmpty(),
                "metaPath" to metaPath.orEmpty(),
                "clipMs" to (clipMs ?: -1L),
            )
        )
    }

    private fun persistClipMeta(
        clip: AudioClip,
        probe: AmbientProbe,
        status: String,
        strategy: String,
        transcript: String?,
        stitchedTranscript: String? = null,
        reason: String?,
        modelStatus: String?,
    ): String? {
        val metaFile = File(
            clip.file.parentFile ?: return null,
            "${clip.file.nameWithoutExtension}.json",
        )
        val json = JSONObject()
            .put("createdAt", System.currentTimeMillis())
            .put("wavPath", clip.file.absolutePath)
            .put("fileName", clip.file.name)
            .put("status", status)
            .put("strategy", strategy)
            .put("reason", reason)
            .put("transcript", transcript)
            .put("stitchedTranscript", stitchedTranscript)
            .put("modelStatus", modelStatus)
            .put("durationMs", clip.durationMs)
            .put("clipRmsDb", clip.rmsDb.toDouble())
            .put("clipPeakDb", clip.peakDb.toDouble())
            .put("probeRmsDb", probe.rmsDb.toDouble())
            .put("probePeakDb", probe.peakDb.toDouble())
            .put("voiceLikely", probe.voiceLikely)
            .put("noisyLikely", probe.noisyLikely)

        return kotlin.runCatching {
            metaFile.writeText(json.toString())
            metaFile.absolutePath
        }.getOrNull()
    }

    private fun enforceClipRetention() {
        val dir = File(context.filesDir, "audio_capture")
        val wavFiles = dir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }.orEmpty()

        val now = System.currentTimeMillis()
        wavFiles.forEachIndexed { index, wav ->
            val ageMs = now - wav.lastModified()
            val shouldDelete = index >= MAX_WAV_FILES || ageMs > WAV_TTL_MS
            if (!shouldDelete) return@forEachIndexed
            kotlin.runCatching { wav.delete() }
            val meta = File(wav.parentFile, "${wav.nameWithoutExtension}.json")
            kotlin.runCatching { meta.delete() }
        }
    }

    private fun maybeEmitErrorEvent(reason: String, payload: Map<String, Any>) {
        val now = System.currentTimeMillis()
        if (now - lastErrorEventAt < ERROR_EMIT_INTERVAL_MS) return
        lastErrorEventAt = now

        enqueueEvent(
            summary = "Local speech transcription unstable: $reason",
            payload = mapOf(
                "status" to "error",
                "reason" to reason,
                "mode" to "continuous_vad_local_stt",
            ) + payload,
        )
    }

    private fun maybeEmitPermissionEvent() {
        val now = System.currentTimeMillis()
        if (now - lastPermissionEventAt < NO_PERMISSION_EMIT_INTERVAL_MS) return
        lastPermissionEventAt = now
        enqueueEvent(
            summary = "Microphone permission missing; audio collection paused.",
            payload = mapOf(
                "status" to "permission_missing",
                "permission" to android.Manifest.permission.RECORD_AUDIO,
            )
        )
    }

    private fun enqueueEvent(summary: String, payload: Map<String, Any>) {
        enqueueEvent(
            ContextEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = System.currentTimeMillis(),
                source = descriptor.id,
                category = "audio",
                summary = summary,
                payload = payload,
                sensitivity = Sensitivity.HIGH,
                ttlSeconds = 43_200,
            )
        )
    }

    private fun enqueueEvent(event: ContextEvent) {
        synchronized(queueLock) {
            pendingEvents.addLast(event)
            while (pendingEvents.size > MAX_PENDING_EVENTS) {
                pendingEvents.removeFirstOrNull()
            }
        }
    }

    private fun drainPendingEvents(limit: Int): List<ContextEvent> {
        synchronized(queueLock) {
            if (pendingEvents.isEmpty()) return emptyList()
            val count = minOf(limit, pendingEvents.size)
            return List(count) { pendingEvents.removeFirst() }
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private sealed interface TranscriptionResult {
        data class Recognized(
            val transcript: String,
            val strategy: String,
            val modelStatus: String,
        ) : TranscriptionResult

        data class NoSpeech(
            val strategy: String,
        ) : TranscriptionResult

        data class Error(
            val reason: String,
            val strategy: String,
            val modelStatus: String,
        ) : TranscriptionResult

        data class Skipped(
            val reason: String,
            val strategy: String,
        ) : TranscriptionResult
    }

    private data class AmbientProbe(
        val rmsDb: Float,
        val peakDb: Float,
        val voiceLikely: Boolean,
        val noisyLikely: Boolean,
    )

    private data class AudioClip(
        val file: File,
        val durationMs: Long,
        val rmsDb: Float,
        val peakDb: Float,
    )

    data class ReplayOutcome(
        val status: String,
        val strategy: String,
        val reason: String?,
        val modelStatus: String,
        val transcript: String?,
        val metaPath: String?,
    )

    private data class SttAttempt(
        val useOnDevice: Boolean,
        val preferOffline: Boolean,
        val languageTag: String?,
        val useSegmentedSession: Boolean,
        val name: String,
    )

    private data class MlKitModeAttempt(
        val mode: Int,
        val label: String,
        val locale: Locale?,
        val localeLabel: String,
    )

    private sealed interface AndroidSpeechOutcome {
        data class Success(
            val text: String,
            val engine: String,
        ) : AndroidSpeechOutcome

        data class Partial(
            val text: String,
            val engine: String,
            val reason: String,
        ) : AndroidSpeechOutcome

        data class NoMatch(
            val reason: String,
        ) : AndroidSpeechOutcome

        data class Error(
            val reason: String,
            val detail: String,
        ) : AndroidSpeechOutcome
    }

    private sealed interface MlKitRecognitionOutcome {
        data class Recognized(
            val text: String,
            val engine: String,
        ) : MlKitRecognitionOutcome

        data class NoSpeech(
            val reason: String,
        ) : MlKitRecognitionOutcome

        data class Error(
            val reason: String,
            val detail: String,
        ) : MlKitRecognitionOutcome
    }

    companion object {
        private const val LOG_TAG = "AudioAmbientPlugin"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_EVENTS_PER_POLL = 24
        private const val MAX_PENDING_EVENTS = 300

        private const val PROBE_WINDOW_MS = 2_400L
        private const val SILENT_PROBE_INTERVAL_MS = 4_000L
        private const val AFTER_CYCLE_DELAY_MS = 800L

        private const val CAPTURE_MAX_MS = 28_000L
        private const val CAPTURE_MIN_TOTAL_MS = 800L
        private const val CAPTURE_MIN_SPEECH_MS = 1_200L
        private const val CAPTURE_END_SILENCE_MS = 3_600L

        private const val VOICE_RMS_DB_THRESHOLD = -44f
        private const val VOICE_PEAK_DB_THRESHOLD = -25f
        private const val NOISY_RMS_DB_THRESHOLD = -30f
        private const val NOISY_PEAK_DB_THRESHOLD = -12f

        private const val CAPTURE_FRAME_SPEECH_DB = -46f
        private const val CAPTURE_FRAME_SPEECH_PEAK = 3800

        private const val RECOGNIZED_DEDUP_MS = 90_000L
        private const val NO_SPEECH_EMIT_INTERVAL_MS = 180_000L
        private const val ERROR_EMIT_INTERVAL_MS = 90_000L
        private const val NO_PERMISSION_EMIT_INTERVAL_MS = 300_000L
        private const val NO_PERMISSION_RETRY_MS = 10_000L
        private const val ERROR_RETRY_MS = 5_000L

        private const val MAX_TRANSCRIPT_CHARS = 420
        private const val MAX_STITCHED_TRANSCRIPT_CHARS = 1_400
        private const val STITCH_GAP_RESET_MS = 5_500L
        private const val MAX_PCM_AMPLITUDE = 32768.0
        private const val MAX_WAV_FILES = 120
        private const val WAV_TTL_MS = 48L * 60L * 60L * 1000L
        private const val WAV_HEADER_BYTES = 44
        private const val FILE_STT_TIMEOUT_MS = 22_000L
        private const val MLKIT_DOWNLOAD_TIMEOUT_MS = 180_000L
        private const val RETRY_ATTEMPT_DELAY_MS = 260L

        // LiteRT-LM multimodal audio path currently crashes native library on some devices.
        private const val ENABLE_EXPERIMENTAL_LITERT_AUDIO_STT = false
        private const val ENABLE_MLKIT_GENAI_STT = false
        private const val ENABLE_ANDROID_FILE_STT = true
        private const val ENABLE_ANDROID_STT_FALLBACK_AFTER_MLKIT = true
    }
}
