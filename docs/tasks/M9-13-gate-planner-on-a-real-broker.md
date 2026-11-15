# M9-13: Gate: a plan built in the planner does on a real broker what its steps say

Milestone: M9    Depends on: M9-01 to M9-10 (M9-11 and M9-12 are not part of the gate)    Size: M

PLANNED 2026-11-15. Not started.

## Goal
One live test, `RealBrokerM9GateTest` (`@gate-M9`, tag `broker`), builds a plan through the planner's core as a user would, checks it, saves it, applies it with the existing runner and executor on a real RabbitMQ 4.x broker, checks it again, and proves with messages that the route and the dead-letter path work.

## Why
Spec milestones table, M9 gate. Every recipe is a claim about broker behaviour; the fast tests prove the actions, only a live test proves the effect.

## Scope
In: the test class in `data-rabbit` (Testcontainers, `rabbitmq:4-management`, MQTT plugin, the real `RabbitMqConnectionGateway`, `PlannerViewModel` or the forms of `core.planner` with a temp folder as the migrations folder and a temp draft store, the real `MigrationRunner`, `ManagementTopologyMigrationExecutor` and `ManagementDeclareCheck`). One happy path:
1. A quorum queue `orders` exists before the plan (created by the test).
2. The plan: a direct exchange and a binding to `orders`; dead-lettering for `orders`; a stream `telemetry` with its response stream; a retention policy; an MQTT route `sensors/+/temp` to `telemetry`.
3. Check: every new step "will be created"; the recorded requests of the check are GET only.
4. Save as migration: the file is in the folder, parses, and holds no user and no hash.
5. Apply with the runner: recorded as applied.
6. Check again: every step "already there".
7. An MQTT publish to `sensors/room1/temp` arrives in `telemetry` (management `messages`, bounded polling).
8. A message published to `orders` with a short per-message expiration arrives in the dead-letter queue (count by the management API; content, if looked at, through the requeue peek only).
9. `telemetry-dln` exists and is empty; nothing was deleted (the queues, exchanges and policies that existed before still exist).

Out: mutation runs beyond two (as `M8-11`); the UI; a cluster.

## Acceptance
- BDD: `21_topology_planner_recipes.feature` / "A plan built in the planner does on a real broker what its steps say" (@broker @gate-M9)
- The test fails with a message that names the step that broke (which check result, which count).
- `RealBrokerM5GateTest`, `RealBrokerM7GateTest` and `RealBrokerM8GateTest` are run once before the merge proposal if shared code changed in the milestone (the executor's reader, the allow-list, `ManagementHttp`); CLAUDE.md asks to keep M7 and M8 green when queue reads or the Shovel are touched, which M9 does not do.
- At most two live mutation runs: the policy placed before the exchange (the dead-letter count must fail or the check must show it), and a wrong translation of `+` (the MQTT count must fail).

## Design notes
Bounded polling everywhere, no sleep; the statistics lag up to 5 s. Clean up: the container is the clean-up. The gate uses facts of the three spikes; if a spike left an item unmeasured the gate does not rely on it. At-least-once and a stream as dead-letter target are not in the gate (they are optional parts of `M9-09`).

## UX
None.

## MCP
None.

## Open points
The gate's plan follows the decided answers (retention by policy, Q4; the response stream without MQTT ticks, Q2 and Q3). The MQTT ticks of the stream pair (`M9-07b`) are not part of the gate: they have their own live scenario, because the revised Q3 is still to be confirmed and S-A6 and S-A7 are open.

## Definition of done
The gate passes on a machine with Docker or rootless Podman, twice in a row; the two mutations were red; the milestone status in `docs/spec.md` and `docs/tasks/README.md` is updated by the architect.
