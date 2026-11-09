# M7-02: P0 wiring fixes (Migrations in the app frame, live MQTT publish, Time start mode controls)

Milestone: M7    Depends on: M5-04 (migrations screen), M6-05 (publish MQTT dialog), M3 browser    Size: M    Commit: ad6bd15 (no task branch)

## Goal
Three functional gaps found while trying the app are closed: the Migrations screen sits inside the common app frame, the Publish MQTT dialog is wired to the live connection in `desktopApp`, and the message browser's Time start mode has working controls.

## Why
M6-05 built the MQTT dialog and M5-04 the Migrations screen but `Main.kt` did not wire them fully; the Time mode could show an empty result with no way out. Spec: "MQTT support (M6)", "Topology migrations".

## Scope (what shipped)
In:
- `MigrationsScreen` rendered in `AppFrameLayout` (drawer, app bar, connection chip; new parameters `control`, `controlEvents`, `onNavigate`); banners use theme colours instead of fixed colours.
- `desktopApp/Main.kt`: `PublishMqttViewModel` built from `source.mqttPublisher` (falls back to `UnavailableMqttMessagePublisher`), new `PublishMqttEventsAdapter`, dialog state in the app; after a publish the topology and stream list refresh. The stream publish adapter now also refreshes the browser last page, the clients and the list.
- `MessageBrowserView`: Time mode shows `TimestampSeekControls` (epoch ms field, Jump, presets -5m, -1h, -24h); the entry default into Time mode is now "one hour ago" (was "now"); the "No messages at or after timestamp" state keeps the controls and adds a "Browse from first offset" button.
Out: engine and dialog (M6-04, M6-05).

## Acceptance
- `ui/.../migrations/MigrationsUiTest`: "MigrationsScreen renders inside AppFrame with navigation drawer items".
- `ui/.../streams/MessageBrowserUiTest`: "no messages at or after time state keeps navigation controls and recovery button visible".
Not covered by a test: the MQTT wiring in `Main.kt` (no test builds `desktopApp`), the -5m/-1h/-24h presets, the one-hour default, the refresh after publish.

## Design notes
`PublishMqttEventsAdapter` mirrors `PublishEventsAdapter`; presets read `System.currentTimeMillis()` in the view (not injectable; flagged as a smell for a follow-up).

## UX
`docs/ux/` has no Time-mode screen; styling follows the browser controls card.

## MCP
None: wiring and UI only.

## Non-goals
Any change to paging rules.

## Open points
Time presets use the machine clock, not the broker clock.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
