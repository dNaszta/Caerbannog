# M9-10b: "Send a test message" for an MQTT route: sample values, a generated correlation-id header and its warning

Milestone: M9    Depends on: M9-10 (`MqttRoutes.routeOf` and the translation, merged), M9-03 (the Migrations screen); the existing Publish MQTT dialog (M6)    Size: M (confirmed at step 1; one task, two review checkpoints, see "Step 1 decisions")

PLANNED 2026-11-17 (cut out of `M9-10` at its step 1, as that brief allowed, so the route step is a smaller first slice). Step 1 done 2026-11-17 (the brief is confirmed and made exact below).

## Goal
On the Migrations screen, an MQTT route of an applied migration offers "Send a test message": it asks for one sample value per wildcard of the topic filter, then opens the existing Publish MQTT dialog with the concrete topic and one generated `correlation-id` header, and a warning banner says where that header lands. Nothing is published until the user presses Publish.

## Why
Owner decisions O2 and O14 (the sample-value test message, A15), Q17, O16 (the warning), O17 (the queue text). Spec "Topology planner (M9)" A15 and "Measured facts of M9-00a" S-A3, S-A6, S-A7.

## Scope
In (all exact; see "Step 1 decisions" for the reasons):
- `core.planner` (next to `MqttRoutes`; the planner package already owns the translation and `MqttDestinationKind`), pure and unit-tested:
  - `MqttTopicSample`: `slots(filter): List<SampleSlot>` (`Plus(level)` or `Hash(level)`, `level` 1-based), `startValue(slot)` (`item` for a `+`, `test` for a `#`), `fill(filter, values): SampleResult` (`Topic(topic)` or `Refused(problems)`, one problem per refused slot with its fixed text). A filter without wildcards has no slots and `fill` returns the filter itself.
  - Rules for a value of a `+`: not blank ("Enter a value"), no `/` ("One level only: no /"), no `+` or `#` ("No + or # in a value"), no `.` (the O15 text `A level cannot contain a dot: RabbitMQ would read it as another level`, reused), no control character (new text, Owner review list item 1). A `#` value: not blank, no `+` or `#`, no control character; `/`, empty levels and dots are allowed (S-A3 measured that they work). No length rule (not measured); 10,000 characters give a topic, and the broker's refusal shows in the dialog. The MQTT `$` rule is not applied (S-A3).
  - The start values of every slot, filled in, give the same topic as `MqttRouteForm.matchingTopic()` (guard test over several filters). Do NOT use `StreamInboundRoute.routingKeyToSuggestedMqttTopic` here: it trims the whole key, splits on `.` and `/` and drops empty levels, which would change a filter such as `a//b`.
  - `MqttTestMessage.offer(status, route, hasMqtt, publishingEnabled, vhost): TestMessageOffer` = `NotOffered` (status not Applied), `Disabled(reason)` or `Available`. Order: not applied, then no MQTT port, then no write permission. Texts are constants: `This connection has no MQTT port` and the shared write text. Extract the write text of `PublishMessageViewModel` into one function in `core.publish` (`noWritePermission(vhost)`), used by both; the old tests stay unedited.
  - `MqttTestMessageTexts`: the two warning texts (O16 stream, O17 queue) word for word, the three refusals, the control-character refusal, the labels `Value for the + in level N` and `Value for the rest of the topic`, the hint `for example a device id`.
  - `MqttTestMessage.opening(route, kind, values, ids): TestMessageOpening` = topic, one header `correlation-id` (constant, Q17) with the value of the injected `CorrelationIdSource`, and a `HeaderNotice` (key `correlation-id`, the text of the kind) or null when `kind` is null.
  - The kind comes from `MqttRoutes.kindOf(route.destination, file.up, KnownTopology.None)` (the declare steps of the SAME migration). Unknown gives no notice and no guess.
- `core.mqtt`: `CorrelationIdSource` (an interface; a UUID in the composition root, a counter in tests); `HeaderNotice(key, text)`. `PublishMqttViewModel` gets two optional parameters beside `initialTopic`: `initialHeaders: List<MqttHeader> = emptyList()` and `headerNotice: HeaderNotice? = null` (additive; the existing tests stay unedited). The state gets `headerNoticeText: String?`: the notice text while a header whose trimmed key equals the notice key (exact case) is in the list, else null. It never changes `canPublish`.
- `ui`: the button and its reason on the action row of a route (`MigrationsScreen`, the row of `DryRunActionRow`; a route row only), the small sample-value dialog (no broker access; state in a small `core` holder so the composable only draws), the banner in `PublishMqttDialog` (reads `state.headerNoticeText`; no new parameter). The banner sits under the headers list (after the rows and "Add header", before the confirmation and error banners), uses the Material 3 warning container, a warning icon and a polite live region (Compose has no alert role). It is a different element from the existing broker-name banner (`publishMqttWarningBanner`, unchanged).
- `desktopApp`: `MigrationsScreenState` gets the two inputs it lacks (`hasMqtt`, `publishingEnabled`, additive, defaults false), set by `MigrationsWiring` from the held source and the permission check (same source as `canWrite` of the AMQP publish dialog in `Main.kt`); the window content hosts a fresh `PublishMqttViewModel` per opening (publisher `source.mqttPublisher`) and the existing `PublishMqttEventsAdapter`; the UUID source.
- Stable `testTag`s (fixed here so the test developer need not guess; the test developer may change one and must say so in the report): `migrations:testMessageBtn:<i>` and `migrations:testMessageReason:<i>` (`<i>` the action row index, as `dryRunAction:<i>`), `testMessageDialog`, `testMessageValue:<n>` and `testMessageError:<n>` (`<n>` the field, 0-based), `testMessageOpenBtn` ("Open the dialog", as in the mockup), `testMessageCancelBtn`, `publishMqttHeaderNotice` (the banner).

Out: publishing from the planner itself; a correlation data or response topic field in the dialog (Q17: none); a banner for any other header; a Shovel or any queue read; the route step itself (`M9-10`); a write-permission gate on the Publish MQTT entry points of the stream and topology screens (follow-up `M9-10e`, see below); the kind from the broker's topology or from other migration files (idea, not now).

## Acceptance
All `@fake-broker`, tag `@m9-10b` in `21_topology_planner_recipes.feature` (11 scenarios; the planner runner filter gets `@m9-10b` in step 2 of this task, written as `... or @m9-10b)` with `not @needs-decision` kept):
- "After applying, a test message asks for a sample value and opens the dialog with a concrete topic" (@smoke)
- "A route without wildcards opens the dialog at once"
- "A hash asks for the rest of the topic"
- "A sample value that would not make a valid topic is refused" (outline, @negative; rows: empty, `d/42`, `d+42`, `d#42`, `d.42`)
- "The dialog opens with a correlation-id header that is generated and can be changed"
- "The dialog warns that the service must read and write the application property correlation-id" (stream text; also asserts no correlation data field)
- "The warning for a route into a queue names the headers table" (queue text; the migration declares the queue, so the kind is known)
- "No warning is shown when the kind of the destination is not known" (new at step 1)
- "The warning goes away with the header and never blocks Publish" (new at step 1)
- "The test message is not offered where publishing is not possible" (outline, @negative)
- "A route of a migration that is not applied here offers no test message" (@negative)
Unit tests, written first: `MqttTopicSampleTest` (one, two and no wildcard; `#` takes several levels, empty levels and dots; every refusal with its text; hostile values: control characters, a NUL, 10,000 characters gives a topic and no exception; the start-value guard against `MqttRouteForm.matchingTopic()`), `MqttTestMessageOfferTest` (the three outcomes and their order), `CorrelationIdHeaderTest` (pre-filled from the injected source, two openings differ, the key is the constant), `CorrelationWarningTest` (both texts word for word; stream, queue, unknown kind gives none; header deleted or renamed gives none; the key is trimmed and case-sensitive), `PublishMqttViewModelInitialHeadersTest` (initial headers are in the state and are sent as user properties; the notice never changes `canPublish`; the old tests are unchanged), a test that the AMQP dialog still says the same write text. Compose: `MqttTestMessageDialogUiTest` (one field per wildcard, the start values, the refusals, Enter, Esc; the Publish dialog opens with the topic and the header and publishes nothing by itself), `MigrationsMqttTestMessageUiTest` (the button and its two disabled reasons, none for a pending or modified file, none on a row that is not a route).

Live plan (concrete): no new `@broker` scenario and no new live class; the work adds no broker behaviour (M9-00a measured it). The facts below are pinned by classes that already exist and must stay green; the tests of this task may rely on them and must not re-measure:
- `RealBrokerPlannerIotRouteTest` (`brokerTest --tests '*PlannerIotRoute*'`): the header `correlation-id` arrives as the application property on a stream message and the standard correlation-id stays empty (S-A6), counted through the stream client.
- `RealBrokerPlannerMqttRouteTest` (binds the `@m9-10 @broker` scenario of `21`): translation, stream and quorum queue as destination (S-A1 to S-A3).
- Not pinned by any live test: the headers-table entry on a QUEUE message (S-A6, second half). Optional, one assertion in `RealBrokerPlannerMqttRouteTest`, read only through the management API `get` with `ack_requeue_true` of the existing inspector, never by the app's own code; if it is skipped the queue text stays backed by the spike record in the spec.
- Rows the tests of this task may pin: S-A1 (`/` is `.`, `+` is `*`), S-A3 (a dot in a level is another word, so a `+` value refuses it; empty levels, spaces and `$` topics work), S-A6 (both header landings, which the two banner texts say), S-A7 only as "no correlation data field". Not measured, so never asserted: QoS 2, topic length, non-ASCII topics, that `#` skips `$` topics (it does not on 4.4.0).
Run: the fast suites of `core`, `ui`, `desktopApp` and `acceptance-tests` (`./gradlew :acceptance-tests:test`); `brokerTest` only if the optional assertion is added.

## Design notes
Only existing parts publish: `MqttMessagePublisher`, the Publish MQTT dialog, `PublishMqttEventsAdapter`. Fakes: `FakeMqttMessagePublisher`, `FakeTopologyCatalog` (not needed by the kind rule). Safety: the profile's MQTT port and the write permission decide the button; the app reads no queue message; no new request kind; no new dependency (`kotlin.uuid` is not used; the UUID lives behind the interface in `desktopApp`). `core` stays free of `java.*`. The dialog stays a thin composable: the topic, the header, the banner text and its visibility are `core` state.

## UX
`docs/ux/Planner.dc.html`: "14. After applying" (sample-value dialog, pre-filled header, banner). The mockup shows the stream text; the queue text is the same banner with the O17 sentence (no new layout). The mockup's confirm button reads "Open the dialog".

## MCP
None (owner decision 2026-11-15). The existing `caerbannog_publish_mqtt` is unchanged and not connected to plans.

## Open points
- S-A7: the standard AMQP `correlation-id` is filled only by MQTT 5 correlation data; the owner's answer (O16) is the warning, not a field. Reopenable by the owner only.
- NOT measured, so not claimed: QoS 2, topic length, non-ASCII topics; that a `#` filter skips `$` topics (it does not on 4.4.0).
- Known limit: a route whose destination is declared by an EARLIER migration shows no banner (the kind is unknown on this screen). A stream pair planned with M9-07b declares everything in one file, so the usual case is covered. Idea for later: also read the declare steps of the other applied migrations.
- Gap found at step 1: the older Publish MQTT entry points (stream and topology screens) have no write-permission gate. Follow-up `M9-10e` (S): reuse the same `core.publish` text and the offer rule. Not part of this task.

## Step 1 decisions (architect, 2026-11-17)
1. **Gates** (A15 said "the existing publish gate"): the existing MQTT dialog has only the MQTT-port gate (`offersMqttPublishing`); the write-permission text exists only in the AMQP dialog. This task shares that text from `core.publish` and gates the new button with: applied, then MQTT port, then write permission. An unfinished permission check counts as no write permission (same as `canWrite` in `Main.kt`).
2. **Where the button is:** on the action row of a route (as the mockup says: "The action row of an applied file names an MQTT route ... and offers the button"), not in the file's bottom bar, so a file with two routes (answers and requests) tests each. Applied means `MigrationStatus.Applied` of the selected entry, which is the history of THIS connection profile.
3. **Sample values:** one field per wildcard (the mockup), in order, label `Value for the + in level N` (N counts levels of the filter from 1), `#` as `Value for the rest of the topic`. Start values `item` and `test` are the rule of the route form (guard test). The dot refusal is new in use, not in wording (O15 text); it is needed because S-A3 measured that a dotted level is two words and the message would miss the filter.
4. **Kind source:** the declare steps of the migration itself (`MqttRoutes.kindOf(..., KnownTopology.None)`); the earlier rule is confirmed: unknown means no banner and no guess. This corrects the first draft of this brief, which also named "the connection's topology lists": the Migrations screen holds no broker topology (M9-10 decision, `MigrationsMqttRouteUiTest`).
5. **Banner:** a `HeaderNotice` in the view model, not a flag "opened by the test message" in the dialog; the existing dialog callers pass none, so they cannot show it.
6. **Size and split:** M, one task. The unit-testable `core` part and the ui part are one vertical slice (nine of eleven scenarios cross both), so no split; step 3 may commit in two checkpoints: (a) `core` plus `PublishMqttViewModel` plus the banner in the dialog, (b) the button, the sample dialog and the wiring. `M9-10e` is separate.
7. **Runner:** only `RunTopologyPlannerAcceptanceTest` selects feature `21`; the other runners select other features, so the new scenarios cannot be picked up by a default runner. Step 2 adds `@m9-10b` to that one filter.

## Owner review list (new wording; all OPEN, recommendation given, the owner may answer "yes to all")
1. OPEN. Refusal of a value with a control character (new text): `No control characters in a value`. Recommendation: yes.
2. OPEN. The O15 dot text reused for a `+` value: `A level cannot contain a dot: RabbitMQ would read it as another level`. Recommendation: yes (no new sentence).
3. OPEN. The sample dialog title `Send a test message` and the confirm and cancel buttons `Open the dialog` and `Cancel` (the first two are in the mockup). Recommendation: yes.
4. OPEN. The disabled-reason texts are the decided ones (`This connection has no MQTT port`, and the write text of the AMQP dialog). Recommendation: yes; listed only because the write text is now shared.
No product decision is open. The owner may separately want `M9-10e` (write-permission gate on the older MQTT entry points); recommendation: yes, small.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-17): written as the cut-out of `M9-10`; confirmed and made exact (branch `m9-10b-mqtt-test-message`). Changed: this brief, feature `21` (two scenarios, one example row, a stale comment), spec A15. Next: step 2 (test developer).
- Step 2 (test developer, 2026-11-17, round 0): tests written first, red because the production code is missing (compile errors in the `core`, `ui` and `acceptance-tests` test source sets; nothing else fails). Unit: `MqttTopicSampleTest`, `MqttTestMessageOfferTest`, `CorrelationIdHeaderTest`, `CorrelationWarningTest`, `NoWritePermissionTextTest`, `PublishMqttViewModelHeaderNoticeTest`. Compose: `MqttTestMessageDialogUiTest`, `MigrationsMqttTestMessageUiTest`, `PublishMqttHeaderNoticeUiTest`. Glue: `PlannerTestMessageSteps` (11 scenarios) and the runner filter now lists `@m9-10b`. API fixed by the tests is listed in the step 2 report; the architect confirms it. Next: step 3 (senior developer). No live class added; the optional queue headers-table assertion is skipped (see the report).
