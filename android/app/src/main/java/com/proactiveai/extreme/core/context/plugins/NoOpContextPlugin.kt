package com.proactiveai.extreme.core.context.plugins

import com.proactiveai.extreme.core.context.ContextEvent
import com.proactiveai.extreme.core.context.ContextPlugin
import com.proactiveai.extreme.core.model.PluginDescriptor

class NoOpContextPlugin(
    override val descriptor: PluginDescriptor,
) : ContextPlugin {
    override suspend fun start(): Boolean = true

    override suspend fun stop() = Unit

    override suspend fun poll(): List<ContextEvent> = emptyList()
}
