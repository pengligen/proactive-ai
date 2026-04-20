package com.proactiveai.extreme

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.proactiveai.extreme.app.AppPrefs
import com.proactiveai.extreme.core.edge.EdgeModelProfile
import com.proactiveai.extreme.core.edge.LiteRtLmRuntime
import com.proactiveai.extreme.core.edge.LocalModelBackend
import com.proactiveai.extreme.core.edge.LocalModelRuntimeConfig
import com.proactiveai.extreme.service.ProactiveCollectionService
import com.proactiveai.extreme.sync.ActionExecutionScheduler
import com.proactiveai.extreme.sync.SyncScheduler
import com.proactiveai.extreme.ui.ProactiveExtremeApp
import com.proactiveai.extreme.ui.theme.ProactiveTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ProactiveAI"
        const val ACTION_LLAMA_SELFTEST = "com.proactiveai.extreme.ACTION_LLAMA_SELFTEST"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppPrefs.markUiForegrounded(this)
        SyncScheduler.ensurePeriodic(this)
        ActionExecutionScheduler.ensurePeriodic(this)
        maybeRunLlamaSelfTest()

        setContent {
            ProactiveTheme {
                ProactiveExtremeApp()
            }
        }
    }

    private fun maybeRunLlamaSelfTest() {
        if (intent?.action != ACTION_LLAMA_SELFTEST) {
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val runtime = LiteRtLmRuntime.getInstance(this@MainActivity)
            val config = LocalModelRuntimeConfig(
                enabled = true,
                backend = LocalModelBackend.LITERT_LM,
                modelPath2B = AppPrefs.getLocalModelPath2B(this@MainActivity),
                modelPath4B = AppPrefs.getLocalModelPath4B(this@MainActivity),
                ggufPath2B = AppPrefs.getLocalGgufPath2B(this@MainActivity),
                ggufPath4B = AppPrefs.getLocalGgufPath4B(this@MainActivity),
                maxTokens = 48,
                topK = 40,
                temperature = 0.7f,
                llamaContextSize = AppPrefs.getLocalLlamaContextSize(this@MainActivity),
                llamaThreads = AppPrefs.getLocalLlamaThreads(this@MainActivity),
            )

            val result = runtime.generate(
                profile = EdgeModelProfile.GEMMA_EFFECTIVE_2B,
                prompt = "Reply with one short sentence saying hello from local LiteRT-LM on Android.",
                config = config,
            )

            Log.i(
                TAG,
                "LITERTLM_SELFTEST used=${result.usedNativeModel} message=${result.message} output=${result.output?.take(120)}",
            )
        }
    }

    override fun onStart() {
        super.onStart()
        AppPrefs.markUiForegrounded(this)
    }

    override fun onResume() {
        super.onResume()
        AppPrefs.markUiForegrounded(this)
        if (AppPrefs.isCollectionEnabled(this)) {
            ProactiveCollectionService.start(this)
        }
    }

    override fun onStop() {
        AppPrefs.markUiBackgrounded(this)
        super.onStop()
    }
}
