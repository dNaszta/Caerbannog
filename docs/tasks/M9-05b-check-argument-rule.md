# Check: the measured rule for queue and exchange arguments

Milestone: M9    Depends on: M9-05 (done), M9-00c (done, measured 2026-11-16: `docs/tasks/M9-00c-spike-results.md`)    Size: S

## Goal
The check says "Already there" or "Exists with different settings" for a queue and an exchange by the rule that predicts the apply (the broker's 204 or 400, then the executor's read-back), as measured by spike S-G, a `200` with a JSON list is "Could not be checked", and the live scenarios prove both against a real broker.

## Why
`M9-05` shipped with `DeclaredResourceMatcher` as it was (declared arguments must be present and equal, every extra argument on the broker ignored). The spike measured that one case is wrong: an argument the broker's type HONOURS that is on the broker and missing from the declare is a 400 on the repeated declare, but reads "Already there" today. Everything else the matcher does agrees with the apply (see "The rule"); the matcher is NOT changed, the check gets one more function (see Scope). A second finding: `GET /api/queues/%2F/%2E` and the exchange path answer `200` with a JSON list (the vhost listing), which today reads "Exists with different settings" (queue, exchange, stream) or "Will be deleted" (a delete step).

## The rule (spec "Check against the broker", written by the architect from the measurement)
A queue or exchange step is "Already there" only when ALL hold:
1. `type` (a queue: `type` of the GET against the action's type), `durable` and `auto_delete` are equal; an exchange also `internal`.
2. Every DECLARED argument is on the broker with the same wire type and value (today's matcher, unchanged). This also covers an argument the broker's type ignores: the broker answers 204 and stores nothing, then `ReadBack` (same matcher) stops the apply with "was found with different settings after it was declared", so "Exists with different settings" is the right prediction.
3. NEW: no argument that the broker's type HONOURS is on the broker while the declare lacks it. Honoured sets: `DeclareEquivalenceTable.queueComparedArguments[type]` and `exchangeComparedArguments`; `x-queue-type` is judged by rule 1, not here; a limit that a policy sets is not in `arguments` and never counts. An argument on the broker that the type does not honour is ignored (the broker answers 204 and the read-back is fine).
A stream step stays "the item is of type stream" (a product-made stream has `x-queue-leader-locator: balanced`; it is not compared). A `declareQueue` of type stream is judged by the same three rules (its honoured set holds the leader locator, so a bare declare against a product-made stream is "different", as the broker's 400 says).

## Scope
In:
- Rule 3 as a function beside `DeclaredResourceMatcher`, called by `ManagementDeclareCheck` only (new file or a small object, `internal`). The matcher the executor's read-back uses stays UNCHANGED: after a successful first declare the read-back must keep passing even if a broker adds an honoured argument by itself (not seen in the spike, so not excluded for every version); the check's rule 3 is the prediction of the 400 that the apply's PUT would get, which the read-back never sees.
- `ManagementDeclareCheck`: a `200` whose body is a JSON list on a queue, exchange, stream, policy or operator-policy path is `BrokerFinding.Unreadable(ExecutorReasons.NO_ANSWER)` (the fixed text that exists), also for a delete step; a binding keeps its list handling.
- `core-testing`: `FakeManagementBroker` is made honest in two places and its existing tests stay green: (a) `GET` of a queue or exchange named `.` or `..` answers `200` with a JSON list (today it answers 404, which is wrong); (b) a repeated `PUT` of a queue or exchange compares `durable`, `auto_delete`, the type, and only the honoured arguments of `DeclareEquivalenceTable` (today it answers 400 for ANY difference in arguments, which the broker does not). The fake reads the table; it holds no copy.
- A table test over every row of `DeclareEquivalenceTable` for queue and exchange (the first column of rows: the expected answer is the row's status; the check's finding must be "Same" exactly when the broker answers 204 AND the read-back would pass, "Different" otherwise; the rows "unknown argument added/other value" are the ones where the two differ, name them in the test).
- `RealBrokerPlanCheckTest`: binds the two scenarios below by name.
Out: changing what the apply sends or says; operator-policy comparison; any write; a new path (GET only, the same allow-list).

## Acceptance
- BDD: `20_topology_planner_check.feature` / "What the check calls different is what a real broker refuses" (outline, 5 rows; @m9-05b @broker; `@needs-spike` removed by the architect)
- BDD: `20_topology_planner_check.feature` / "A name that the management API answers with a list is not read as a queue" (@m9-05b @broker)
- Unit: the table test above; `ManagementDeclareCheckTest` additions (a list body is Unreadable for each of the five declare kinds and a delete step; a classic queue made by the builder reads "Same" after the apply (the live test checks quorum, classic and stream: `x-queue-version` or another broker default must not appear as an honoured extra; if it does, STOP and report to the architect, the rule changes)); `FakeManagementBrokerTest` (the two honesty rows).
- `M9-05`'s tests and the 16 `@m9-05` fake scenarios stay green (the parity matrix of 324 combinations must be updated only where rule 3 changes an expected value, and each such row is named in the commit).

## Design notes
Read `docs/tasks/M9-00c-spike-results.md` first (S-G1, S-G2, S-G4, S-H). Do not guess a row the spike did not measure: add the live row instead (the 20 candidate arguments are the classified ones; any other argument is unclassified: treat it as ignored, and add a live row before claiming otherwise). The three-dots name `...` was not measured: add it to the list scenario's rows (`%2E%2E%2E`) with a live answer before asserting anything about it. GET only, same allow-list, no new path.

## UX
None.

## MCP
None, same reason as `M9-05` (owner decision 2026-11-15, no MCP for the planner).

## Open points
- Wording: the apply's text for rule 2 ("was found with different settings after it was declared") differs from the check's "Exists with different settings"; no change (DECIDED, owner 2026-11-16, Owner review list item 8, `M9-00c`: keep both).
- A wrong "Already there" for an argument outside the 20 classified ones is harmless (the apply stops, nothing changes) and is reported as such.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated. The `@broker` scenarios were RUN, or the final report says in plain words that they were not.

Note (architect, M9-00c step 3, 2026-11-16): replaces the earlier version of this brief. Corrections to M9-05: its decision 3 and step 5 note said a `.` or `..` name would read `Unreadable(NOT_A_REQUEST)`; the code encodes the name as `%2E` and the allow-list lets it through, so today it reads "different". The case is unreachable from the app (forms, `PlanValidation`, parser refuse these names), so this is hardening, not a bug in a shipped path.
