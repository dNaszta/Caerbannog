# M9-07b: The optional MQTT routes of a stream pair (the owner's request and answer flow)

Milestone: M9    Depends on: M9-07 (the plain pair), M9-10 (the MQTT route form and its translation), M9-00a (S-A5 to S-A8, for the texts and the note), M9-12 (for the "recognised again" scenario only)    Size: S

PLANNED 2026-11-15. Not started. Cut out of `M9-07` on 2026-11-15 so that the plain stream pair can be built without the MQTT route step; this task adds two ticks that call the form of `M9-10`, and one note in the graph.

## Goal
In the stream form, with "Also create its response stream (DLN)" ticked, two more optional ticks, both off by default, each add one ordinary MQTT route: "Answers arrive over MQTT" into the response stream, and "Requests are also recorded from an MQTT topic" into the request stream. The wizard summary and the review graph say, in one short sentence, that the owner's own service turns a request into an MQTT message and that the planner does not plan that.

## Why
Owner decision O13 (Q3 revised). The owner's words: "i have several iot devices, i can request them through eg. started on stream, iot device gets mqtt message, answers with mqtt, which is processed through stream", and "header can contains destination id, devices answer on a fixed topic with their id in it, like devices/<id>/answer - correlation id". The flow: a request is a message in the request stream with the destination device id in a header; the owner's service (not planned by the app) turns it into an MQTT message for the device; the device answers on `devices/<id>/answer`; the answer must land in the response stream and be processed from there; request and answer are matched by a correlation id in the message, which the planner never touches. The first idea (routing on the request side by default) had the MQTT route on the wrong side. Spec "Topology planner (M9)", "Streams and the response stream", A14.

## Scope
In:
- `core.planner`: `StreamForm` (from `M9-07`) gains `answersTopic: String?` and `requestsTopic: String?`; null means the tick is off. The ticks exist only while `withResponse` is true; clearing the pair clears both. Each given filter is checked by the same function as the MQTT route form (`MqttTopicTranslation`, `M9-10`), with the same fixed texts. `actions`: the two streams first, then the answers route (`MqttRouteForm(answersTopic, <response stream name>, stream)` actions), then the requests route (`MqttRouteForm(requestsTopic, <stream name>, stream)` actions). Nothing else: no exchange, no policy, no marker (A1, A14).
- The text of the summary under the step list when an answers route is part of it, and of the graph note, is one constant: `PlannerTexts.SERVICE_NOT_PLANNED = "Your own service turns a request into an MQTT message; the planner does not plan that."`
- The note in the graph: `planGraphNotes(plan, known): List<PlanGraphNote>`, a pure function over the actions (A10, A11): for every MQTT route into a stream that is recognised as a response stream, one note between the request stream and `amq.topic` (once per request stream, however many routes). So the note is also drawn for a pair that was typed by hand or reopened from a file. It goes into the `notes` field that `M9-04` added to `PlanGraph`. A route into a stream that is not a response stream draws no note.
- `ui`: two checkboxes with a topic field each in `StreamFormView` (placeholder of the answers field `devices/+/answer`, the owner's example; the requests field has none), the note drawn by `PlanReviewGraph` as a dashed rounded box with no edge, no click and no focus action beyond reading it. Test tags: `planForm:answersOverMqtt`, `planForm:answersTopic`, `planForm:requestsOverMqtt`, `planForm:requestsTopic`, `planGraphNote:<index>`.

Out: any bridge from a stream to an MQTT message; reading or writing a correlation id (the test message of `M9-10` is the only place the planner pre-fills one, and the only place that warns about it: owner decision O16, 2026-11-17, the Publish MQTT dialog shows a warning that the service must read and write the application property `correlation-id`; the pair's tick and graph note say nothing about it); retention (`M9-08`); an exchange or a routing key of the owner's own choice for the pair; MQTT 5 settings of a route.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "The MQTT ticks are offered only with the response stream and are off until I tick them" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Answers arrive over MQTT adds one route into the response stream" (@fake-broker @smoke)
- BDD: `21_topology_planner_recipes.feature` / "Requests are also recorded from an MQTT topic adds one route into the request stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "Both ticks add two routes, the answers route first" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The wizard says what it does not plan" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The routes of a pair are ordinary steps" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The topic filter of a tick is checked like an MQTT route" (@fake-broker @negative)
- BDD: `21_topology_planner_recipes.feature` / "Without the response stream the MQTT ticks go away" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "The review graph shows a note where the owner's service sits" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "No note without an MQTT route into a response stream" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A saved pair with its answers route is recognised again" (@fake-broker)
- BDD: `21_topology_planner_recipes.feature` / "A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it" (@broker; the last steps now say: the application property `correlation-id` has the value, the standard `correlation-id` is empty)
- Unit tests, written first: `StreamFormMqttTicksTest` (actions and their order for each combination of ticks; the ticks are offered only with the pair and cleared when it is unticked; filter errors are the ones of the route form), `PlanGraphNotesTest` (one note for a pair with an answers route; none without a route; none for a route into a stream that is not a response stream; one note for two routes into the same pair; a hand-typed pair; a reopened pair), `PlanPreviewDeviceFlowTest` (the edge from `amq.topic` to the response stream is labelled with the filter, the note sits between the two nodes). Compose: `StreamFormMqttTicksUiTest`, `PlanReviewGraphNoteUiTest` (the note is drawn and read; it has no click action that reaches a planner event, with a sensitivity check that the test would see one).
- Live: `RealBrokerPlannerIotRouteTest` (tag `broker`) binds the `@broker` scenario by name: the saved plan is applied by the real runner; a publish over MQTT to `devices/d42/answer` with a correlation id as user property arrives in the response stream and not in the request stream (positive control, bounded polling, counts only); the correlation id is asserted as S-A6 measured it: a user property `correlation-id` arrives as the APPLICATION property `correlation-id` (text) and the standard AMQP `correlation-id` stays empty.

## Design notes
Everything here is built from parts that exist after `M9-07` and `M9-10`: two `declareStream` steps and ordinary `declareBinding` steps on `amq.topic`. After "Add to plan" nothing remembers that the steps belong together, so each can be edited or removed (A1); a reopened file shows the recognised pair (A11) and the recognised routes (A10) and the note follows from them. The note is not a step, not an edge and not stored. Where the broker is faked: `FakeTopologyCatalog` for `KnownTopology`; real in the live test. No MCP and no new request: the apply is the existing runner and executor.

## UX
`docs/ux/Planner.dc.html`: "4." form Stream (the two ticks under the response stream), "5. Summary" for the stream pair, "8. Review graph: other cases" (the graph of the request and answer flow with the note).

## MCP
None (owner decision 2026-11-15, "no MCP"). The existing tool `caerbannog_publish_mqtt` is unchanged and is not connected to plans.

## Open points
- Q3 (revised by the architect from the owner's description, CONFIRMED by the owner on 2026-11-15, O14): this task no longer waits for the owner, only for `M9-07`, `M9-10` and the spike items it names. No scenario of this task carries `@needs-decision` any more.
- The two tick texts, "Answers arrive over MQTT" and "Requests are also recorded from an MQTT topic", were part of the question the owner confirmed; the sentence of the note is the architect's wording and is in the mockup.
- Measured by M9-00a (2026-11-16), what this task may rely on: S-A1, `devices/+/answer` is `devices.*.answer` and `commands/#` is `commands.#` (the scenarios that name the keys lost `@needs-spike`); S-A5, nothing was found that feeds an MQTT subscription from a stream, so the note and the sentence "Your own service turns a request into an MQTT message; the planner does not plan that." stay and the owner is NOT asked (no feed exists to plan); S-A8, one publish reaches a device's own subscription and the request stream at once, so the planner adds nothing for that; S-A6 and S-A7, the correlation id of a test message arrives as an application property (see `M9-10`), MQTT 5 correlation data would arrive as the standard `correlation-id`, and a response topic as `reply-to` in the AMQP address form `/exchanges/amq.topic/<key>`.
- What it must NOT claim: that the broker bridges a stream to MQTT (none found, documentation read for the current release only), that a device's MQTT 5 correlation data lands where the test message's user property lands (it does not), or that `creation-time` of a stream message is set (the Java client shows 0).
- A filter typed in a tick is checked by `MqttTopicTranslation` of `M9-10`, so a `.`, a `*` or an empty level in `devices/+/answer`-style filters is refused there (rule A16).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, 2026-11-17): owner decision O16 item 1 (correlation-id warning) changes `M9-10`, not this task: no scenario of `M9-07b` changes. The live scenario "A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it" already asserts the measured place (application property, not the AMQP property), which is what the warning tells the owner.
