package com.proactiveai.extreme.core.edge

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

enum class LocalModelBackend(
    val id: String,
    val label: String,
) {
    LITERT_LM(
        id = "litert_lm",
        label = "LiteRT-LM (.litertlm)",
    ),
    MEDIAPIPE_TASK(
        id = "mediapipe_task",
        label = "MediaPipe (.task)",
    );

    companion object {
        fun fromId(value: String): LocalModelBackend {
            return entries.firstOrNull { it.id == value } ?: LITERT_LM
        }
    }
}

data class LocalModelRuntimeConfig(
    val enabled: Boolean,
    val backend: LocalModelBackend = LocalModelBackend.LITERT_LM,
    val modelPath2B: String,
    val modelPath4B: String,
    val ggufPath2B: String,
    val ggufPath4B: String,
    val maxTokens: Int = 256,
    val topK: Int = 40,
    val temperature: Float = 0.8f,
    val llamaContextSize: Int = 4096,
    val llamaThreads: Int = 0,
) {
    fun taskPathFor(profile: EdgeModelProfile): String {
        return when (profile) {
            EdgeModelProfile.GEMMA_EFFECTIVE_2B -> modelPath2B
            EdgeModelProfile.GEMMA_EFFECTIVE_4B -> modelPath4B
        }
    }

    fun ggufPathFor(profile: EdgeModelProfile): String {
        return when (profile) {
            EdgeModelProfile.GEMMA_EFFECTIVE_2B -> ggufPath2B
            EdgeModelProfile.GEMMA_EFFECTIVE_4B -> ggufPath4B
        }
    }
}

data class LocalModelRuntimeResult(
    val usedNativeModel: Boolean,
    val output: String?,
    val message: String,
)

class MediapipeLlmRuntime private constructor(
    private val appContext: Context,
) {
    private data class Runner(
        val modelPath: String,
        val maxTokens: Int,
        val topK: Int,
        val temperature: Float,
        val inference: Any,
    )

    private val runners = ConcurrentHashMap<EdgeModelProfile, Runner>()

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
                message = "Model path is empty for ${profile.label}.",
            )
        }

        return runCatching {
            val runner = ensureRunner(profile, modelPath, config)
            val output = invokeGenerate(runner.inference, prompt)
            LocalModelRuntimeResult(
                usedNativeModel = true,
                output = output,
                message = "Native model used (${profile.label})",
            )
        }.getOrElse { error ->
            LocalModelRuntimeResult(
                usedNativeModel = false,
                output = null,
                message = "Native model fallback: ${error.message ?: error.javaClass.simpleName}",
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
        config: LocalModelRuntimeConfig,
    ): Runner {
        val current = runners[profile]
        if (
            current != null &&
            current.modelPath == modelPath &&
            current.maxTokens == config.maxTokens &&
            current.topK == config.topK &&
            current.temperature == config.temperature
        ) {
            return current
        }

        if (current != null) {
            closeRunner(current)
        }

        val inference = createInference(
            modelPath = modelPath,
            maxTokens = config.maxTokens,
            topK = config.topK,
            temperature = config.temperature,
        )
        val runner = Runner(
            modelPath = modelPath,
            maxTokens = config.maxTokens,
            topK = config.topK,
            temperature = config.temperature,
            inference = inference,
        )
        runners[profile] = runner
        return runner
    }

    private fun createInference(
        modelPath: String,
        maxTokens: Int,
        topK: Int,
        temperature: Float,
    ): Any {
        val llmInferenceClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
        val optionsClass = Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions")
        val intClass = Int::class.javaPrimitiveType ?: Int::class.java
        val floatClass = Float::class.javaPrimitiveType ?: Float::class.java

        val builder = optionsClass.getMethod("builder").invoke(null)
            ?: error("Failed to create LlmInferenceOptions builder")
        invokeBuilder(builder, "setModelPath", arrayOf(String::class.java), arrayOf(modelPath))
        invokeBuilder(builder, "setMaxTokens", arrayOf(intClass), arrayOf(maxTokens))

        if (!invokeBuilder(builder, "setTopK", arrayOf(intClass), arrayOf(topK))) {
            invokeBuilder(builder, "setMaxTopK", arrayOf(intClass), arrayOf(topK))
        }
        invokeBuilder(builder, "setTemperature", arrayOf(floatClass), arrayOf(temperature))
        val options = builder.javaClass.getMethod("build").invoke(builder)
            ?: error("Failed to build LlmInferenceOptions")

        val createMethod = llmInferenceClass.getMethod("createFromOptions", Context::class.java, optionsClass)
        return createMethod.invoke(null, appContext, options)
            ?: error("LlmInference.createFromOptions returned null")
    }

    private fun invokeGenerate(inference: Any, prompt: String): String {
        val method = inference.javaClass.getMethod("generateResponse", String::class.java)
        val result = method.invoke(inference, prompt)
        return result?.toString().orEmpty()
    }

    private fun invokeBuilder(
        builder: Any,
        methodName: String,
        parameterTypes: Array<Class<*>?>,
        args: Array<Any>,
    ): Boolean {
        return runCatching {
            val method = builder.javaClass.getMethod(methodName, *parameterTypes)
            method.invoke(builder, *args)
            true
        }.getOrDefault(false)
    }

    private fun closeRunner(runner: Runner) {
        when (val inference = runner.inference) {
            is AutoCloseable -> runCatching { inference.close() }
            else -> runCatching {
                val closeMethod = inference.javaClass.methods.firstOrNull { it.name == "close" && it.parameterCount == 0 }
                closeMethod?.invoke(inference)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MediapipeLlmRuntime? = null

        fun getInstance(context: Context): MediapipeLlmRuntime {
            return instance ?: synchronized(this) {
                instance ?: MediapipeLlmRuntime(context.applicationContext).also { instance = it }
            }
        }
    }
}
