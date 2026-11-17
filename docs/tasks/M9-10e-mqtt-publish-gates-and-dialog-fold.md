# Write-permission gate on the older MQTT entry points, and the fold check of the AMQP Publish dialog

Milestone: M9 (follow-up)    Depends on: M9-10b (merged)    Size: S

Proposed 2026-11-17 by the architect at step 5 of `M9-10b`. QUEUED (owner, 2026-11-17, "accept all", spec O18). Brief made exact at step 1 on 2026-11-17 (below, "Step 1 record").

## Status
Step 3 done (senior developer, 2026-11-17): built test-first, no test edited. `./gradlew test --continue` green (core 3333, ui 1240, desktopApp 194, acceptance 2008 runs with 1234 skipped as before; feature 15: 17 runs, 0 failed). Ready for step 4 (verification) and review. Banner max height kept at 136 dp (test developer's hint; the brief proposed 160 dp).

Earlier:
Step 2 done (test developer, 2026-11-17): tests written and red for the right reason, no production code. API fixed by the tests, see "Step 2 record" at the end. AMQP `PublishTestMessageDialog` fold check: FAILS (red by assertion, real bug).

## Goal
Every control that opens the Publish MQTT dialog respects the profile's declared write permission the way "Send a test message" does, the AMQP Publish dialog keeps Publish and Cancel on screen in a small window, and an error in the Publish MQTT dialog can never squeeze the body out.

## Why
CLAUDE.md safety rule: topology changes and publishing respect the profile's declared permissions. `M9-10b` step 1 found that the older MQTT entry points are gated only by the MQTT port (`offersMqttPublishing` in `desktopApp/.../StreamWiring.kt`), not by the write permission. The text exists since `M9-10b`: `core.publish.noWritePermission(vhost)`. `PublishTestMessageDialog` (AMQP) was not checked when the MQTT dialog got a pinned footer. Spec: O18 item 6; "Test publishing" rules of D9a and the M7 text on write permission.

## Scope
In:
1. **The gate, four controls** (the brief said three screens; the stream screen has two controls). All four currently exist only when the profile has an MQTT port:
   - stream screen header button, tag `publishMqttStreamButton` (`StreamScreen.kt`, `ClientsActions`);
   - stream screen route pill, tag `publishMqttRouteButton:{routingKey}` (`StreamScreen.kt`);
   - stream pipeline graph MQTT route node, tag `pipelineNode:{id}` of kind `MqttRoute` (`StreamPipelineGraphView.kt`, `PipelineNodeCard`);
   - topology screen, MQTT tab, tag `publishMqttButton` (`TopologyControls.kt`).
   Rule, in this order (same as `MqttTestMessage.offer`): no MQTT port: the control stays ABSENT as today (behaviour unchanged, it is not part of this task); MQTT port but publishing not enabled: the control is shown DISABLED with `noWritePermission(vhost)`; else enabled. "Publishing not enabled" is `done?.result?.publishingEnabled ?: false`, so an unfinished or failed check counts as no permission (same expression as `canWrite` in `Main.kt` and `MigrationsInputs.publishingEnabled`).
2. **One pure rule in `core`**, used by `MqttTestMessage.offer` and by the wiring, so the surfaces cannot disagree.
3. **AMQP `PublishTestMessageDialog` fold check.** Reading the code: the whole dialog (fields, banners AND the Cancel/Publish row) sits in one `verticalScroll` Column, so in a small window Publish scrolls out of view: expect the check to FAIL at 1000x600 with headers and a banner. The layout test decides (write it first). If it fails: same fix as `PublishMqttDialog` (body in its own scroll region with a visible scrollbar only on overflow, banners and buttons pinned below it, existing tags unchanged). If it passes: record "checked, fine" in the Status and keep the test.
4. **Publish MQTT error banner height.** In `PublishMqttDialog.kt` the pinned footer (notice, confirmation, error) is unbounded. Give the error banner (and, same code path, the confirmation) a maximum height with its own visible scrollbar (`BannerBox` gets `heightIn(max = ...)` plus a `VerticalScrollbar`, tag `publishMqttErrorScrollbar` when it overflows), so `publishMqttBody` always keeps its minimum. CLAUDE.md pitfall: the scroll sits on the text inside the banner, which is where the content overflows; do not put `weight`/`fillMax*`/ellipsis inside it.
Out: any new wording (the write text is reused); hiding instead of disabling (see Open points); the no-MQTT-port case; the topology control level logic; any new request kind; `tests/bdd/README.md` (the test developer adds the `@m9-10e` row at step 2, after the parallel M9-01b edit has merged, to avoid a conflict).

## Acceptance
- BDD, file `tests/bdd/features/15_mqtt_publishing.feature` (written at step 1, tags `@m9-10e @fake-broker`, 8 scenarios, 9 runs with the outline rows). It is the feature with the MQTT publish scenarios (the dialog scenarios of `@m9-10b` live in `21` but they test the Migrations route; the old entry points have no feature yet). The runner `RunMqttPublishingAcceptanceTest` already filters `@fake-broker and not @broker` and none of the new scenarios carries `@needs-*`, so nothing needs widening; the new steps go in a new glue class in package `dev.caerbannog.acceptance.mqtt`.
  - Publishing to MQTT is disabled without write permission on the stream screen (header button and route pill).
  - Publishing to MQTT is disabled without write permission on the topology screen.
  - Publishing to MQTT is disabled without write permission on the pipeline graph.
  - The reason names the virtual host (outline, rows `/` and `orders`).
  - An unfinished permission check disables it.
  - With write permission and an MQTT port the entry points open the dialog (regression).
  - Without an MQTT port the entry points are absent, whatever the permission (regression, unchanged behaviour).
  - A disabled entry point does not open the dialog (nothing published).
- Unit tests (`core`, `commonTest`): the shared rule, one row per cell of (hasMqtt, publishingEnabled), and `MqttTestMessage.offer` still returns the same four answers (the existing tests in `core` stay green unchanged).
- UI layout tests (`ui/src/jvmTest/.../publish/`): `PublishDialogLayoutUiTest` (new): `publishButton` and `cancelButton` of the AMQP dialog inside the window at 1280x800 and 1000x600 with 8 application properties and a 400-character error; a scrollbar is drawn on overflow. `PublishMqttDialogLayoutUiTest` (extend): a 2000-character error at 1000x600 keeps both buttons inside the window, `publishMqttBody` at least 120 dp high, and `publishMqttErrorScrollbar` drawn.
- UI tests per screen: disabled state has `assertIsNotEnabled()` and the reason in `contentDescription`, clicking calls nothing (extend `PublishMqttDialogUiTest`, `PipelineGraphUiTest`, topology tests via `TopologyTestKit`).
- `desktopApp` unit test of the new wiring functions (`StreamWiringTest`, same style as the existing ones).

## Design notes
- `core.publish` (new file `MqttPublishAccess.kt`, no planner validation file touched; M9-01b works in `core.planner` validation):
  ```kotlin
  sealed interface MqttPublishAccess {
      data object NoMqttPort : MqttPublishAccess
      data class NoWritePermission(val reason: String) : MqttPublishAccess   // reason = noWritePermission(vhost)
      data object Allowed : MqttPublishAccess
      companion object { fun of(hasMqtt: Boolean, publishingEnabled: Boolean, vhost: String): MqttPublishAccess }
  }
  ```
  `MqttTestMessage.offer` keeps its signature and maps: `NoMqttPort` to `Disabled(NO_MQTT_PORT)`, `NoWritePermission` to `Disabled(reason)`, `Allowed` to `Available` (after the `Applied` check). The mapping line is the only edit in `core.planner`.
- `desktopApp/StreamWiring.kt`: `offersMqttPublishing(source)` is replaced by `mqttPublishAccess(source, publishingEnabled)` (vhost from `source`). `streamMqttOpener(...)` and `topologyMqttOpener(...)` return `MqttEntry(open: ((String?) -> Unit)?, disabledReason: String?)`: absent = both null; disabled = `open` null and reason set; enabled = reason null. Keep the existing function names so the existing tests only gain a parameter.
- `ui`: each screen gets one new optional parameter next to the existing callback: `publishMqttDisabledReason: String? = null` (`StreamScreen`, `StreamPipelineGraphCard`, `TopologyScreen`/`TopologyControls`). Callback null and reason null = absent; callback null and reason non-null = disabled with the reason. The reason goes into `contentDescription` exactly as `publishTestMessageButton` does; existing tags stay, the disabled controls keep the same tag. The pipeline node and the route pill have no `enabled` flag: disabled = no `clickable` and `Modifier.semantics { disabled() }` so `assertIsNotEnabled()` works; draw them with the disabled alpha.
- `Main.kt`: the stream screen already computes `canWrite`; compute it the same way in the topology screen composable (line ~325) from `done?.result?.publishingEnabled ?: false` and pass it to the two openers. No second source of truth.
- Safety: this is tightening only, no new request kind, no new read path, no new dependency. The Publish MQTT view model is unchanged.

## UX
No new screen. Disabled control plus the reason as `contentDescription`, in the style of the AMQP publish button (`docs/ux/Publish.dc.html`). Error banner: scrolls inside itself above a maximum height (recommended 160 dp, about 8 lines of `bodySmall`; fix at step 2 so the 1000x600 test holds). The AMQP dialog: only if the check fails.

## MCP
None (UI-only). The MCP server has no tool that publishes over MQTT or AMQP (`mcp-server` only wires `mqttMessagePublisherProvider` for the existing tools; no change here). This task adds no capability, it only disables UI controls and fixes layout.

## Open points
- Hide or disable without permission: disabled with the reason (as the AMQP button, the stream "Publish test message" and the test message). Not a new decision: wording is the decided `noWritePermission(vhost)`. If the owner prefers hiding, the only change is `open = null, reason = null`; ask only if he objects at review.
- Visible reason text: only a `contentDescription` today (as the AMQP button; no visible tooltip). A visible tooltip would be new UX; not part of this task. NEW OPTIONAL decision for the owner: (a) keep as the AMQP button, recommended, consistent; (b) add a hover tooltip to all publish buttons in one later task. Nothing is blocked.
- No MQTT port stays absent, unlike the test message on the Migrations screen, which shows it disabled with `NO_MQTT_PORT`. Recorded inconsistency, not changed here; owner may ask for a follow-up to align.
- Error banner maximum height of 160 dp is the architect's proposal; the senior developer may change it if the 1000x600 test needs it.

## Definition of done
Tests written first and failing for the right reason, then passing; `./gradlew :core:jvmTest :ui:jvmTest :desktopApp:test :acceptance-tests:test` run; no unrelated changes; `tests/bdd/README.md` row for `@m9-10e` added; task index row updated.

## Step 1 record (architect, 2026-11-17)
- Confirmed against `docs/spec.md` (O18 item 6, nothing contradicts), CLAUDE.md (publishing respects permissions), `PublishMqttDialog` footer, `core.publish.noWritePermission`, `MqttTestMessage.offer` and `Main.kt` `canWrite`.
- Corrections to the proposal: four controls not three; no-MQTT-port stays absent; the AMQP dialog very likely fails the fold check (footer inside the scroll).
- Scenarios written: 8 in `15_mqtt_publishing.feature`; steps to bind at step 2 (glue package `dev.caerbannog.acceptance.mqtt`, real screens through the Compose UI test API like `PlannerScreens`): `the user has no write permission on {string}`, `the user may publish on {string}`, `the permission check of {string} has not finished`, `I open the stream {string} that is fed by the MQTT topic {string}`, `I open the MQTT tab of the topology screen`, the action/route-node enabled, disabled, absent and says assertions, `I try to press {string}`, `the Publish MQTT dialog is not open`, `nothing was published over MQTT`.

## Step 2 record (test developer, 2026-11-17)
- Red: compile errors on the missing API (`MqttPublishAccess`, `publishMqttDisabledReason`, the new wiring signatures); the AMQP layout test is red by assertion (see below); the rest goes green with a throwaway reference implementation kept outside the repo (core, ui, desktopApp and the 17 runs of `15_mqtt_publishing.feature` all green there).
- AMQP fold check: the Publish button of `PublishTestMessageDialog` is at top 990 dp in a 600 dp window (and in an 800 dp window too) with 8 application properties and a 400-character error; a confirmation alone pushes it to 918 dp at 1000x600. So the fix of scope item 3 is needed.
- Fixed names the tests rely on: body tag `publishDialogBody`, scrollbar tag `publishDialogBodyScrollbar` (AMQP); banners `publishError`, `publishConfirmation` and the footer stay pinned below the body. Publish MQTT: `publishMqttErrorScrollbar` (and `publishMqttConfirmationScrollbar` for the confirmation, same `BannerBox`).
- Hint from the reference: in `BannerBox` the scrollbar must use `matchParentSize()` and the height limit belongs on the Box that holds the text (`heightIn(max = 136.dp)` on the Box, text with `verticalScroll`), otherwise the scrollbar fills the whole remaining height and the banner swallows the footer. Material buttons keep an OnClick action when disabled, so only `assertIsNotEnabled()` is asserted for them; the route pill and the node must have no click action.
- `core` `jvmTest` does not compile on `main` f9c0b69b: `BrokerNameRulesTest` (M9-01b step 2) references constants that M9-01b has not added yet. The new `MqttPublishAccessTest` cannot run in this branch until M9-01b is merged; run it after the merge, or delete that file locally for a quick run.

