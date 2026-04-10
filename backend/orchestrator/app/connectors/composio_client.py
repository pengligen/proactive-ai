from __future__ import annotations

from dataclasses import dataclass
import os
from typing import Any, Dict


@dataclass
class ConnectorExecutionResult:
    ok: bool
    detail: str


@dataclass
class ConnectorAuthorizeResult:
    connected: bool
    account_label: str | None
    auth_url: str | None
    message: str


class ComposioClient:
    """Local-first Composio adapter.

    Modes:
    - `stub_connected` (default): immediately marks connector connected.
    - `oauth_link_only`: returns auth URL and waits for external completion.
    """

    def __init__(self) -> None:
        self._mode = os.getenv("COMPOSIO_MODE", "stub_connected").strip().lower()
        self._auth_base_url = os.getenv("COMPOSIO_AUTH_BASE_URL", "https://auth.local").rstrip("/")

    def execute(self, connector: str, operation: str, args: Dict[str, Any]) -> ConnectorExecutionResult:
        if connector.lower() not in {"gmail", "slack", "github", "local"}:
            return ConnectorExecutionResult(ok=False, detail=f"Unsupported connector: {connector}")

        return ConnectorExecutionResult(
            ok=True,
            detail=f"Executed {connector}.{operation} with args keys={list(args.keys())}",
        )

    def authorize(self, connector: str, user_id: str) -> ConnectorAuthorizeResult:
        normalized = connector.lower()
        auth_url = f"{self._auth_base_url}/{normalized}?userId={user_id}"

        if self._mode == "oauth_link_only":
            return ConnectorAuthorizeResult(
                connected=False,
                account_label=None,
                auth_url=auth_url,
                message=f"Open auth URL to finish {normalized} authorization.",
            )

        return ConnectorAuthorizeResult(
            connected=True,
            account_label=f"{normalized}-linked-account",
            auth_url=auth_url,
            message=f"{normalized} connected for local prototyping.",
        )

    def disconnect(self, connector: str, user_id: str) -> ConnectorAuthorizeResult:
        normalized = connector.lower()
        return ConnectorAuthorizeResult(
            connected=False,
            account_label=None,
            auth_url=None,
            message=f"{normalized} disconnected for user {user_id}.",
        )
