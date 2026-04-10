package com.proactiveai.extreme.core.edge

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LiteRtLmRuntime private constructor(
    private val appContext: Context,
) {
    private data class Runner(
        val modelPath: String,
        val engine: Engine,
    )

    private val runners = ConcurrentHashMap<EdgeModelProfile, Runner>()

    @Synchronized
    fun generate(
        profile: EdgeModelProfile,
        prompt: String,
        config: LocalModelRuntimeConfig,
    ): LocalModelRuntimeResult {
        if (!config.enabled) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "Native model disabled by user.",
            )
        }

        val modelPath = config.taskPathFor(profile).trim()
        if (modelPath.isBlank()) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM model path is empty for ${profile.label}.",
            )
        }

        val file = File(modelPath)
        if (!file.exists()) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM file not found: $modelPath",
            )
        }

        return runCatching {
            val output = generateWithRunnerRetry(
                profile = profile,
                modelPath = modelPath,
                prompt = prompt,
            )
            if (output.isBlank()) {
                error("LiteRT-LM returned empty output.")
            }
            LocalModelRuntimeResult(
                usedNativeModel = true,
                output = output,
                message = "Native LiteRT-LM used (${profile.label})",
            )
        }.getOrElse { error ->
            LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM fallback: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    @Synchronized
    fun transcribeAudio(
        profile: EdgeModelProfile,
        audioFilePath: String,
        prompt: String,
        config: LocalModelRuntimeConfig,
    ): LocalModelRuntimeResult {
        if (!config.enabled) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "Native model disabled by user.",
            )
        }

        val modelPath = config.taskPathFor(profile).trim()
        if (modelPath.isBlank()) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM model path is empty for ${profile.label}.",
            )
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM file not found: $modelPath",
            )
        }

        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            return LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "Audio file not found: $audioFilePath",
            )
        }

        return runCatching {
            val output = transcribeWithRunnerRetry(
                profile = profile,
                modelPath = modelPath,
                audioFilePath = audioFilePath,
                prompt = prompt,
            )
            if (output.isBlank()) {
                error("LiteRT-LM audio transcription returned empty output.")
            }
            LocalModelRuntimeResult(
                usedNativeModel = true,
                output = output,
                message = "Native LiteRT-LM audio transcribe used (${profile.label})",
            )
        }.getOrElse { error ->
            LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "LiteRT-LM audio fallback: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    fun unloadAll() {
        runners.values.forEach { closeRunner(it) }
        runners.clear()
    }

    private fun ensureRunner(
        profile: EdgeModelProfile,
        modelPath: String,
    ): Runner {
        val existing = runners[profile]
        if (existing != null && existing.modelPath == modelPath) {
            return existing
        }

        if (existing != null) {
            closeRunner(existing)
        }

        val runner = createRunner(modelPath)
        runners[profile] = runner
        return runner
    }

    private fun createRunner(modelPath: String): Runner {
        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = appContext.cacheDir.absolutePath,
            )
        )
        engine.initialize()
        return Runner(
            modelPath = modelPath,
            engine = engine,
        )
    }

    private fun generateWithRunnerRetry(
        profile: EdgeModelProfile,
        modelPath: String,
        prompt: String,
    ): String {
        var runner = ensureRunner(profile, modelPath)
        return try {
            createConversationAndGenerate(runner, prompt)
        } catch (firstError: Throwable) {
            if (!isSessionAlreadyExistsError(firstError)) {
                throw firstError
            }

            closeRunner(runner)
            runners.remove(profile, runner)
            runner = createRunner(modelPath)
            runners[profile] = runner
            createConversationAndGenerate(runner, prompt)
        }
    }

    private fun transcribeWithRunnerRetry(
        profile: EdgeModelProfile,
        modelPath: String,
        audioFilePath: String,
        prompt: String,
    ): String {
        var runner = ensureRunner(profile, modelPath)
        return try {
            createConversationAndTranscribe(runner, audioFilePath, prompt)
        } catch (firstError: Throwable) {
            if (!isSessionAlreadyExistsError(firstError)) {
                throw firstError
            }

            closeRunner(runner)
            runners.remove(profile, runner)
            runner = createRunner(modelPath)
            runners[profile] = runner
            createConversationAndTranscribe(runner, audioFilePath, prompt)
        }
    }

    private fun createConversationAndGenerate(
        runner: Runner,
        prompt: String,
    ): String {
        val conversation = runner.engine.createConversation()
        return try {
            invokeSendMessage(conversation, prompt)
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun createConversationAndTranscribe(
        runner: Runner,
        audioFilePath: String,
        prompt: String,
    ): String {
        val conversation = runner.engine.createConversation()
        return try {
            val input = Contents.Companion.of(
                Content.AudioFile(audioFilePath),
                Content.Text(prompt),
            )
            val message = conversation.sendMessage(input, emptyMap<String, Any>())
            val extractedText = extractTextFromMessage(message).trim()
            if (extractedText.isNotBlank()) {
                extractedText
            } else {
                message.toString().trim()
            }
        } finally {
            runCatching { conversation.close() }
        }
    }

    private fun invokeSendMessage(
        conversation: AutoCloseable,
        prompt: String,
    ): String {
        val methods = conversation.javaClass.methods.filter {
            it.name == "sendMessage" &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == String::class.java
        }

        val message = when {
            methods.any { it.parameterTypes.size == 2 && Map::class.java.isAssignableFrom(it.parameterTypes[1]) } -> {
                val method = methods.first {
                    it.parameterTypes.size == 2 && Map::class.java.isAssignableFrom(it.parameterTypes[1])
                }
                method.invoke(conversation, prompt, emptyMap<String, Any>())
            }

            methods.any { it.parameterTypes.size == 1 } -> {
                val method = methods.first { it.parameterTypes.size == 1 }
                method.invoke(conversation, prompt)
            }

            else -> error("LiteRT-LM Conversation.sendMessage(String, Map?) not found")
        }

        val extractedText = extractTextFromMessage(message).trim()
        if (extractedText.isNotBlank()) {
            return extractedText
        }

        return message?.toString().orEmpty().trim()
    }

    private fun extractTextFromMessage(message: Any?): String {
        if (message == null) return ""

        val contents = runCatching {
            message.javaClass.getMethod("getContents").invoke(message)
        }.getOrNull() ?: return ""

        val parts = runCatching {
            @Suppress("UNCHECKED_CAST")
            contents.javaClass.getMethod("getContents").invoke(contents) as? Iterable<Any?>
        }.getOrNull() ?: return ""

        val textParts = parts.mapNotNull { part ->
            if (part == null || !part.javaClass.name.endsWith("\$Text")) {
                return@mapNotNull null
            }
            runCatching {
                part.javaClass.getMethod("getText").invoke(part)?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

        return textParts.joinToString("\n")
    }

    private fun closeRunner(runner: Runner) {
        runCatching { runner.engine.close() }
    }

    private fun isSessionAlreadyExistsError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("session already exists", ignoreCase = true) ||
                message.contains("FAILED_PRECONDITION", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        @Volatile
        private var instance: LiteRtLmRuntime? = null

        fun getInstance(context: Context): LiteRtLmRuntime {
            return instance ?: synchronized(this) {
                instance ?: LiteRtLmRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
