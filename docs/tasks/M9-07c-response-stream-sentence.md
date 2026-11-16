# M9-07c: Add the one broker sentence to the response-stream note

Milestone: M9 (follow-up of M9-07)    Depends on: M9-07 (merged), M9-00b (measured 2026-11-16)    Size: XS (one constant, three tests, one scenario; under half a day)

## Goal
The note under the response-stream tick keeps what it says today and ends with the sentence "RabbitMQ does not move messages into it by itself: your applications write to it."

## Why
Owner decision O16 (2026-11-17, "add it", item 6 of the Owner review list of `docs/tasks/M9-00b-spike-dead-lettering-and-streams.md`). Spec "Streams and the response stream". The basis is measured: S-D1 (a stream declared with `x-dead-letter-exchange` is refused, 400 and 406), S-D2 (a `dead-letter-exchange` policy that applies to streams is accepted and never takes effect), S-D3 (nothing left a stream when retention removed 2132 messages; one run on RabbitMQ 4.4.0, stated as observed, not as a proof); `docs/tasks/M9-00b-spike-results.md`.

## Scope
In:
- `PlannerTexts.RESPONSE_STREAM_NOTE` (`core/src/commonMain/.../planner/PlannerTexts.kt`) becomes the two planner sentences it holds today, one space, then the owner's sentence. Final text, character for character:
  `A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them. RabbitMQ does not move messages into it by itself: your applications write to it.`
  Keep ONE constant (the screen, the state texts and the scenario read one string); the developer may build it from two private parts so the doc comment can name the basis of the second one (S-D1 to S-D3). The KDoc above the constant ("Says only what the planner does: no claim about the broker until the spike M9-00b has measured it") is now wrong and is replaced by one that names S-D1 to S-D3.
- The mockup `docs/ux/Planner.dc.html` (line 207) already holds the sentence; the architect changed nothing there. It shortens the planner sentences on purpose (a mockup banner), the constant keeps the full text the owner approved at M9-07 (do not shorten it).
Out: any other broker claim (no sentence about dead-letter exchanges, retention or super streams); any change to the form layout; the pair recognition; the wording of the tick.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "The wizard says what a response stream is and what RabbitMQ does not do" (`@m9-07 @m9-07c`, new, written 2026-11-17; carries `@needs-M9-07c` and NO `@fake-broker` until step 2 so that no runner selects it before its text is built; the planner runner is `(@m9-01 .. @m9-07) and @fake-broker and not @needs-decision and not @needs-spike`). Step 2 (test developer): DELETE the old scenario "The wizard says what a response stream is" in the same commit, drop `@needs-M9-07c`, add `@fake-broker`. The existing step `the form says {string}` already compares the text with the constant (`PlannerSteps.formSays` calls `PlannerStreamSteps.noteSaid` when the text equals the constant): the new scenario needs no new step.
- Tests that pin the OLD wording and MUST change in step 2 (found by grep on 2026-11-17):
  1. `core/src/jvmTest/.../planner/PlannerTextsStreamTest.kt`, test "the note says what a response stream is and what ticking does - and nothing else": the expected string becomes the final text above; rename it ("... and what RabbitMQ does not do").
  2. Same file, test "the note makes no claim about what RabbitMQ does": it forbids `rabbitmq`, `broker`, `message`, `move`, `write`, `application`, `by itself`. Replace it by a test that pins the claim narrowly: the note ends with the owner's sentence, the sentence appears once, and the note holds none of the words `retention`, `dead-letter`, `delete`, `queue`, `super` (so no second broker claim slips in unreviewed). The KDoc of the class also says the sentence is cut: update it.
  3. `ui/src/jvmTest/.../planner/StreamFormUiTest.kt` line about 125: the literal in `assertEquals(...)` for the tag `planForm:responseNote` becomes the final text (better: read `PlannerTexts.RESPONSE_STREAM_NOTE` and keep one literal in the core test).
  4. The feature scenario above (the literal is in the Gherkin).
  No other file holds the text (grep for `ordinary stream, named after` and `RESPONSE_STREAM_NOTE` over `core`, `ui`, `acceptance-tests`, `tests/bdd`, `docs`; docs and the spec are updated by the architect in this step).
- Layout check (no new scenario): the note is longer; the planner dialog must still show it without a clipped line at 1280 x 800 (the note is a wrapping `Text`, tag `planForm:responseNote`). One assertion in `StreamFormUiTest` that the node is displayed and not cut (height greater than one line is fine).

## Design notes
`core` only holds the string; `ui` already reads it. Nothing reaches the broker. No new dependency. Safety: the sentence is a statement about RabbitMQ, so it needs the measured basis above; it must not be widened (a queue's dead letters CAN be routed into a stream by a binding, S-C7; the sentence says nothing about that and the spec keeps this caution).

## UX
`docs/ux/Planner.dc.html`, "4." form Stream, the info banner under the response stream name. State to check: the tick on (the note shows), off (no note).

## MCP
None (UI-only text; no tool involved).

## Open points
- Super streams and other versions (owner asked the architect to decide whether the sentence needs a qualifier). Architect's decision: NO qualifier on screen. The planner's response stream is always a plain stream (the stream form declares one stream; a super stream cannot be planned here), so the sentence is about exactly what S-D measured (a plain stream, 4.4.0). Super stream partitions are plain streams made by the CLI; no measurement was made on them and none is claimed. Other versions: not measured; the app supports 4.x for the planner (`ManagementDeclareCheck` fails closed below 4), the sentence is stated as measured on 4.4.0 in the spec, not on screen. Listed for the owner as a question only if he wants "(measured on RabbitMQ 4.4)" on screen: recommendation no (the mockup sentence was approved unchanged).
- The sentence says "your applications": it matches the owner's own wording in the mockup and the IoT flow (O13); no change.

## Definition of done
Tests written first and failing for the right reason (the old expected text), then passing; `./gradlew :core:jvmTest :ui:jvmTest :acceptance-tests:test` green; no unrelated change; the spec line for O16 stays as the architect wrote it.

## Status
- Step 1 (architect, 2026-11-17, round 0, branch `brief-owner-answers-m9`): brief written from the owner's answer (O16, item 6). Scenario written with `@needs-M9-07c` (no `@fake-broker`); old scenario and the four pinning tests listed above, left untouched until step 2 so the suite stays green. Next: step 2 (test developer).
- Step 2 (test developer, 2026-11-17, round 0, branch `m9-07c-response-stream-sentence`): old scenario deleted, new one tagged `@fake-broker` (no `@needs-M9-07c`); `PlannerTextsStreamTest` (two tests rewritten, class comment updated), `StreamFormUiTest` (literal now the final text, plus `assertIsDisplayed` on the note). Red because `RESPONSE_STREAM_NOTE` still holds the old text. Next: step 3 (senior developer): change the constant and its KDoc only.
