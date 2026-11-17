# M9-10b: "Send a test message" for an MQTT route: sample values, a generated correlation-id header and its warning

Milestone: M9    Depends on: M9-10 (`MqttRoutes.routeOf` and the translation), M9-03 (the Migrations screen); the existing Publish MQTT dialog (M6)    Size: S to M

PLANNED 2026-11-17 (cut out of `M9-10` at its step 1, as that brief allowed, so the route step is a smaller first slice). Not started.

## Goal
On the Migrations screen, an MQTT route of an applied migration offers "Send a test message": it asks for one sample value per wildcard of the topic filter, then opens the existing Publish MQTT dialog with the concrete topic and one generated `correlation-id` header, and a warning banner says where that header lands. Nothing is published until the user presses Publish.

## Why
Owner decisions O2 and O14 (the sample-value test message, A15), Q17, O16 (the warning), O17 (the queue text). Spec "Topology planner (M9)" A15 and "Measured facts of M9-00a" S-A6, S-A7.

## Scope
In:
- `core.mqtt`: `MqttTopicSample.fill(filter, values): SampleResult`, pure: one value for every `+` (the dialog starts the field as `item`, labelled "Value for the + in level N", hint "for example a device id") and one for a `#` (starts as `test`, "Value for the rest of the topic"); a filter without wildcards asks nothing. Fixed refusals: "Enter a value", "One level only: no /" (for a `+`), "No + or # in a value"; a refused value never opens the dialog. The `$` rule of MQTT is not applied (the broker delivers `$SYS/...` to `#`, S-A3).
- `CorrelationIdSource` (an interface in `core.mqtt`; a UUID in the composition root, a counter in tests). The dialog opens with one header, key `correlation-id` (constant, Q17), value from the source, an MQTT 5 user property in the dialog's own headers list, editable like any header. `PublishMqttViewModel` gets one optional parameter `initialHeaders: List<MqttHeader> = emptyList()` beside `initialTopic` (additive; the existing tests stay unedited).
- The warning (O16, O17): a warning banner (Material 3 warning container, the `banner warn` of the mockup, warning icon, `role` alert) under the headers list of the Publish MQTT dialog, shown only when the dialog was opened by "Send a test message" and a header with the key `correlation-id` is in the list (delete or rename the header and the warning goes). It never blocks Publish. Two exact texts, constants in `core` next to the sample-value texts, with a guard test word for word:
  - route into a STREAM (O16): `In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. Your service must read and write that application property.`
  - route into a QUEUE (O17, 2026-11-17, "show text"): `In the queue message this arrives as an entry of the headers table named correlation-id, not as the AMQP correlation_id property. Your service must read and write that header.`
  The kind is read from the migration's own steps and the connection's topology lists; if it cannot be told, no banner is shown and no kind is guessed (architect's technical decision, no new owner text). A stable `testTag` for the banner (chosen by the test developer, written in the report).
- The button on the Migrations screen: for an MQTT route of an APPLIED migration (A15). Disabled with the reason when the profile has no MQTT port ("This connection has no MQTT port") or the existing publish gate refuses ("Test publishing is disabled: no write permission on virtual host /."; reuse the existing text, no copy); not offered for a pending or modified file.
- `ui`: the button, the small sample-value dialog (no broker access), the banner in `PublishMqttDialog` (additive parameter).

Out: publishing from the planner itself; a correlation data or response topic field in the dialog (Q17: none); a banner for any other header; a Shovel or any queue read; the route step itself (`M9-10`).

## Acceptance
All `@fake-broker`, tag `@m9-10b` in `21_topology_planner_recipes.feature` (retagged from `@m9-10` on 2026-11-17; the planner runner filter gets `@m9-10b` in step 2 of this task, with `not @needs-decision`):
- "After applying, a test message asks for a sample value and opens the dialog with a concrete topic" (@smoke)
- "A route without wildcards opens the dialog at once"
- "A hash asks for the rest of the topic"
- "A sample value that would not make a valid topic is refused" (outline, @negative)
- "The dialog opens with a correlation-id header that is generated and can be changed"
- "The dialog warns that the service must read and write the application property correlation-id" (stream text; also asserts no correlation data field)
- "The warning for a route into a queue names the headers table" (queue text; `@needs-decision` removed 2026-11-17, O17)
- "The test message is not offered where publishing is not possible" (outline, @negative)
- "A route of a migration that is not applied here offers no test message" (@negative)
Unit tests, written first: `MqttTopicSampleTest` (one, two and no wildcard; `#` takes several levels; the three refusals with their texts; hostile values: control characters, 10,000 characters, a NUL), `CorrelationIdHeaderTest` (pre-filled from the injected source, two openings differ, the key is the constant), `CorrelationWarningTest` (both texts word for word; stream, queue, unknown kind gives none; header deleted or renamed gives none), `PublishMqttViewModelInitialHeadersTest` (initial headers are in the state and are sent as user properties; the old tests are unchanged). Compose: `MqttTestMessageDialogUiTest` (one field per wildcard, the start values, the refusals, Enter, Esc; the Publish dialog opens with the topic and the header and publishes nothing by itself), `MigrationsMqttTestMessageUiTest` (the button and its two disabled reasons, none for a pending file). Live: none needed; the broker facts (S-A6, S-A7) are pinned by `RealBrokerPlannerMqttRouteTest` of `M9-10` (one optional assertion there: the header arrives as the application property on a stream message and as a headers-table entry on a queue message, counted through the stream client and the management API, never read by the app's own code).

## Design notes
Only existing parts publish: `MqttMessagePublisher`, the dialog, the gate. Fakes: `FakeMqttMessagePublisher`, `FakeTopologyCatalog`. Safety: the profile's permissions and control level decide the button; no queue message is read; no new dependency. The dialog stays a thin composable: the topic, the header, the banner text and its visibility are `core` state.

## UX
`docs/ux/Planner.dc.html`: "14. After applying" (sample-value dialog, pre-filled header, banner). The mockup shows the stream text; the queue text is the same banner with the O17 sentence (no new layout).

## MCP
None (owner decision 2026-11-15). The existing `caerbannog_publish_mqtt` is unchanged and not connected to plans.

## Open points
- The wording of the disabled reasons reuses the existing publish gate text.
- S-A7: the standard AMQP `correlation-id` is filled only by MQTT 5 correlation data; the owner's answer (O16) is the warning, not a field. Reopenable by the owner only.
- NOT measured, so not claimed: QoS 2, topic length, non-ASCII topics; that a `#` filter skips `$` topics (it does not on 4.4.0).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-17): written as the cut-out of `M9-10`. Waits for `M9-10` (merged).
