# M9-10c: A hint when the broker's MQTT exchange is not amq.topic (optional)

Milestone: M9    Depends on: M9-10 (the assumption sentence), M9-05 (the check's GET calls); decision of the owner (review list item 9)    Size: S

PROPOSED 2026-11-16 by the architect at step 3 of `M9-00a`. Not started and not scheduled. DECIDED (owner, 2026-11-16: "do arch suggest", item 9 of the `M9-00a` review list): build only the assumption sentence of `M9-10` now; whether `M9-10c` is built is decided after the first slice, and it stays unscheduled until then.

## Goal
When the plan holds an MQTT route and the broker shows that its MQTT plugin uses an exchange other than `amq.topic`, the planner says so next to the route instead of saying nothing.

## Why
Spec "Measured facts of M9-00a", S-B1 and S-B2: the MQTT exchange cannot be read through the management API, and on a broker with `mqtt.exchange` set to another name a route saved on `amq.topic` is applied without error and receives nothing from MQTT. The one trace measured (S-A1 fixture, `RealBrokerM9SpikeMqttExchangeTest`): the binding of an MQTT subscriber's queue (`mqtt-subscription-<clientId>qos1`) has the MQTT exchange as its `source`. With no MQTT subscriber there is no trace, so this is a hint, never a guarantee.

## Scope
In:
- A pure function in `core.planner`: from the bindings of the virtual host (already read by the catalogs of `M9-04` or a new GET `/api/bindings/{vhost}` through the executor's allow-list) take the distinct `source` values of bindings whose destination queue name starts with `mqtt-subscription-`; if the set is not empty and does not contain `amq.topic`, return a `MqttExchangeHint.Other(names)`. If it contains `amq.topic` or is empty, return nothing (no claim either way).
- A note under an MQTT route step and in the check result (a note, never a blocker). Wording is the owner's (proposal: `RabbitMQ's MQTT subscribers on this broker use the exchange <name>, not amq.topic. This route may receive nothing from MQTT.`), tagged `@needs-decision`.
Out: reading the plugin's configuration (not possible through the API, S-B1); declaring or changing an exchange; choosing another exchange for the route (a later decision: the first version only warns); any MQTT connection by the app.

## Acceptance
- BDD (to write by the test developer at step 2, `tests/bdd/features/21_topology_planner_recipes.feature`, tag `@m9-10c`, `@needs-decision` for the text): "A route on amq.topic is flagged when MQTT subscribers use another exchange", "No claim without MQTT subscriber queues", "No note when the subscribers use amq.topic".
- Unit tests, first: `MqttExchangeHintTest` (the three cases; a destination named `mqtt-subscription-` on a classic and a quorum queue; many bindings, performance on 10,000 rows).
- Live: extend `RealBrokerM9SpikeMqttExchangeTest`-style check in a `RealBrokerPlannerMqttExchangeHintTest` (tag `broker`): on the `custom.mqtt` broker a subscriber appears, the hint names `custom.mqtt`; on the default broker it names nothing. Also run it as a user with the `monitoring` tag (M9-00a-b item 4 measures whether such a user sees these bindings).

## Design notes
GET only; 4.x with a connection only, never on 3.x (the planner guardrails). The result is advisory and cached with the other catalogs. No secret is involved; queue names of MQTT clients contain client ids, which are shown only inside the note's count, not listed.

## UX
Next to the MQTT route form and in the check list; no new screen. Mockup change needed before step 2 (the architect adds it if the owner says yes).

## MCP
None: a planner note, not a tool.

## Open points
Needs the owner's answer (review list item 9). Needs M9-00a-b item 4 (the non-administrator view of the bindings) before step 2.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
