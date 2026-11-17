# Vhost home caption in Topology screen

Milestone: M2    Depends on: M2-08c (merged)    Size: S

## Goal

The Topology virtual host menu highlights the connection's own virtual host by displaying the caption "This connection's virtual host" under its entry.

## Why

Addresses review finding 1 and ruling 1 from M2-08c step 5 (`docs/tasks/M2-08-topology-read-only.md`). When a user has access to many virtual hosts, showing which entry is the connection's home virtual host provides immediate context.

## Scope

In:
- `core`:
  - `TopologyState.homeVhost: String? = null`, set from the connection's configured `virtualHost` when present in `vhosts`, otherwise `null` (and `null` for no-tag / CannotRead).
  - `TopologyTexts.HOME_VHOST_CAPTION: String = "This connection's virtual host"`.
  - Unit test in `TopologyViewModelVhostTest`: `homeVhost` set when offered, null when not offered, null for no-tag.
- `ui`:
  - `TopologyVhostSwitch`: `VhostSwitch` passes `homeVhost` to `VhostMenu`. Under the entry matching `homeVhost`, render a second line with `TopologyTexts.HOME_VHOST_CAPTION` using `labelSmall` and `onSurfaceVariant` color.
  - Accessibility: Include the caption in the menu item's semantics/content description.
  - UI Compose test in `TopologyVhostSwitchUiTest` asserting the caption text and semantics.
Out:
- No Gherkin scenario changes (UX layout detail within existing vhost scenarios).
- No new network calls or state machines.

## Acceptance

- Unit: `TopologyViewModelVhostTest` proves `homeVhost` is set when home is in the offered vhosts, null when omitted, null for no-tag.
- Compose: `TopologyVhostSwitchUiTest` proves the caption is rendered under the home virtual host and included in accessibility description; non-home entries do not have the caption.
- Existing tests and screenshot tests continue to pass.

## Design notes

- Core: `TopologyViewModel` sets `homeVhost = if (vhosts.contains(home)) home else null`.
- UI: `VhostSwitch(current: String, vhosts: List<String>, homeVhost: String?, onSelect: (String) -> Unit)`.

## UX

Per mockup `docs/ux/Topology.dc.html` `vhost-menu`:
```html
<div class="vhost-item">
  <div class="vhost-name">/</div>
  <div class="vhost-caption">This connection's virtual host</div>
</div>
```

## MCP

None (UI/view-model display detail; MCP tools already report the profile's virtual host directly).

## Open points

None.

## Definition of done

- Tests written first (red for the right reason).
- Implementation passes all unit and Compose tests.
- Step 4 verify and Step 5 architect review completed.
- Status and README row updated.

## Status
- Reconciled 2026-11-17: this task is merged into `main` (c04f092, 2026-11-06); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.

Step 1: brief written by solution-architect (2026-11-06).
Step 2: tests first done (test-developer, 2026-11-06). Added unit tests in `TopologyViewModelVhostTest` asserting `homeVhost` and Compose UI test in `TopologyVhostSwitchUiTest` asserting caption text and semantics. Confirmed red on assertions (commit `e7a6693`).
Step 3: build done (senior-developer, 2026-11-06). Implemented `homeVhost` in `TopologyViewModel.kt`, passed to `VhostSwitch` in `TopologyControls.kt`, and rendered caption and accessibility descriptions in `TopologyVhostSwitch.kt`. All unit and Compose tests green (commit `53f5db1`).
Step 4: verified (test-developer, 2026-11-06).
- Automated test runs:
  - `:core:jvmTest`: all 25 tests pass in `TopologyViewModelVhostTest`.
  - `:ui:jvmTest`: all 10 tests pass in `TopologyVhostSwitchUiTest`.
  - Full suite `:core:jvmTest :ui:jvmTest` passed green.
  - `:desktopApp:test` and `:acceptance-tests:test` passed green.
- Exploratory verification:
  - Visual layout verified against `docs/ux/Topology.dc.html` (`vhost-item` with name and caption).
  - Caption styled with `labelSmall` and `onSurfaceVariant` under monospace vhost name.
  - Non-home entries never display caption.
  - Keyboard navigation (Down/Up/Escape/Enter/Space), popup dismissal, and window resizing intact.
  - Accessibility `contentDescription` properly set.
Step 5: architect review (solution-architect, 2026-11-06): ACCEPTED.
- Fit: Closes M2-08c step 5 review finding 1 / ruling 1 cleanly.
- Architecture: Clean pure display state `TopologyState.homeVhost`, no extra network calls, no coupling.
- Safety & Rules: Quorum safety and read-only invariants unaffected. MCP tool evaluation confirmed unnecessary for this UX detail.
- Code quality: Idiomatic Compose and clean unit/Compose testing.

Branch: `m2-08f1-vhost-home-caption` (merged into local `main`, not pushed)
Round: 0
Verdict: accepted, merged
