# M9-13: Gate: a plan built in the planner does on a real broker what its steps say

Milestone: M9    Depends on: M9-01 to M9-10, M9-08, M9-09 (all merged; M9-11 and M9-12 are not part of the gate)    Size: M

PLANNED 2026-11-15. Not started. Re-confirmed 2026-11-19 (step 1 of /iterate).

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
Bounded polling everywhere, no sleep; the statistics lag up to 5 s. Clean up: the container is the clean-up. A policy shows on a queue about 6 seconds after the `PUT` (M9-00c, all queue types): any wait for `policy` or `effective_policy_definition` is a bounded poll of up to 90 seconds, never a fixed delay; and a gate policy must hold only keys its target honours (a policy with one ignored key binds to nothing). The gate uses facts of the three spikes; if a spike left an item unmeasured the gate does not rely on it. At-least-once and a stream as dead-letter target are not in the gate (they are optional parts of `M9-09`).

## UX
None.

## MCP
None.

## Open points
The gate's plan follows the decided answers (retention by policy, Q4; the response stream without MQTT ticks, Q2 and Q3). The MQTT ticks of the stream pair (`M9-07b`) are not part of the gate: they have their own live scenario, which has its own live scenario (S-A6 and S-A7 are measured, M9-00a: the correlation id of a user property arrives as an application property; the owner confirmed the revised Q3 on 2026-11-15, O14). The gate's MQTT route `sensors/+/temp` to `telemetry` uses the measured key `sensors.*.temp`; the gate stays on the default MQTT exchange of its throwaway broker (a broker with `mqtt.exchange` set to another name is a documented risk, S-B2, not a gate case).

## Definition of done
The gate passes on a machine with Docker or rootless Podman, twice in a row; the two mutations were red; the milestone status in `docs/spec.md` and `docs/tasks/README.md` is updated by the architect.

Note (architect, M9-00b step 3, 2026-11-16): the dead-letter part of the gate uses a message that EXPIRES in the source queue (S-C1: arrives with `x-first-death-reason` `expired`; the policy needs about 6 s to show, so a bounded poll). Do not use an at-least-once held dead letter in the gate: it was delivered only 165 to 175 s after the repair in the observations (`docs/spec.md` "Measured facts of M9-00b"). The gate's dead-letter policy is the at-most-once recipe (`dead-letter-exchange` only).

## Status

Step 1 done (architect, 2026-11-19). Step 2 done (test-developer, 2026-11-19): `RealBrokerM9GateTest` written, GREEN at once (see below). Briefed (ready). All of M9-01 to M9-12, M9-14, M9-14b and M9-14c are merged on main bba34a63.
Branch: `M9-13-gate-planner-on-a-real-broker` (worktree `.claude/worktrees/m9-13-test`)

Brief refresh (2026-11-19), nothing else changed:
- Use the merged building blocks, not hand-made actions: the dead-letter part is the `M9-09` recipe (policy on exactly the source queue, at most once, `dead-letter-exchange` only); the retention is the `M9-08` policy step covering the pair; the stream with retention goes through the `M9-14c` export path.
- Do not rely on claims `M9-08e` and `M9-09b` still hold unmeasured (policy over argument precedence, at-least-once with reject-publish-dlx, own-argument read live proof). If the gate needs one, measure it inside the gate or leave it out.
- The scenario exists: `tests/bdd/features/21_topology_planner_recipes.feature` line 1010, tags `@m9-13 @broker @gate-M9`. No new scenario is needed.
- Follow-ups (`M9-08c..f`, `M9-09b`, `M9-12b`, `M9-14c-f1`) do not block the gate: none touches the executor, the check or the allow-list in a way the gate uses. `M9-14c-f1` (executor pre-read of a non-stream body) is XS and has no brief; run it after the gate unless the gate's apply step hits it.
- Not run yet in this milestone: the whole `brokerTest` suite; run M5, M7, M8 gates once before the merge proposal because the executor's reader and `ManagementHttp` changed during M9.

Step 2 (test-developer, 2026-11-19):
- Written: `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerM9GateTest.kt` (tags `broker`, `gate-M9`), one test named as the scenario. Real `PlannerViewModel` and its forms (the wizard calls of the screen), real `ManagementDeclareCheck` and `ManagementBrokerPolicyReader` behind a request recorder, real `FolderMigrationFileRepository` and `FilePlanDraftStore` in temp folders, real `MigrationRunner` and `ManagementTopologyMigrationExecutor` (behind a recorder), `RabbitMqConnectionGateway` for the connection. Broker: `m9SpikeBroker()` (rabbitmq:4-management with stream, stream management and MQTT plugins), plain profile.
- Run: `./gradlew :data-rabbit:brokerTest --tests '*M9Gate*'` passes in about 25 s (Docker present). It is NOT red at step 2: every block the gate uses is merged, so there is no missing production behaviour to wait for.
- Proof that it can fail: mutation 2 (the `+` of the MQTT filter translated to `#`, temporary, reverted) is RED with "the stream telemetry holds the messages whose topic the route sensors/+/temp matches, and only those: expected [MATCH, CONTROL] but was [NO-MATCH-TWO-LEVELS, NO-MATCH-NO-LEVEL, MATCH, CONTROL]". The first version of the test let this mutation through (it published no multi-level topic); it now publishes the three topics the filter must not match.
- Mutation 1 (the dead-letter policy moved before its exchange, queue and binding) is NOT red: the apply works, the check shows every step "Will be created" then "Already there", the expired message reaches `orders-dlq`. The broker resolves `dead-letter-exchange` when a message dies, not when the policy is set. The gate does catch the order of the recipe's steps by asserting the names of the plan in plan order (policy last). Question to the architect: keep the order assertion as the guard, or drop mutation 1 from the brief.
- Not relied on: `M9-08e` and `M9-09b` claims. Not in the test: the at-least-once strategy, a stream as dead-letter target, the MQTT ticks of the stream pair.
- Not recorded: the planner's read of the broker's names (`HeldKnownTopologyReader` in the test, same catalogs as `CatalogKnownTopologyReader` of the app, list calls) and the stream-protocol part of the apply (stream creation). `CatalogKnownTopologyReader` lives in `desktopApp`, which `data-rabbit` tests cannot use, so the test has its own twin of it.
