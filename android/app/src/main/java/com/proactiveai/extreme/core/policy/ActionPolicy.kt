package com.proactiveai.extreme.core.policy

import com.proactiveai.extreme.core.model.RiskLevel

enum class ExecutionMode {
    AUTO_EXECUTE,
    REQUIRE_CONFIRMATION,
    BLOCK,
}

data class ActionCandidate(
    val actionId: String,
    val riskLevel: RiskLevel,
    val touchesSensitiveConnector: Boolean,
)

object ActionPolicy {
    fun decide(candidate: ActionCandidate): ExecutionMode {
        if (candidate.touchesSensitiveConnector && candidate.riskLevel != RiskLevel.LOW) {
            return ExecutionMode.REQUIRE_CONFIRMATION
        }

        return when (candidate.riskLevel) {
            RiskLevel.LOW -> ExecutionMode.AUTO_EXECUTE
            RiskLevel.MEDIUM -> ExecutionMode.REQUIRE_CONFIRMATION
            RiskLevel.HIGH -> ExecutionMode.REQUIRE_CONFIRMATION
            RiskLevel.CRITICAL -> ExecutionMode.BLOCK
        }
    }
}
