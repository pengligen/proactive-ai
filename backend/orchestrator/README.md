# ProactiveAI Local Orchestrator

Local backend for proactive planning and action orchestration.

## Run locally

```bash
cd backend/orchestrator
./scripts/run_local.sh
```

Then test:

```bash
curl -sS http://localhost:8080/health
```

Optional end-to-end smoke test:

```bash
./scripts/smoke_test.sh
```

Connector modes (optional):

```bash
# default: immediately mark connector connected (fast local iteration)
export COMPOSIO_MODE=stub_connected

# link-only: return authUrl and keep connector disconnected until callback wiring is added
export COMPOSIO_MODE=oauth_link_only
export COMPOSIO_AUTH_BASE_URL=https://your-auth-host/connect
```

## API summary
- `POST /v1/context/events`
- `POST /v1/proactive/plan`
- `POST /v1/actions/execute`
- `POST /v1/hitl/decision`
- `GET /v1/connectors/status?userId=...`
- `POST /v1/connectors/{connector}/authorize`
- `POST /v1/connectors/{connector}/disconnect`

## Notes
- This service currently uses in-memory store for fast prototyping.
- Composio execution is currently stubbed in `app/connectors/composio_client.py`.
- Connector authorization endpoints are local stubs for iteration; they emulate
  Gmail/Slack/GitHub connected state until real Composio OAuth wiring is added.
- AWS migration guide is in [`docs/backend-apprunner-migration.md`](/Users/ligenpeng/Documents/work/ProactiveAI/docs/backend-apprunner-migration.md).
