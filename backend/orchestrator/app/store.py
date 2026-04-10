from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from threading import Lock
import time
from typing import Dict, List, Optional

from .models import ActionPlan, ConnectorStatus, ContextEvent, HitlDecision


@dataclass
class OrchestratorStore:
    events_by_user: Dict[str, List[ContextEvent]] = field(default_factory=lambda: defaultdict(list))
    plans_by_id: Dict[str, ActionPlan] = field(default_factory=dict)
    decisions_by_plan: Dict[str, HitlDecision] = field(default_factory=dict)
    connectors_by_user: Dict[str, Dict[str, ConnectorStatus]] = field(default_factory=lambda: defaultdict(dict))
    lock: Lock = field(default_factory=Lock)

    def append_events(self, user_id: str, events: List[ContextEvent]) -> None:
        with self.lock:
            self.events_by_user[user_id].extend(events)
            # Keep only the latest window for fast in-memory experimentation.
            self.events_by_user[user_id] = self.events_by_user[user_id][-2000:]

    def recent_events(self, user_id: str, limit: int = 200) -> List[ContextEvent]:
        with self.lock:
            return list(self.events_by_user.get(user_id, []))[-limit:]

    def save_plan(self, plan: ActionPlan) -> None:
        with self.lock:
            self.plans_by_id[plan.planId] = plan

    def get_plan(self, plan_id: str) -> Optional[ActionPlan]:
        with self.lock:
            return self.plans_by_id.get(plan_id)

    def save_decision(self, decision: HitlDecision) -> None:
        with self.lock:
            self.decisions_by_plan[decision.planId] = decision

    def get_decision(self, plan_id: str) -> Optional[HitlDecision]:
        with self.lock:
            return self.decisions_by_plan.get(plan_id)

    def list_connectors(self, user_id: str) -> List[ConnectorStatus]:
        with self.lock:
            user_connectors = self.connectors_by_user.get(user_id, {})
            connectors = []
            for connector in ("gmail", "slack", "github"):
                current = user_connectors.get(connector)
                connectors.append(
                    current
                    if current is not None
                    else ConnectorStatus(connector=connector, connected=False)
                )
            return connectors

    def set_connector_connected(self, user_id: str, connector: str, account_label: str | None) -> ConnectorStatus:
        with self.lock:
            status = ConnectorStatus(
                connector=connector,
                connected=True,
                accountLabel=account_label,
                lastConnectedAt=int(time.time() * 1000),
            )
            self.connectors_by_user[user_id][connector] = status
            return status

    def set_connector_disconnected(self, user_id: str, connector: str) -> ConnectorStatus:
        with self.lock:
            status = ConnectorStatus(
                connector=connector,
                connected=False,
                accountLabel=None,
                lastConnectedAt=None,
            )
            self.connectors_by_user[user_id][connector] = status
            return status
