# Extreme Mode v1 Blueprint

## 1. Product Principle
- Objective: build a `24x7 proactive second brain` for one user.
- Strategy: collect maximal context first, then prune permissions by measured value.
- Constraint: no hidden collection; background continuous collection must use foreground service and visible disclosure.

## 2. System Topology

### 2.1 Runtime split
- Device runtime (private first):
  - Context ingestion and fusion.
  - Edge inference (Gemma 2B/4B selectable).
  - Local encrypted memory.
  - Permission/policy center.
- Cloud runtime (orchestration first):
  - Multi-step planning and decision policy.
  - Deep research worker.
  - Composio connector execution.
  - HITL gate and approval flows.

### 2.2 Event flow
1. Sensor/app/connectors generate events.
2. On-device normalizer converts to `ContextEvent` schema.
3. Edge model produces `IntentHints`, `Urgency`, `SuggestedActions`.
4. Minimal required payload synced to cloud orchestrator.
5. Orchestrator returns `ActionPlan` with risk scores.
6. Policy engine chooses auto-execute or HITL confirmation.
7. Execution feedback logged for model and policy tuning.

## 3. Modules

### 3.1 Android modules (current skeleton in `android/app`)
- `ui/permission`: Permission Command Center.
- `core/model`: permissions, plugin capability, risk, storage policy.
- `core/context`: context event contracts and plugin lifecycle.
- `core/context/plugins`: plugin placeholders:
  - LocationMotionPlugin
  - AudioAmbientPlugin
  - HealthConnectPlugin
  - NotificationUsagePlugin
  - MediaNotebookPlugin

### 3.2 Cloud modules (contract draft in `cloud/openapi`)
- `context-ingest`: accepts normalized event batches.
- `planner`: generate proactive suggestion and action plan.
- `research`: async deep research preparation.
- `action-router`: executes Composio-powered actions.
- `hitl`: approval and overrides.

## 4. Key Data Contracts

### 4.1 ContextEvent (device -> cloud)
- `eventId`: unique id.
- `occurredAt`: epoch millis.
- `source`: sensor/plugin/connector source.
- `category`: location, health, device_state, communication, media, task.
- `summary`: local model generated summary.
- `payload`: structured key-value map.
- `sensitivity`: low/medium/high.
- `ttlSeconds`: retention policy.

### 4.2 ActionPlan (cloud -> device)
- `planId`.
- `goal`.
- `steps[]`: discrete actions.
- `requiresUserConfirmation`.
- `riskLevel`.
- `explainWhy`: reason and evidence.
- `fallback`.

### 4.3 FeedbackEvent
- `suggestionId`.
- `userVerdict`: hit/miss/partial.
- `valueScore`: 1-5.
- `executionOutcome`: success/failure/cancelled.
- `notes`.

## 5. Permission & Capability Matrix (Extreme baseline)
- Location: precise, coarse, background.
- Motion: activity recognition.
- Audio: microphone capture (foreground service + persistent notification).
- Health: Health Connect data types (steps/sleep/hr/etc per user grant).
- Communication: contacts, calendar, optionally call/sms (if distribution policy allows).
- Media & files: photo/video/audio/file access.
- Notifications: notification listener authorization.
- Usage context: usage access stats and app state.
- Nearby/device: bluetooth scan/connect and nearby devices.
- Network/background: boot completed + foreground service for continuous pipelines.

## 6. Authorization UX Flow
1. Enter `Extreme Mode` onboarding.
2. Explain four modes:
   - Max capture.
   - Balanced.
   - Private-first.
   - Custom.
3. On selecting max capture, launch `Grant All Wizard`:
   - Runtime permission batch prompts.
   - Guided deep-links for settings-only permissions (usage stats, notification access, battery optimization exemption, health connect).
4. After completion, show readiness score and missing items.
5. Keep `Master Kill Switch` always accessible in notification + app home.

## 7. Policy Rules (v1)
- Low risk action: auto-execute allowed.
- Medium risk: default HITL approval.
- High/critical risk: mandatory HITL + reason display.
- Sensitive payloads: summarize on-device, upload minimal fields only.

## 8. Evaluation Framework
- Intent Match Rate: accepted proactive suggestions / total suggestions.
- Action Helpfulness: successful high-value actions / total executed actions.
- Lead Time Gain: minutes saved before user requested the task.
- Interruption Quality: positive interactions / total interruptions.
- Permission Value Density: value generated per permission category.

## 9. Weekly Execution Plan
- Week 1:
  - Android skeleton + Permission Command Center.
  - Context event schema and local queue.
- Week 2:
  - Edge model runtime integration (Gemma 2B first).
  - On-device summarization and intent hints.
- Week 3:
  - Cloud orchestrator MVP + action planner.
  - Composio connectors: Gmail, Slack, GitHub.
- Week 4:
  - HITL flow + policy engine.
  - Metrics dashboard + permission value analysis.

## 10. Scope Notes
- This blueprint intentionally prioritizes measurable product value over immediate Play Store compliance packaging.
- For broad release, a compliance pass is mandatory before production publication.
