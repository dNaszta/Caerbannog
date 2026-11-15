# M9-00c: Spike on a live 4.x broker: policy keys per target, one policy per queue, declare equivalence, names

Milestone: M9    Depends on: the owner's review of the M9 plan (nothing else)    Size: M

PLANNED 2026-11-15. Not started. Runs in parallel with `M9-00a` and `M9-00b`.

## Goal
The items S-F1, S-F2, S-G and S-H of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured, so that the policy form offers only settings that work (`M9-08`), the plan can warn about a policy that hides another (`M9-08`, `M9-09`), and the read-only check predicts what a declare will answer (`M9-05`).

## Why
M8-00 (S-E) measured which policy keys RabbitMQ 4.4 accepts at all, not which take effect for which queue type, and not for streams. The check of `M9-05` must say "Already there" or "Exists with different settings" from reads alone; today only the broker's own answer to a repeated declare decides that (the executor's conflict rule), and the read-back matcher (`DeclaredResourceMatcher`) deliberately ignores arguments the broker has in addition.

## Scope
In: `@broker` spike tests in `data-rabbit`, fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the shared results file `docs/tasks/M9-00-spike-results.md`, spec and scenario corrections.

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
