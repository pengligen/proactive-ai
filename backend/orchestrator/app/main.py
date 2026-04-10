from __future__ import annotations

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi import Query

from .connectors.composio_client import ComposioClient
from .models import (
    ActionExecutionRequest,
    ActionExecutionResult,
    ActionPlan,
    ConnectorAuthorizeRequest,
    ConnectorAuthorizeResponse,
    ConnectorDisconnectRequest,
    ConnectorStatusResponse,
    EventBatch,
    HealthResponse,
    HitlDecision,
    PlanRequest,
)
from .services.executor import ExecutionService
from .services.planner import PlannerService
from .store import OrchestratorStore

app = FastAPI(title="ProactiveAI Local Orchestrator", version="0.1.0")

# Local development CORS policy. Tighten this before production rollout.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

store = OrchestratorStore()
planner = PlannerService()
composio_client = ComposioClient()
executor = ExecutionService(composio_client)
SUPPORTED_CONNECTORS = {"gmail", "slack", "github"}


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok")


@app.post("/v1/context/events", status_code=202)
def ingest_context_events(batch: EventBatch) -> dict:
    store.append_events(batch.userId, batch.events)
    return {
        "accepted": len(batch.events),
        "userId": batch.userId,
    }


@app.post("/v1/proactive/plan", response_model=ActionPlan)
def generate_plan(request: PlanRequest) -> ActionPlan:
    if not request.contextWindow:
        request = request.model_copy(update={"contextWindow": store.recent_events(request.userId)})

    plan = planner.build_plan(request)
    store.save_plan(plan)
    return plan


@app.post("/v1/actions/execute", response_model=ActionExecutionResult)
def execute_action(request: ActionExecutionRequest) -> ActionExecutionResult:
    plan = store.get_plan(request.planId)
    if plan is None:
        raise HTTPException(status_code=404, detail=f"Unknown planId={request.planId}")

    decision = store.get_decision(request.planId)
    return executor.execute(plan, request.stepId, decision)


@app.post("/v1/hitl/decision")
def submit_decision(decision: HitlDecision) -> dict:
    plan = store.get_plan(decision.planId)
    if plan is None:
        raise HTTPException(status_code=404, detail=f"Unknown planId={decision.planId}")

    store.save_decision(decision)
    return {
        "planId": decision.planId,
        "approved": decision.approved,
        "stored": True,
    }


@app.get("/v1/connectors/status", response_model=ConnectorStatusResponse)
def get_connector_status(userId: str = Query(..., description="Stable user id from client")) -> ConnectorStatusResponse:
    return ConnectorStatusResponse(
        userId=userId,
        connectors=store.list_connectors(userId),
    )


@app.post("/v1/connectors/{connector}/authorize", response_model=ConnectorAuthorizeResponse)
def authorize_connector(connector: str, request: ConnectorAuthorizeRequest) -> ConnectorAuthorizeResponse:
    normalized = connector.lower()
    if normalized not in SUPPORTED_CONNECTORS:
        raise HTTPException(status_code=404, detail=f"Unsupported connector={connector}")

    auth = composio_client.authorize(normalized, request.userId)
    status = (
        store.set_connector_connected(
            user_id=request.userId,
            connector=normalized,
            account_label=auth.account_label,
        )
        if auth.connected
        else store.set_connector_disconnected(
            user_id=request.userId,
            connector=normalized,
        )
    )
    return ConnectorAuthorizeResponse(
        userId=request.userId,
        connector=normalized,
        connected=status.connected,
        authUrl=auth.auth_url,
        message=auth.message,
    )


@app.post("/v1/connectors/{connector}/disconnect", response_model=ConnectorAuthorizeResponse)
def disconnect_connector(connector: str, request: ConnectorDisconnectRequest) -> ConnectorAuthorizeResponse:
    normalized = connector.lower()
    if normalized not in SUPPORTED_CONNECTORS:
        raise HTTPException(status_code=404, detail=f"Unsupported connector={connector}")

    disconnect = composio_client.disconnect(normalized, request.userId)
    status = store.set_connector_disconnected(
        user_id=request.userId,
        connector=normalized,
    )
    return ConnectorAuthorizeResponse(
        userId=request.userId,
        connector=normalized,
        connected=status.connected,
        authUrl=disconnect.auth_url,
        message=disconnect.message,
    )
