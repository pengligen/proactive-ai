from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class Sensitivity(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class RiskLevel(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class ExecutionStatus(str, Enum):
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class ContextEvent(BaseModel):
    eventId: str
    occurredAt: int
    source: str
    category: str
    summary: str
    payload: Dict[str, Any] = Field(default_factory=dict)
    sensitivity: Sensitivity
    ttlSeconds: int


class EventBatch(BaseModel):
    userId: str
    sessionId: Optional[str] = None
    events: List[ContextEvent]


class PlanRequest(BaseModel):
    userId: str
    now: int
    contextWindow: List[ContextEvent]


class ActionStep(BaseModel):
    stepId: str
    connector: str
    operation: str
    args: Dict[str, Any] = Field(default_factory=dict)


class ActionPlan(BaseModel):
    planId: str
    goal: str
    riskLevel: RiskLevel
    requiresUserConfirmation: bool
    explainWhy: str
    steps: List[ActionStep]


class ActionExecutionRequest(BaseModel):
    planId: str
    stepId: str


class ActionExecutionResult(BaseModel):
    status: ExecutionStatus
    details: Optional[str] = None


class HitlDecision(BaseModel):
    planId: str
    approved: bool
    note: Optional[str] = None


class HealthResponse(BaseModel):
    status: str


class ConnectorStatus(BaseModel):
    connector: str
    connected: bool
    accountLabel: Optional[str] = None
    lastConnectedAt: Optional[int] = None


class ConnectorStatusResponse(BaseModel):
    userId: str
    connectors: List[ConnectorStatus]


class ConnectorAuthorizeRequest(BaseModel):
    userId: str


class ConnectorAuthorizeResponse(BaseModel):
    userId: str
    connector: str
    connected: bool
    authUrl: Optional[str] = None
    message: str


class ConnectorDisconnectRequest(BaseModel):
    userId: str
