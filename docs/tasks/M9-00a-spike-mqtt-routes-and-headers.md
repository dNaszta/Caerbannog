# M9-00a: Spike on a live 4.x broker: MQTT routes, headers bindings and the correlation id of an MQTT request and response flow

Milestone: M9    Depends on: none (the owner answered the open questions on 2026-11-15)    Size: M

PLANNED 2026-11-15, confirmed for the live run 2026-11-16 (a container runtime exists now). One of three spikes (`M9-00a`, `M9-00b`, `M9-00c`) that can run in parallel with each other; see "How the spike is run" for how many brokers may run at once.

## Goal
The items S-A1 to S-A8, S-B and S-E of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured on a real RabbitMQ 4.x broker by live tests that stay as proof, and the spec, the `@needs-spike` scenarios of `@m9-07b`, `@m9-10` and `@m9-11` and the briefs `M9-07b`, `M9-10` and `M9-11` are corrected to what was measured.

## Why
An MQTT route is saved as a binding key computed by the app, and a headers binding as arguments chosen in a form. If the translation or the argument shape is wrong, the migration applies cleanly and routes nothing, or routes too much, and no check can see it. Today only a literal topic into a stream is verified (`RealBrokerM5GateTest`, `RealBrokerMqttHeadersTest`: topic `sensors/room1`, key `sensors.room1`).

## Scope
In: `@broker` spike tests in `data-rabbit` (JUnit tag `broker`, Testcontainers, `rabbitmq:4-management`, the MQTT plugin enabled through exec as `RealBrokerMqttHeadersTest` does), fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the results file `docs/tasks/M9-00a-spike-results.md` (one file per spike so the three branches never edit the same file; one section per item), spec and scenario corrections.

| Item | Question | Output | Used by |
| --- | --- | --- | --- |
| S-A1 | The broker's own translation: subscribe over MQTT (Paho 5 test client) to `sensors/+/temp`, `sensors/#`, `#`, `+`, `a/b/c` and `devices/+/answer` (the owner's filter for the answers of his devices; a named row; expected key `devices.*.answer`, UNVERIFIED) and read the binding the broker created on `amq.topic` (`GET /api/bindings/{vhost}`): which routing key does each filter get | translation table, fixture of the binding list | `M9-10`, `M9-07b` |
| S-A2 | The same keys written by hand: bind a stream and a quorum queue on `amq.topic` with each key, publish over MQTT to topics that should and should not match, and count what arrives (stream: management `messages`; queue: `messages` of `GET /api/queues/{vhost}/{name}`) | match table per filter, for a stream and for a quorum queue | `M9-10` |
| S-A3 | Edge levels: a level that holds a `.` (`sensors/v1.2/temp`), a level that holds a `*`, an empty level (`sensors//temp`), a leading and a trailing `/`, a space, a `$` topic. For each: is the publish accepted, which `x-routing-key` does the stream message carry (the app's stream reader shows it), which hand-written key matches it, and does the key translate back to the same topic | table: accepted or refused, key seen, round trip yes or no | `M9-10` (what the form refuses) |
| S-A4 | QoS 0 and QoS 1 publishes both arrive through a hand-written binding; a retained publish | note | `M9-10` |
| S-A5 | Can anything feed an MQTT subscription directly from a stream? Expectation (UNVERIFIED): no. Measure: a stream bound to `amq.topic` and an MQTT client subscribed to the same topic; a message put into the stream through the stream protocol (not through `amq.topic`) never reaches the client; is there any option of the MQTT plugin or of a subscription that consumes a stream (record what the documentation of the installed version says and cite the page) | yes or no with the evidence | `M9-07b` (the graph note, the wizard sentence) |
| S-A6 | A correlation id set as an MQTT 5 user property (key `correlation-id`, and once another key) on a publish to `devices/d42/answer`, delivered into a stream through a binding `devices.*.answer`: where does it land on the stream message (read with the app's stream reader and, for the raw facts, the stream protocol): an application property, a message annotation, a standard property, or nowhere; the same into a quorum queue (requeue peek) | table | `M9-10` (the header of the test message), `M9-07b` |
| S-A7 | MQTT 5 correlation data and response topic: a publish with both set; are they kept into the stream message (which AMQP property or annotation), and into a queue | table | `M9-10`, `M9-07b` |
| S-A8 | Fan-out: one message published to `amq.topic` as `devices/d42/request` reaches both a device-style MQTT subscription (its own queue) and a request stream bound with `devices.*.request`; counts on both, with a positive control | observation | `M9-07b` |
| S-B1 | Can the name of the MQTT exchange be read through the management API (`/api/overview`, `/api/nodes`, `/api/global-parameters`, `/api/definitions` as administrator)? | yes with the field, or no | `M9-10` (the sentence "Uses the broker's default MQTT exchange amq.topic") |
| S-B2 | A second container configured with `mqtt.exchange = custom.mqtt`: does a binding on `amq.topic` receive an MQTT publish, does one on `custom.mqtt`, must the exchange exist first | observation | `M9-10` |
| S-E1 | Headers exchange: `POST` a binding with arguments of type text, whole number and boolean plus `x-match`; read it back: are the types kept | fixture, type table | `M9-11` |
| S-E2 | `x-match` values: `all`, `any`, `all-with-x`, `any-with-x`, none at all, and an invalid one: status of the `POST` and what the binding list shows | status table | `M9-11` |
| S-E3 | Two bindings between the same exchange and queue that differ only in arguments: two list entries; their `properties_key`; a repeated `POST` of one of them (status, list unchanged) | fixture | `M9-11`, `M9-05` |
| S-E4 | Routing: publish with headers through the management API (a test may publish) and count arrivals for `all` and `any`; a number header `10` against a text argument `"10"`; a binding with `x-match` and no header | match table | `M9-11` (the text of "A headers binding without a header says what it would match") |
| S-E5 | A fanout and a headers exchange with a routing key in the binding: is the key ignored for routing | observation | `M9-01`, `M9-11` (hiding the routing key field) |

Out: any product code beyond test helpers; MQTT subscriptions as a product feature; AMQP 1.0.

## Acceptance
- BDD: none run by this task. It unblocks the `@needs-spike` scenarios of `tests/bdd/features/21_topology_planner_recipes.feature` tagged `@m9-07b` ("Answers arrive over MQTT adds one route into the response stream", "Requests are also recorded from an MQTT topic adds one route into the request stream", "Both ticks add two routes, the answers route first", "The routes of a pair are ordinary steps", "A saved pair with its answers route is recognised again", "A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it"), `@m9-10` ("The dialog opens with a correlation-id header that is generated and can be changed", "An MQTT route is saved as a binding on amq.topic", "A topic filter that cannot be translated safely is refused", "A message published over MQTT to a planned route reaches a stream and a queue") and `@m9-11` ("A binding on a headers exchange carries its headers and the match rule", "Header values keep their type in the saved file", "A headers binding without a header says what it would match"). The architect corrects their examples and wording to the measurements and removes the tag.
- Spike test classes `RealBrokerM9SpikeMqttRouteTest`, `RealBrokerM9SpikeMqttRequestResponseTest` (S-A5 to S-A8) and `RealBrokerM9SpikeHeadersBindingTest` assert the findings, so they fail on purpose when a later broker version answers differently.
- `docs/spec.md`: the rows S-A1 to S-A8, S-B and S-E of "Facts to verify first" are replaced by the measured facts; nothing is left silently unanswered (an item that could not be measured says so).

## Design notes
Reuse the helpers of `RealBrokerMqttHeadersTest` (container, MQTT plugin, Paho client) and of the `RealBrokerM8Spike*Test` classes (management HTTP helper, fixtures). Paho MQTT 5 is already a dependency of `data-rabbit`; no new dependency. The image tag floats: record the exact version in the results file. Never sleep: wait for a value by bounded polling. "Does not arrive" is asserted with a positive control: publish the message under test, then a control message to a route that must match, wait for the control, then assert the first did not arrive. The app's own code is not exercised except the stream reader for `x-routing-key`; queue contents are judged by counts.

## UX
None.

## MCP
None (spike, no product capability).

## Open points
- If S-B1 finds no way to read the MQTT exchange, the planner keeps the assumption `amq.topic` and says so in the wizard (already the plan). If it finds one, `M9-10` reads it and the architect updates A10.
- If S-A3 shows that a level with a `.` cannot be expressed as a binding key at all, the form keeps refusing it; that is the default until measured.
- If S-A6 and S-A7 show that the correlation id does not reach the stream message (neither as a user property nor as correlation data), the test message keeps its header (the existing dialog can do nothing else) and the architect tells the owner, because his processing then cannot match answers to requests that way; the planner is unaffected (it plans streams and routes only).
- If S-A5 finds a way to feed an MQTT subscription from a stream, the owner is asked whether the planner should plan it: that would be a new decision, because the confirmed Q3 plans no bridge (O13, O14).
- The spike grows by about half a day with S-A5 to S-A8; it stays one task of size M.

## Definition of done
Spike tests pass on a machine with Docker or rootless Podman; fixtures committed; `docs/tasks/M9-00a-spike-results.md` written (what was measured, the floated image version, what could not be measured); spec, scenarios and the briefs `M9-07b`, `M9-10`, `M9-11` corrected by the architect; no product code changed.

## How the spike is run (added at step 1, 2026-11-16)
- **Broker image:** the literal `rabbitmq:4-management` (the repo has no image constant: every live test writes the literal, as `RealBrokerMqttHeadersTest` and `RealBrokerM8SpikeDefinitionsTest` do). Plugins through `brokerWithPlugins(image, "rabbitmq_stream", "rabbitmq_stream_management", "rabbitmq_mqtt")` in `RabbitContainers.kt`; `MgmtApi` and `awaitUntil` of `M8SpikeSupport.kt` are reused as they are. The tag floats: the first test of each class asserts that `GET /api/overview` `rabbitmq_version` starts with `4.`, and the results file records the exact RabbitMQ and Erlang version, the CPU architecture and the date (as `M8-00-spike-results.md` does).
- **Throwaway brokers only.** Testcontainers containers with the default guest user; never a user's broker, no profile of the app. No password or token reaches a log or a fixture except the container's own default `guest`.
- **MQTT login and the port:** the plain MQTT port 1883 is used (a throwaway container; the product's TLS default is not under test here). Copy from `RealBrokerMqttHeadersTest` whatever it needs so that the Paho client may log in (its `tls/rabbitmq.conf`); S-B2 needs its own configuration file with `mqtt.exchange = custom.mqtt`, kept in `data-rabbit/src/test/resources/fixtures/m9-00/` and passed with `withRabbitMQConfig`, keeping the lines that let the login work.
- **Resources:** S-B2 starts a second broker, so this spike peaks at two containers. Put S-B2 in its own test class so the others run on one container. The coordinator runs at most two broker tests at a time across all five tasks (see the plan in the `M9-00c` Status).
- **Helpers stay private** to the new test files (names prefixed `M9SpikeMqtt...`): three branches run in parallel and a shared new `M9SpikeSupport.kt` would conflict on merge. Duplicated helper code is accepted for the spike; the architect decides on a shared helper when the spikes are merged.
- **Fixtures:** `data-rabbit/src/test/resources/fixtures/m9-00/` with files named `s-a1-...`, `s-e1-...` (item prefix) so no other spike writes the same file name. Fixtures hold broker answers of a throwaway container only.
- **Every item ends in one of three states** in the results file: measured (test method named), not measurable (why), or measured with a surprise (what differs from the spec's expectation, and which scenario or brief it contradicts). Nothing is dropped silently.

## What the follow-on tasks may rely on (the contract of this spike)
- `M9-10` may rely on: the translation table of S-A1 (including the named row `devices/+/answer`), the match table S-A2 for a stream and a quorum queue, the refusal list of S-A3 (what the form blocks), S-A6 and S-A7 (where the correlation id, correlation data and response topic land), S-B1 and S-B2 (the sentence about `amq.topic`).
- `M9-07b` may rely on: S-A5 (yes or no feed from a stream), S-A6, S-A7, S-A8 (fan-out).
- `M9-11` may rely on: S-E1 to S-E5 (types kept, `x-match` values and the status of an invalid one, two bindings differing only in arguments, routing behaviour, routing key of a fanout or headers binding).
- Not relied on until measured: anything marked UNVERIFIED in the rows above. The test classes `RealBrokerM9SpikeMqttRouteTest`, `RealBrokerM9SpikeMqttRequestResponseTest`, `RealBrokerM9SpikeMqttExchangeTest` (S-B, new: its own class for the second container) and `RealBrokerM9SpikeHeadersBindingTest` pin each fact, so a later 4.x that answers differently fails them on purpose.

## Product questions
None. (Open points above are technical; if S-A5 finds a feed from a stream, the architect asks the owner then, as the open point says.)

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-00a-spike-mqtt-routes-and-headers`, base main `652c42a`): brief checked against the spec ("Facts to verify first", S-A to S-E; A10, O13, O14), the code (`RealBrokerMqttHeadersTest`, `RabbitContainers.kt`, `M8SpikeSupport.kt`) and the scenarios of `@m9-07b`, `@m9-10`, `@m9-11`. Brief CHANGED: results go to `M9-00a-spike-results.md` (one file per spike, no shared file, to avoid merge conflicts); section "How the spike is run" (image literal, version recording, throwaway brokers, MQTT login, two-container peak, private helpers, fixture names, three end states per item); section "What the follow-on tasks may rely on"; S-B gets its own class `RealBrokerM9SpikeMqttExchangeTest`. No scenario or spec change at this step: the spec rows are replaced after the run. No product code is allowed to change. No open product question. Next: step 2 (test developer writes the spike tests, red until each fact is asserted), then the run on Docker.
