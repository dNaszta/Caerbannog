# Shared UI components between Streams and Topology

Milestone: M2    Depends on: M2-08c, M2-08f1 (merged)    Size: S

## Goal

Extract duplicate and cross-package UI components (`SearchField`, `CenteredMessage`, `CopyableText`, `LostConnectionBanner`) into a neutral `dev.caerbannog.ui.components` package, removing the dependency of `topology` on `streams` and keeping UI presentation and tests byte-for-byte compatible.

## Why

Addresses review finding 1, 2, and 3 from M2-08c step 5 (`docs/tasks/M2-08-topology-read-only.md`):
1. `TopologySearchField` was a 35-line copy of `StreamsSearchField`, copied originally because another branch was modifying `ui/streams`. Both branches are merged on `main`.
2. `TopologyMessage` and `StreamsScreen.Message` duplicate centred messaging with icon/tag differences.
3. `TopologyScreen`, `TopologyTable`, and `TopologyQueueDetail` imported `CopyableText` and `LostConnectionBanner` directly from `dev.caerbannog.ui.streams`, coupling topology to streams.
4. Clean up fully-qualified `androidx.compose.ui.text.style.TextAlign` in `TopologyScreen.kt:151`.

## Scope

In:
- `ui`:
  - Create package `dev.caerbannog.ui.components`:
    - `SearchField.kt`: composable `SearchField(value, placeholder, tag, onSearch, minWidth = 280.dp, maxWidth = 480.dp)`.
    - `CenteredMessage.kt`: composable `CenteredMessage(text, icon = null, modifier = Modifier.fillMaxSize(), tag = null, action = null)`.
    - `CopyableText.kt`: moved from `dev.caerbannog.ui.streams.StreamsTable.kt`.
    - `LostConnectionBanner.kt`: moved from `dev.caerbannog.ui.streams.StreamsScreen.kt`.
  - Refactor `dev.caerbannog.ui.streams`:
    - `StreamsSearchField` delegates to `SearchField` with tag `"streamsSearch"`, min 320.dp, max 480.dp.
    - `Message` delegates to `CenteredMessage`.
    - Update references to `CopyableText` and `LostConnectionBanner`.
  - Refactor `dev.caerbannog.ui.topology`:
    - `TopologySearchField` delegates to `SearchField` with min 280.dp, max 400.dp.
    - `TopologyMessage` delegates to `CenteredMessage(..., tag = "topologyMessage")`.
    - Import `CopyableText` and `LostConnectionBanner` from `dev.caerbannog.ui.components`.
    - Clean up inline `androidx.compose.ui.text.style.TextAlign.Center` with clean import.
Out:
- No visual or behavioral changes; existing unit, Compose UI, screenshot, and acceptance tests remain untouched and must pass identically.
- No changes to `core`, `data-rabbit`, or `desktopApp` logic.

## MCP Evaluation

None. This is an internal UI component refactoring. Caerbannog's headless `:mcp-server` has no UI dependencies and does not expose UI components.

## Acceptance

- All existing Compose UI tests in `ui:jvmTest` continue to pass without modification.
- Topology no longer imports anything from `dev.caerbannog.ui.streams`.
- No duplicated `SearchField` or `CenteredMessage` implementations.
- Full test suite `./gradlew test` passes.

## Definition of done

- Step 1 brief written and committed.
- Step 2 guard baseline verified.
- Step 3 build completed (components extracted and wired).
- Step 4 verified (all test suites pass, zero behavior changes).
- Step 5 architect review completed.
- Status and README row updated.

## Status

Step 1: brief written by solution-architect (2026-11-06). Ready for step 2 (test-developer).
Branch: `m2-08f2-shared-components`
Round: 0
Verdict: in progress
