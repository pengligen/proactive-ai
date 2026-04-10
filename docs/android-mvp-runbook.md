# Android MVP Runbook (Extreme Mode)

## Implemented app-side modules
- Permission Command Center
  - real runtime permission requests
  - settings-gated permission navigation (usage stats, notification listener, background location)
  - plugin-level enable/disable toggles with persistence
- Foreground collection service
  - always-on collection loop with persistent notification
  - heartbeat events when plugin output is empty
  - boot restore behavior
- Real context plugins (v1)
  - location + inferred motion from last known location speed
  - app usage foreground package tracking
  - media library delta detection (images/videos/audio)
  - ambient audio level probe
  - step counter health signal (when body sensor permission is granted)
  - nearby connectivity state (wifi/cellular/vpn/bluetooth)
- Notification capture service
  - NotificationListenerService writes notification events directly to local queue
  - immediate sync trigger after notification ingestion
- Local event queue
  - SQLite-based `context_events` queue
  - unsynced tracking, TTL pruning, sync marking
- Backend sync integration
  - WorkManager periodic and immediate sync jobs
  - pushes unsynced event batches to orchestrator
  - builds plan request with recent local context window (not empty payload)
  - optional low-risk step auto-execute path (policy-gated)
- Action Center (HITL + execution loop)
  - on-device inference summary (`intent hints`, urgency score, suggested actions)
  - pluggable on-device inference strategy layer (fast 2B heuristic / deep 4B heuristic)
  - edge model profile selection (`Gemma Effective 2B` / `Gemma Effective 4B`) drives strategy switching
  - inference test dialog for prompt-based local strategy validation
  - native on-device model runtime path via MediaPipe LLM Inference (`tasks-genai`)
  - model config dialog (`enable native` + `2B/4B .task path`)
  - native runtime auto-fallback to heuristic when model file/runtime is unavailable
  - model hub download panel:
    - accepts arbitrary Hugging Face direct URL
    - optional HF token for gated repos
    - downloads model to app local storage and binds to chosen profile (`2B`/`4B`)
  - cloud plan fetch with full plan details (goal/risk/why/steps)
  - step-level approve/deny submission to `/v1/hitl/decision`
  - step-level execution via `/v1/actions/execute`
- Connector Center
  - backend-driven connector status for Gmail/Slack/GitHub
  - connect/disconnect actions wired to orchestrator connector endpoints
- Action History
  - local SQLite log for plan generation, HITL decisions, and execution outcomes
  - in-app timeline view with filter cycle (`All/Plan/HITL/Execution/Queue`)
  - local text export for offline review
- Action Execution Queue (reliable execution path)
  - step execution goes through local queue, not fire-and-forget direct call
  - periodic + immediate WorkManager worker drains queue
  - exponential backoff retries for failed actions
  - manual retry and clear-finished controls in UI
- Evaluation metrics (local)
  - intent match rate
  - action helpfulness
  - interruption quality

## Key files
- [`MainActivity.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/MainActivity.kt)
- [`PermissionCommandCenterScreen.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/ui/permission/PermissionCommandCenterScreen.kt)
- [`PermissionCommandCenterState.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/ui/permission/PermissionCommandCenterState.kt)
- [`PermissionStatusResolver.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/permission/PermissionStatusResolver.kt)
- [`OnDeviceInferenceEngine.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/core/edge/OnDeviceInferenceEngine.kt)
- [`OrchestratorGateway.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/orchestrator/OrchestratorGateway.kt)
- [`OrchestratorModels.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/orchestrator/OrchestratorModels.kt)
- [`ProactiveCollectionService.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/service/ProactiveCollectionService.kt)
- [`NotificationCaptureService.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/service/NotificationCaptureService.kt)
- [`ContextEventStore.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/storage/ContextEventStore.kt)
- [`ActionHistoryStore.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/storage/ActionHistoryStore.kt)
- [`ActionQueueStore.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/storage/ActionQueueStore.kt)
- [`ActionExecutionWorker.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/sync/ActionExecutionWorker.kt)
- [`ActionExecutionScheduler.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/sync/ActionExecutionScheduler.kt)
- [`OrchestratorSyncWorker.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/sync/OrchestratorSyncWorker.kt)
- [`AppPrefs.kt`](/Users/ligenpeng/Documents/work/ProactiveAI/android/app/src/main/java/com/proactiveai/extreme/app/AppPrefs.kt)

## Manual test checklist
1. Launch app on emulator.
2. Tap `Grant All Wizard` and grant runtime permissions.
3. Tap `Next Settings` repeatedly to go through settings-gated permissions.
4. Tap `Start Service`.
5. Confirm persistent foreground notification appears.
6. Enable notification listener access when prompted in system settings.
7. Tap `Ping Backend` and verify status changes to `Healthy` when backend is running.
8. Tap `Sync Now` and verify queued event count eventually decreases.
9. Trigger a test phone notification and confirm queued events increase.
10. In `Action Center`, choose edge model profile (`2B` or `4B`).
10.0 In `Model Hub Download`, paste a Hugging Face model URL and tap `Download & Use`.
    - optional: fill HF token for gated models
    - choose target profile (`Target 2B` / `Target 4B`)
10.1 Tap `Model Config`, turn on native model, and set model file paths.
10.2 Place model files on device (example):
    ```bash
    adb shell mkdir -p /data/local/tmp/llm
    adb push /absolute/path/to/gemma3n_e2b.task /data/local/tmp/llm/gemma3n_e2b.task
    adb push /absolute/path/to/gemma3n_e4b.task /data/local/tmp/llm/gemma3n_e4b.task
    ```
11. Tap `Generate Plan` and confirm:
    - local inference summary and intent hints update
    - plan goal/risk/steps render
11.1 Tap `Inference Test` and input a free-form scenario sentence; confirm local strategy output updates immediately.
11.2 Switch model profile between `2B` and `4B`; run `Inference Test` again and verify strategy label/summary changes.
11.3 Verify `Native status` in Action Center:
    - success path: shows native model used
    - fallback path: shows explicit fallback reason
12. For a step with confirmation mode, tap `Approve` or `Deny` and verify status updates.
13. Tap `Execute Step` and verify execution result text appears.
14. Tap `Run Auto Steps` and verify low-risk steps execute.
15. Confirm metrics row updates after approvals/executions.
16. In `Connector Center`, tap `Refresh` and verify Gmail/Slack/GitHub rows appear.
17. Tap `Connect` on one connector and verify status turns `Connected`.
18. Tap `Disconnect` and verify state rolls back.
19. Confirm `Action History` shows PLAN/HITL/EXECUTION entries while testing flows.
20. Tap `Execute Step` in Action Center and verify queue row appears in `Execution Queue`.
21. Tap `Run Now` in queue card and verify status transitions (`PENDING/RUNNING/SUCCEEDED` or `FAILED`).
22. For failed rows, tap `Retry` and confirm attempt counter increments.
23. In history card, cycle filters and verify event subset changes.
24. Tap `Export` and verify export path is shown in card text.
