# Command Center UI Refinement Design

## Goal

Refine the Android command center UI so the four tabs feel like one coherent product surface instead of a collection of utility panels. The update must preserve the existing information architecture and behavior while improving hierarchy, spacing, responsiveness, and visual polish on narrow mobile screens.

## Constraints

- Keep all current actions and data visible; do not remove workflows.
- Avoid touching business logic, state persistence, networking, or orchestration behavior.
- Follow existing Compose structure where practical; targeted helper extraction is acceptable.
- Optimize first for phone-width layouts.

## Visual Direction

Use a compact command-center style:

- Soft slate background instead of flat light gray.
- Consistent card shell with rounded corners, subtle border, and controlled elevation.
- Strong title hierarchy and muted supporting text.
- Primary actions use filled buttons; secondary actions use outlined buttons in a consistent two-column compact grid.
- Status values should read like signals rather than logs via tone, chip-like blocks, or concise highlighted rows.

## Layout Changes

### App Shell

- Upgrade the top bar into a clearer header with title and lightweight supporting context.
- Make the bottom navigation feel intentional through better spacing, container color, and selected-state emphasis.

### Cards

- Standardize cards around four sections when applicable:
  - header
  - support text / summary
  - signal or status rows
  - action grid
- Reuse a single compact action grid helper for narrow-width button groups.
- Normalize inner padding and vertical spacing across tabs.

### Information Presentation

- Convert high-value state rows such as backend health, service state, sync mode, and configuration readiness into clearer visual groupings.
- Preserve dense diagnostic text, but visually demote it below primary signals and actions.
- Keep long values readable with muted styling and controlled wrapping.

## Scope

This pass covers:

- `ProactiveExtremeApp` shell
- shared action grid helper
- summary card on `Permissions`
- model management and action center sections
- context cards with dense action rows
- assistant queue/history areas where controls currently feel crowded

This pass does not cover:

- feature additions
- navigation structure changes
- copy rewrites beyond minor UI-label tightening
- business-state or data-model refactors

## Verification

- Existing unit tests for compact action grouping remain green.
- Android unit test task compiles the touched UI sources successfully.
- No action labels collapse into per-character vertical wrapping at phone widths.
