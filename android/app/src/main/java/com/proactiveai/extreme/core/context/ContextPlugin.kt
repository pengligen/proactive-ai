package com.proactiveai.extreme.core.context

import com.proactiveai.extreme.core.model.PluginDescriptor

interface ContextPlugin {
    val descriptor: PluginDescriptor

    suspend fun start(): Boolean

    suspend fun stop()

    suspend fun poll(): List<ContextEvent>
}
