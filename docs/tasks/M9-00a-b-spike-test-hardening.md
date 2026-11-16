# M9-00a-b: Harden the M9-00a spike tests where a claim is not yet pinned by an assertion

Milestone: M9    Depends on: M9-00a (step 3 review)    Size: S

PLANNED 2026-11-16 by the architect at step 3 of `M9-00a`. Not started. It blocks nothing: the spec and the briefs already use the corrected wording; this task makes the tests say the same.

## Goal
Every fact that `docs/tasks/M9-00a-spike-results.md` and the spec's "Measured facts of M9-00a" state is asserted by a live test with a positive control, or the results file says it is only printed. No test edits a production class.

## Why
The brief of `M9-00a` ("Design notes") demands that "does not arrive" is asserted after a control arrived, and that the tests fail on purpose when a later 4.x answers differently. Review findings R1 to R7 and E1 (results file, "Review at step 3") name places where a note was printed instead of asserted, or where a column was computed instead of measured.

## Scope
In (all in `data-rabbit/src/test`, tag `broker`, run one class at a time):
1. **R1, `RealBrokerM9SpikeMqttRouteTest` S-A3.** Measure what the broker hands back for a dot level: one MQTT subscriber of `sensors/#` (or `#`) next to the others, then assert the topic text it receives for `sensors/v1.2/temp` (the expected, not yet measured, value is `sensors/v1/2/temp`; assert whatever is measured and correct the results file). Replace the computed column "key back to topic" by this measurement for the dot row. Do the same for the asterisk row with a raw socket if cheap (already done for `a+b`).
2. **R2, same test.** Assert the control message at every literal subscriber that stayed connected (today `controlSeen` is only printed): `literalSubscriberGotIt=false` for the dot row then has an asserted positive control.
3. **R3, S-A1.** Assert the subscriber queue's `type`, `durable`, `auto_delete` and the two binding argument keys `x-binding-key`, `x-mqtt-subscription-opts`, or drop them from the results file.
4. **R4, S-B1 on the default broker.** Assert it (for example: no response of the listed endpoints contains `amq.topic` next to the word `mqtt`, and the `/api/overview` mention of `mqtt` is the listener only), or keep it as printed notes and say so in the results file (already said). Add the request as a user without the administrator tag (monitoring) if cheap: the brief of `M9-10c` would need it.
5. **R5, S-A4.** Assert that the retained message's annotations are exactly `x-routing-key` and `x-exchange` and its standard and application properties are empty.
6. **R6, `RealBrokerM9SpikeHeadersBindingTest` `arrivals()`.** Poll the always-matching queue (`all(only x-match)`; for S-E5 the fanout queue) until the marker is there before peeking the other queues, so every "did not arrive" follows a control. Keep the expected tables unchanged.
7. **R7, S-A7.** Assert `creation-time` and `absolute-expiry-time` = 0 on the two stream messages that carry correlation data, or drop the claim from the results file.
8. **E1, optional.** One extra headers probe for `M9-11`: a boolean argument (`true`) and a decimal (`1.5`) matched by a publish with the same header, with a positive control (add the result to the results file; if the routing differs, `M9-11` is told).

Out: any new fact beyond item 8; QoS 2; clusters; a custom exchange other than `custom.mqtt`.

## Acceptance
- BDD: none (spike tests). The four classes stay green on RabbitMQ 4.x, each run alone; the first test of each still asserts a `4.` broker.
- The results file is edited only where a measurement replaces a printed note or a computed column.

## Design notes
Bounded polling through `awaitUntil`, no sleep. Keep the helpers private to the M9-00a files. Run `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeMqttRouteTest*'` and the others one at a time (the coordinator allows two broker tests at once).

## UX
None.

## MCP
None (spike).

## Open points
If item 1 shows that the subscriber of `#` receives something other than `sensors/v1/2/temp`, the results file and the spec sentence "A topic with a dot and a topic with a slash in its place are the same key" stay true (it follows from the key); only the explanation of the missing delivery changes.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
