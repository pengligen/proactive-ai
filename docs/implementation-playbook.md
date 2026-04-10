# Implementation Playbook (Extreme Mode v1)

## 1) Repository structure

```text
ProactiveAI/
  android/
    app/
      src/main/
        java/com/proactiveai/extreme/
          core/
            context/
            model/
            policy/
          data/
          ui/
    build.gradle.kts
    settings.gradle.kts
  backend/
    orchestrator/
      app/
      Dockerfile
      apprunner.yaml
  cloud/
    openapi/orchestrator-v1.yaml
  docs/
    extreme-mode-v1-blueprint.md
    permission-matrix.md
    implementation-playbook.md
```

## 2) Module contracts

### Android
- `ContextPlugin`
  - `start()` initialize collection resources.
  - `stop()` release resources.
  - `poll()` emit normalized events.
- `ContextEngine`
  - manages plugin lifecycle based on enabled plugin IDs.
  - merges all plugin events into one sorted tick output.
- `ActionPolicy`
  - input: action risk + connector sensitivity.
  - output: `AUTO_EXECUTE | REQUIRE_CONFIRMATION | BLOCK`.
- `PermissionCommandCenterState`
  - tracks plugin enabled state, grant status, readiness score.
  - provides `grantAllWizardSimulated()` and `revokeAll()` for extreme experiments.

### Cloud
- `POST /v1/context/events`
  - consume event batches.
- `POST /v1/proactive/plan`
  - generate next best action plan.
- `POST /v1/actions/execute`
  - perform connector action.
- `POST /v1/hitl/decision`
  - apply user approval/denial.
- Runtime implementation lives in `backend/orchestrator/app`.

## 3) Core data structures

### On-device
- `ContextEvent`
  - source, category, summary, payload, sensitivity, ttl.
- `IntentHint`
  - model-inferred likely intent and confidence.
- `PluginDescriptor`
  - plugin metadata and required permissions.
- `PermissionDescriptor`
  - permission gate type, purpose, retention default.

### Orchestrator
- `EventBatch`
- `PlanRequest`
- `ActionPlan`
- `ActionExecutionRequest`
- `HitlDecision`

## 4) Development order

### Milestone A: Device foundation (current)
- Android skeleton.
- Permission command center.
- Plugin interfaces and event schema.

### Milestone B: Real collectors
- Replace plugin stubs with real collectors:
  - location + activity recognition
  - notification listener + usage stats
  - media metadata indexer
  - health connect sync
- Add foreground service for continuous high-frequency collection.

### Milestone C: Edge intelligence
- Integrate Gemma runtime selector (2B/4B).
- Add private summarization and intent extraction pipeline.
- Write local encrypted memory and retrieval layer.

### Milestone D: Cloud action loop
- Implement orchestrator APIs from OpenAPI contract.
- Connect Composio for Gmail/Slack/GitHub.
- Execute low-risk actions automatically.

### Milestone E: HITL and evaluation
- Add approval UI for medium/high risk actions.
- Track intent-match and action-helpfulness metrics.
- Rank permissions by value density to support later permission pruning.

## 5) Immediate next coding tasks
- Implement `Grant All Wizard` real runtime permission launcher.
- Add settings deep-links for usage stats and notification listener.
- Add local event queue storage (Room).
- Add foreground service skeleton for always-on collection pipeline.
