# M9-00c: Spike on a live 4.x broker: policy keys per target, one policy per queue, declare equivalence, names

Milestone: M9    Depends on: none (the owner answered the open questions on 2026-11-15)    Size: M

PLANNED 2026-11-15, confirmed for the live run 2026-11-16 (a container runtime exists now). Runs in parallel with `M9-00a` and `M9-00b`; it is the first of the five live tasks to start because `M9-05b` waits for its S-G results. See "How the spike is run".

## Goal
The items S-F1, S-F2, S-G and S-H of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured, so that the policy form offers only settings that work (`M9-08`), the plan can warn about a policy that hides another (`M9-08`, `M9-09`), and the read-only check predicts what a declare will answer (`M9-05`).

## Why
M8-00 (S-E) measured which policy keys RabbitMQ 4.4 accepts at all, not which take effect for which queue type, and not for streams. The check of `M9-05` must say "Already there" or "Exists with different settings" from reads alone; today only the broker's own answer to a repeated declare decides that (the executor's conflict rule), and the read-back matcher (`DeclaredResourceMatcher`) deliberately ignores arguments the broker has in addition.

## Scope
In: `@broker` spike tests in `data-rabbit`, fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the results file `docs/tasks/M9-00c-spike-results.md` (one file per spike), spec and scenario corrections.

| Item | Question | Output | Used by |
| --- | --- | --- | --- |
| S-F1a | For each key (`max-length`, `max-length-bytes`, `overflow` with its three values, `message-ttl`, `expires`, `dead-letter-exchange`, `dead-letter-routing-key`, `dead-letter-strategy`, `delivery-limit`, `queue-leader-locator`, `target-group-size`, `max-age`, `stream-max-segment-size-bytes`, `alternate-exchange`) and each `apply-to` (`queues`, `quorum_queues`, `classic_queues`, `streams`, `exchanges`, `all`): status of the policy `PUT` | accept matrix, as a shared constant for the fake | `M9-08` |
| S-F1b | For a quorum queue, a classic queue and a stream matched by such a policy: which keys appear in `effective_policy_definition` (take effect) | effect matrix | `M9-08` (the list of offered settings per target) |
| S-F1c | Values of the wrong type (text for a number, a number for `overflow`, a malformed `max-age`): status and whether anything is stored | status table | `M9-08` (form validation) |
| S-F1d | Retention of a stream set by policy on a stream that already exists: `effective_policy_definition`, the stream's `arguments`, and what the app's stream list would show (it reads `arguments.x-max-age` today) | note; a follow-up if the Retention column would stay empty | `M9-08` (Q4), a possible follow-up for the stream list |
| S-F2a | Two policies that match one queue, with different and with equal priority: which applies; the queue's `policy` and `effective_policy_definition` | rule in one sentence, fixtures | `M9-08`, `M9-09`, `M9-05` |
| S-F2b | A policy next to an operator policy on one queue: how they combine | note | `M9-08` |
| S-G1 | Queue: declare, then `PUT` again with the same body, with one argument less, one more, another value, a number sent as text, another type, another `durable` and `auto_delete`: 204 or 400 for each | table "repeated declare", as a shared constant | `M9-05` (the rule for "the same settings") |
| S-G2 | Exchange: the same for type, `durable`, `auto_delete`, `internal` and arguments (`alternate-exchange`) | table | `M9-05` |
| S-G3 | Stream: the stream manager's create on a stream that exists; on a name that is a quorum queue; and a `PUT` of a queue on a name that is a stream | answers (typed result of `StreamManager`, status) | `M9-05` |
| S-G4 | Does `GET /api/queues/{vhost}/{name}` and `GET /api/exchanges/{vhost}/{name}` return everything S-G1 and S-G2 show the broker compares (so a read alone can predict the answer) | yes, or the list of what is missing | `M9-05` |
| S-H | Names: a queue, an exchange, a stream and a policy named `amq.test`; a name of 255 and of 256 bytes; status and reason code for each | table | `M9-01` (what blocks in the form), `M9-05` |

Out: product code; operator policies in the wizard; clusters.

## Acceptance
- BDD: none run by this task. It unblocks the `@needs-spike` scenarios of `tests/bdd/features/21_topology_planner_recipes.feature` tagged `@m9-08` ("The form offers only the settings measured for what the policy applies to", "Two policies of a plan that match the same queue are pointed out", "A policy that would take the place of a policy on the broker is pointed out by the check", "Retention of a stream is saved as a policy", "The response stream gets the same retention unless I untick it") and of `tests/bdd/features/20_topology_planner_check.feature` ("What the check calls different is what a real broker refuses").
- Spike test classes `RealBrokerM9SpikePolicyKeysTest`, `RealBrokerM9SpikeDeclareEquivalenceTest`, `RealBrokerM9SpikeNamesTest` assert the findings; the two matrices are constants in `core-testing` that the fake broker and the live test share (the pattern of `ManagementStatusTable`, M5-08a).
- `docs/spec.md`: rows S-F1, S-F2, S-G and S-H replaced by the measured facts; "Check against the broker" states the measured rule for "the same settings"; A4 loses "UNVERIFIED" for the one-policy rule or is rewritten.

## Design notes
Follow `RealBrokerM5MigrationTest` (the 35-row measured status table, constant `ManagementStatusTable` in `core-testing`) and the matrices of the M8-00 spike classes (`RealBrokerM8Spike*Test`). Create each queue fresh per row so one row cannot influence the next. `effective_policy_definition` appears after the policy is applied: wait for it by bounded polling. Record the floated image version.

## UX
None.

## MCP
None (spike).

## Open points
- If S-G4 says a read cannot predict the broker's answer for some difference, the check reports that case as "Already there" only when every compared field is equal, and otherwise as "Exists with different settings", and the spec says which differences the check cannot see. A wrong "Already there" is harmless (the apply stops with the conflict and changes nothing); it is still reported honestly.
- If S-F2a shows that policies do combine, the overlap warnings of `M9-08` and `M9-09` are dropped and A4 is rewritten.

## Definition of done
Spike tests pass on a machine with Docker or rootless Podman; fixtures and shared constants committed; results written; spec, scenarios and the briefs `M9-05`, `M9-08`, `M9-09` corrected by the architect; no product code changed.

## How the spike is run (added at step 1, 2026-11-16)
- **Broker image:** the literal `rabbitmq:4-management` (no image constant exists; follow `RealBrokerM5MigrationTest` and `RealBrokerM8SpikeDefinitionsTest`). Plugins `rabbitmq_stream` and `rabbitmq_stream_management` through `brokerWithPlugins` (S-G3 needs the stream manager); `MgmtApi`, `awaitUntil` of `M8SpikeSupport.kt` are reused. The first test of each class asserts `rabbitmq_version` starts with `4.`; the results file records the exact RabbitMQ and Erlang version, the architecture and the date.
- **Throwaway brokers only; fresh resource per row** (a queue, exchange or stream with a name of its own), so one row cannot influence the next. `effective_policy_definition` is waited for by bounded polling. No bare sleep.
- **Request shapes:** S-G1 to S-G3 send the same bodies the app's executor sends (`declareQueue`, `declareExchange`, `StreamManager` create), taken from the real request builders where they are reachable from tests, so the table describes what the product would meet, not what a hand-written body does.
- **Shared constants (core-testing):** this task creates the two tables and nothing else in `core-testing`: `PolicyKeyMatrix` (S-F1a accept and S-F1b effect, per key and `apply-to`) and `DeclareEquivalenceTable` (S-G1 and S-G2, with the S-G3 answers), in the shape of `ManagementStatusTable`; `RealBrokerM9Spike*Test` asserts the live answer equals the constant row by row. `M9-05b` and `M9-08` read these constants and must not re-measure them. Adding constants to `core-testing` is test support, not product code.
- **Names (S-H):** include the names `.` and `..` for a queue, an exchange and a stream via the same `GET /api/queues/{vhost}/{name}` and `GET /api/exchanges/{vhost}/{name}` the check uses (HTTP status and body of the real GET), because the fake answers Absent and the answer was never measured (architect note of `M9-05` step 5). One line in the results file for each, even if the broker answers 400 or 404. Only GET; the declare of such names is part of the S-H table anyway.
- **Resources:** one container, medium load (many short rows). The results file is `M9-00c-spike-results.md`; fixtures are named `s-f1-...`, `s-g1-...`, `s-h-...`.
- **Every item ends in one of three states** in the results file: measured (test method named), not measurable (why), measured with a surprise.

## What the follow-on tasks may rely on (the contract of this spike)
- `M9-05b` may rely on: the `DeclareEquivalenceTable` rows for a queue and an exchange (S-G1, S-G2: same, fewer, more, other value, a number sent as text, another type, other flags) and S-G4 (whether a GET returns everything the broker compares; the list of what a read cannot see). It starts only after these results exist (see the dependency note in `M9-05b`).
- `M9-08` may rely on: the accept and effect matrices (S-F1a, S-F1b), the wrong-type table (S-F1c), the stream retention note (S-F1d), the one-policy rule (S-F2a) and the operator-policy note (S-F2b).
- `M9-09` may rely on: S-F2a. `M9-01` and `M9-05` may rely on S-H (what a name may be, the `amq.` rule, 255 and 256 bytes).

## Product questions
None.

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-00c-spike-policies-equivalence-names`, base main `652c42a`): brief checked against the spec ("Facts to verify first" S-F, S-G, S-H; "Check against the broker"; A4), the code (`DeclaredResourceMatcher`, `ManagementStatusTable`, `RealBrokerM5MigrationTest`, `M8SpikeSupport.kt`) and the `M9-05` and `M9-05b` briefs. Brief CHANGED: results go to `M9-00c-spike-results.md` (one file per spike); section "How the spike is run" (image literal, version recording, fresh resource per row, request bodies from the real builders, the two `core-testing` constants named `PolicyKeyMatrix` and `DeclareEquivalenceTable`, the real GET answers for the names `.` and `..` added to S-H, resources, three end states); section "What the follow-on tasks may rely on"; the dependency: it is the first of the five live tasks to start because `M9-05b` waits for it. No product code may change. No open product question. Next: step 2 (test developer).
- Plan for the five live tasks of 2026-11-16 (architect): `M9-00a`, `M9-00b`, `M9-00c` and `M9-14` are independent of each other; `M9-05b` waits for `M9-00c`. One Docker VM of 7.7 GiB and 8 CPUs: at most TWO broker test runs at the same time (a RabbitMQ container with plugins takes roughly 0.5 to 1 GiB; Gradle, Kotlin daemons and Testcontainers' own overhead come on top). Waves: (1) `M9-00c` with `M9-14` (one container each; `M9-00c` first because it gates `M9-05b`); (2) `M9-00a` with `M9-00b` (`M9-00a` peaks at two containers only in its S-B2 class: run that class when `M9-00b` is idle, or last); (3) `M9-05b` last, after `M9-00c` is merged or merged into its branch, together with whichever of wave 2 is still running. Steps that need no broker (briefs, unit tests, fixtures' parsing, reviews) may overlap freely. Never two Gradle `brokerTest` runs in one worktree.
