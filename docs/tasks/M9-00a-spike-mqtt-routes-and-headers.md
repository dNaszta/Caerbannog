# M9-00a: Spike on a live 4.x broker: MQTT routes and headers bindings

Milestone: M9    Depends on: the owner's review of the M9 plan (nothing else)    Size: M

PLANNED 2026-11-15. Not started. One of three spikes (`M9-00a`, `M9-00b`, `M9-00c`) that can run in parallel; each is the first step of the tasks named in its "Used by" column.

## Goal
The items S-A, S-B and S-E of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured on a real RabbitMQ 4.x broker by live tests that stay as proof, and the spec, the `@needs-spike` scenarios of `@m9-10` and `@m9-11` and the briefs `M9-10` and `M9-11` are corrected to what was measured.

## Why
An MQTT route is saved as a binding key computed by the app, and a headers binding as arguments chosen in a form. If the translation or the argument shape is wrong, the migration applies cleanly and routes nothing, or routes too much, and no check can see it. Today only a literal topic into a stream is verified (`RealBrokerM5GateTest`, `RealBrokerMqttHeadersTest`: topic `sensors/room1`, key `sensors.room1`).

## Scope
In: `@broker` spike tests in `data-rabbit` (JUnit tag `broker`, Testcontainers, `rabbitmq:4-management`, the MQTT plugin enabled through exec as `RealBrokerMqttHeadersTest` does), fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the results file `docs/tasks/M9-00-spike-results.md` (shared by the three spikes, one section per item), spec and scenario corrections.

| Item | Question | Output | Used by |
| --- | --- | --- | --- |
| S-A1 | The broker's own translation: subscribe over MQTT (Paho 5 test client) to `sensors/+/temp`, `sensors/#`, `#`, `+`, `a/b/c` and read the binding the broker created on `amq.topic` (`GET /api/bindings/{vhost}`): which routing key does each filter get | translation table, fixture of the binding list | `M9-10` |
| S-A2 | The same keys written by hand: bind a stream and a quorum queue on `amq.topic` with each key, publish over MQTT to topics that should and should not match, and count what arrives (stream: management `messages`; queue: `messages` of `GET /api/queues/{vhost}/{name}`) | match table per filter, for a stream and for a quorum queue | `M9-10` |
| S-A3 | Edge levels: a level that holds a `.` (`sensors/v1.2/temp`), a level that holds a `*`, an empty level (`sensors//temp`), a leading and a trailing `/`, a space, a `$` topic. For each: is the publish accepted, which `x-routing-key` does the stream message carry (the app's stream reader shows it), which hand-written key matches it, and does the key translate back to the same topic | table: accepted or refused, key seen, round trip yes or no | `M9-10` (what the form refuses) |
| S-A4 | QoS 0 and QoS 1 publishes both arrive through a hand-written binding; a retained publish | note | `M9-10` |
| S-B1 | Can the name of the MQTT exchange be read through the management API (`/api/overview`, `/api/nodes`, `/api/global-parameters`, `/api/definitions` as administrator)? | yes with the field, or no | `M9-10` (the sentence "Uses the broker's default MQTT exchange amq.topic") |
| S-B2 | A second container configured with `mqtt.exchange = custom.mqtt`: does a binding on `amq.topic` receive an MQTT publish, does one on `custom.mqtt`, must the exchange exist first | observation | `M9-10` |
| S-E1 | Headers exchange: `POST` a binding with arguments of type text, whole number and boolean plus `x-match`; read it back: are the types kept | fixture, type table | `M9-11` |
| S-E2 | `x-match` values: `all`, `any`, `all-with-x`, `any-with-x`, none at all, and an invalid one: status of the `POST` and what the binding list shows | status table | `M9-11` |
| S-E3 | Two bindings between the same exchange and queue that differ only in arguments: two list entries; their `properties_key`; a repeated `POST` of one of them (status, list unchanged) | fixture | `M9-11`, `M9-05` |
| S-E4 | Routing: publish with headers through the management API (a test may publish) and count arrivals for `all` and `any`; a number header `10` against a text argument `"10"`; a binding with `x-match` and no header | match table | `M9-11` (the text of "A headers binding without a header says what it would match") |
| S-E5 | A fanout and a headers exchange with a routing key in the binding: is the key ignored for routing | observation | `M9-01`, `M9-11` (hiding the routing key field) |

Out: any product code beyond test helpers; MQTT subscriptions as a product feature; AMQP 1.0.

## Acceptance
- BDD: none run by this task. It unblocks the `@needs-spike` scenarios of `tests/bdd/features/21_topology_planner_recipes.feature` tagged `@m9-10` ("An MQTT route is saved as a binding on amq.topic", "A topic filter that cannot be translated safely is refused", "A message published over MQTT to a planned route reaches a stream and a queue") and `@m9-11` ("A binding on a headers exchange carries its headers and the match rule", "Header values keep their type in the saved file", "A headers binding without a header says what it would match"). The architect corrects their examples and wording to the measurements and removes the tag.
- Spike test classes `RealBrokerM9SpikeMqttRouteTest` and `RealBrokerM9SpikeHeadersBindingTest` assert the findings, so they fail on purpose when a later broker version answers differently.
- `docs/spec.md`: the rows S-A, S-B and S-E of "Facts to verify first" are replaced by the measured facts; nothing is left silently unanswered (an item that could not be measured says so).

## Design notes
Reuse the helpers of `RealBrokerMqttHeadersTest` (container, MQTT plugin, Paho client) and of the `RealBrokerM8Spike*Test` classes (management HTTP helper, fixtures). Paho MQTT 5 is already a dependency of `data-rabbit`; no new dependency. The image tag floats: record the exact version in the results file. Never sleep: wait for a value by bounded polling. "Does not arrive" is asserted with a positive control: publish the message under test, then a control message to a route that must match, wait for the control, then assert the first did not arrive. The app's own code is not exercised except the stream reader for `x-routing-key`; queue contents are judged by counts.

## UX
None.

## MCP
None (spike, no product capability).

## Open points
- If S-B1 finds no way to read the MQTT exchange, the planner keeps the assumption `amq.topic` and says so in the wizard (already the plan). If it finds one, `M9-10` reads it and the architect updates A10.
- If S-A3 shows that a level with a `.` cannot be expressed as a binding key at all, the form keeps refusing it; that is the default until measured.

## Definition of done
Spike tests pass on a machine with Docker or rootless Podman; fixtures committed; `docs/tasks/M9-00-spike-results.md` written (what was measured, the floated image version, what could not be measured); spec, scenarios and the briefs `M9-10`, `M9-11` corrected by the architect; no product code changed.
