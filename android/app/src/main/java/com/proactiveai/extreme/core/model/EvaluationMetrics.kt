package com.proactiveai.extreme.core.model

data class EvaluationMetrics(
    val suggestionsTotal: Int,
    val suggestionsAccepted: Int,
    val executionsTotal: Int,
    val executionsSuccessful: Int,
    val interruptionsTotal: Int,
    val interruptionsPositive: Int,
) {
    val intentMatchRate: Int
        get() = rate(suggestionsAccepted, suggestionsTotal)

    val actionHelpfulness: Int
        get() = rate(executionsSuccessful, executionsTotal)

    val interruptionQuality: Int
        get() = rate(interruptionsPositive, interruptionsTotal)

    private fun rate(numerator: Int, denominator: Int): Int {
        if (denominator <= 0) return 0
        return (numerator * 100) / denominator
    }
}
