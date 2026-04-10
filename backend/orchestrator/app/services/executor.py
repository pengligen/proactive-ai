from __future__ import annotations

from ..connectors.composio_client import ComposioClient
from ..models import ActionExecutionResult, ActionPlan, ExecutionStatus, HitlDecision


class ExecutionService:
    def __init__(self, composio_client: ComposioClient) -> None:
        self._composio_client = composio_client

    def execute(self, plan: ActionPlan, step_id: str, decision: HitlDecision | None) -> ActionExecutionResult:
        step = next((s for s in plan.steps if s.stepId == step_id), None)
        if step is None:
            return ActionExecutionResult(status=ExecutionStatus.FAILED, details=f"Unknown stepId={step_id}")

        if plan.requiresUserConfirmation and (decision is None or not decision.approved):
            return ActionExecutionResult(
                status=ExecutionStatus.CANCELLED,
                details="Action requires confirmation and was not approved.",
            )

        connector_result = self._composio_client.execute(
            connector=step.connector,
            operation=step.operation,
            args=step.args,
        )

        if connector_result.ok:
            return ActionExecutionResult(status=ExecutionStatus.SUCCESS, details=connector_result.detail)

        return ActionExecutionResult(status=ExecutionStatus.FAILED, details=connector_result.detail)
