# Command Center UI Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify the Android command center UI into a cleaner, more elegant mobile-first control surface without changing behavior.

**Architecture:** Keep all behavior in place and concentrate changes in the Compose shell, shared UI helpers, and the major card sections inside the existing screen file. Promote reuse for button grids and shared visual styling so the same look applies across tabs.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, Android Gradle unit tests

---

### Task 1: Document And Lock Scope

**Files:**
- Create: `docs/superpowers/specs/2026-04-16-command-center-ui-design.md`
- Create: `docs/superpowers/plans/2026-04-16-command-center-ui-refinement.md`

- [ ] **Step 1: Write the design doc**

Document the approved direction: command-center visual style, app-shell refinement, standardized cards, shared action grid, and verification scope.

- [ ] **Step 2: Write the implementation plan**

Capture the UI-only execution path so later edits stay out of business logic.

### Task 2: Establish Shared UI Styling

**Files:**
- Modify: `android/app/src/main/java/com/proactiveai/extreme/ui/theme/Color.kt`
- Modify: `android/app/src/main/java/com/proactiveai/extreme/ui/theme/Theme.kt`
- Modify: `android/app/src/main/java/com/proactiveai/extreme/ui/permission/SummaryActionLayout.kt`

- [ ] **Step 1: Add a broader command-center palette and reusable action/button styling helpers**
- [ ] **Step 2: Keep the action grid responsive at two columns with button hierarchy support**
- [ ] **Step 3: Run the compact-grid unit test**

Run: `cd android && ./gradlew app:testDebugUnitTest --tests com.proactiveai.extreme.ui.permission.SummaryActionLayoutTest`

Expected: `BUILD SUCCESSFUL`

### Task 3: Refine The App Shell

**Files:**
- Modify: `android/app/src/main/java/com/proactiveai/extreme/ui/ProactiveExtremeApp.kt`

- [ ] **Step 1: Upgrade top bar spacing, title styling, and surrounding scaffold colors**
- [ ] **Step 2: Improve bottom navigation selected-state clarity and overall spacing**
- [ ] **Step 3: Keep tab switching behavior unchanged**

### Task 4: Standardize Major Cards

**Files:**
- Modify: `android/app/src/main/java/com/proactiveai/extreme/ui/permission/PermissionCommandCenterScreen.kt`

- [ ] **Step 1: Introduce reusable card/header/status helpers where they reduce duplication**
- [ ] **Step 2: Apply the new structure to permissions summary and model management**
- [ ] **Step 3: Apply the same structure to action center, audio/context controls, and execution queue**
- [ ] **Step 4: Preserve all action wiring and existing tab content**

### Task 5: Verify Build Stability

**Files:**
- Modify: `android/app/src/test/java/com/proactiveai/extreme/ui/permission/SummaryActionLayoutTest.kt`

- [ ] **Step 1: Add or update any small layout helper tests needed by the refactor**
- [ ] **Step 2: Run focused unit tests**

Run: `cd android && ./gradlew app:testDebugUnitTest --tests com.proactiveai.extreme.ui.permission.SummaryActionLayoutTest`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run a broader UI compile verification**

Run: `cd android && ./gradlew app:testDebugUnitTest`

Expected: `BUILD SUCCESSFUL`
