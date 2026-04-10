from __future__ import annotations

import time
import uuid
from typing import List

from ..models import ActionPlan, ActionStep, ContextEvent, PlanRequest, RiskLevel, Sensitivity


class PlannerService:
    """Heuristic planner for local prototyping.

    This will later be replaced by cloud LLM orchestration + policy layer.
    """

    def build_plan(self, request: PlanRequest) -> ActionPlan:
        events: List[ContextEvent] = request.contextWindow
        if not events:
            return ActionPlan(
                planId=str(uuid.uuid4()),
                goal="Bootstrap context collection",
                riskLevel=RiskLevel.LOW,
                requiresUserConfirmation=False,
                explainWhy="No recent events were provided; start by collecting context safely.",
                steps=[
                    ActionStep(
                        stepId=f"step-{int(time.time())}",
                        connector="local",
                        operation="collect_more_context",
                        args={"window_minutes": 30},
                    )
                ],
            )

        has_high_sensitivity = any(e.sensitivity == Sensitivity.HIGH for e in events)
        has_comm = any(e.category in {"communication", "notification"} for e in events)
        has_calendar = any("calendar" in e.summary.lower() or e.category == "task" for e in events)

        risk = RiskLevel.MEDIUM if has_high_sensitivity else RiskLevel.LOW
        requires_confirmation = risk != RiskLevel.LOW

        steps: List[ActionStep] = []

        if has_comm:
            steps.append(
                ActionStep(
                    stepId=str(uuid.uuid4()),
                    connector="gmail",
                    operation="draft_reply_candidates",
                    args={"top_k": 3},
                )
            )

        if has_calendar:
            steps.append(
                ActionStep(
                    stepId=str(uuid.uuid4()),
                    connector="slack",
                    operation="prepare_meeting_brief",
                    args={"depth": "deep"},
                )
            )

        if not steps:
            steps.append(
                ActionStep(
                    stepId=str(uuid.uuid4()),
                    connector="github",
                    operation="fetch_assigned_prs",
                    args={"limit": 5},
                )
            )

        return ActionPlan(
            planId=str(uuid.uuid4()),
            goal="Generate proactive support bundle",
            riskLevel=risk,
            requiresUserConfirmation=requires_confirmation,
            explainWhy="Plan built from the latest context window and sensitivity mix.",
            steps=steps,
        )
