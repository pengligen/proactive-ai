package com.proactiveai.extreme.core.context.engine

import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin

class ContextEngine(
    private val plugins: List<ContextPlugin>,
) {
    private val startedPluginIds = mutableSetOf<String>()

    suspend fun startEnabled(enabledPluginIds: Set<String>) {
        plugins.forEach { plugin ->
            val pluginId = plugin.descriptor.id
            val shouldRun = pluginId in enabledPluginIds
            val alreadyStarted = pluginId in startedPluginIds

            if (shouldRun && !alreadyStarted) {
                if (plugin.start()) {
                    startedPluginIds += pluginId
                }
            } else if (!shouldRun && alreadyStarted) {
                plugin.stop()
                startedPluginIds -= pluginId
            }
        }
    }

    suspend fun stopAll() {
        plugins.forEach {
            kotlin.runCatching { it.stop() }
        }
        startedPluginIds.clear()
    }

    suspend fun collectTick(enabledPluginIds: Set<String>): List<ContextEvent> {
        return collectTickByPlugin(enabledPluginIds)
            .values
            .flatten()
            .sortedBy { it.occurredAt }
    }

    suspend fun collectTickByPlugin(enabledPluginIds: Set<String>): Map<String, List<ContextEvent>> {
        return plugins
            .asSequence()
            .filter { it.descriptor.id in enabledPluginIds }
            .associate { plugin ->
                val events = kotlin.runCatching { plugin.poll() }.getOrDefault(emptyList())
                plugin.descriptor.id to events
            }
    }
}
