# ProactiveAI Extreme Mode

Single-user proactive AI stack with clear frontend/backend split.

## Repository Layout
- `android/`
  - Android app (frontend/client): permissions command center, context plugins, edge runtime integration points.
- `backend/orchestrator/`
  - Local-first orchestration backend: planning, HITL gate, action execution router.
- `cloud/openapi/`
  - API contract reference.
- `docs/`
  - Architecture, implementation, and deployment docs.

## Quick Start

### 1) Run backend locally
```bash
cd backend/orchestrator
./scripts/run_local.sh
```

### 2) Run Android app
- Open `/Users/ligenpeng/Documents/work/ProactiveAI/android` in Android Studio.
- Run app on emulator/device.
- Default backend endpoint in app is `http://10.0.2.2:8080` (for emulator).

## Deployment path
- Local iteration now.
- Migrate backend to AWS App Runner later (see [backend-apprunner-migration.md](/Users/ligenpeng/Documents/work/ProactiveAI/docs/backend-apprunner-migration.md)).
- Android-side MVP runbook is in [android-mvp-runbook.md](/Users/ligenpeng/Documents/work/ProactiveAI/docs/android-mvp-runbook.md).
