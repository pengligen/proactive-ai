# Local Dev Wiring (Android + Local Orchestrator)

## Backend
1. Start backend:
   ```bash
   cd /Users/ligenpeng/Documents/work/ProactiveAI/backend/orchestrator
   ./scripts/run_local.sh
   ```
2. Validate health:
   ```bash
   curl -sS http://localhost:8080/health
   ```
3. Run full API smoke:
   ```bash
   ./scripts/smoke_test.sh
   ```

## Android Emulator
- Emulator should call host machine via `10.0.2.2`.
- Current app config already points to:
  - `http://10.0.2.2:8080`

## Android Physical Device
- Replace endpoint with your machine LAN IP and keep backend bound to `0.0.0.0`.
- Ensure phone and laptop are on same network.

## Suggested smoke test
1. Launch app and confirm top card shows orchestrator URL.
2. Send a sample `EventBatchPayload` through `HttpOrchestratorGateway` from a background worker.
3. Call `requestPlan(userId)` and verify non-empty plan response.
