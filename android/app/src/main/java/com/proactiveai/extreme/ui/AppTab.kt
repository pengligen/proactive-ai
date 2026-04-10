package com.proactiveai.extreme.ui

enum class AppTab(
    val label: String,
    val title: String,
) {
    PERMISSIONS(label = "Permissions", title = "Permissions & Collection"),
    MODELS(label = "Models", title = "On-device Model Lab"),
    CONTEXTS(label = "Contexts", title = "Context Timeline"),
    ASSISTANT(label = "Assistant", title = "Proactive Assistant"),
}
