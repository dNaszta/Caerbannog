# Check: the measured rule for queue and exchange arguments

Milestone: M9    Depends on: M9-05 (merged on main), M9-00c (S-G1 to S-G4; its results and the `DeclareEquivalenceTable` constant must exist on this branch before step 2)    Size: S

## Goal
The check says "Already there" or "Exists with different settings" for a queue and an exchange by the rule the broker itself applies to a repeated declare, as measured by spike S-G, and the live scenario proves it against a real broker.

## Why
`M9-05` ships with the comparison `DeclaredResourceMatcher` has today (declared arguments must be present and equal, extras on the broker ignored). Whether a real broker refuses a repeated declare in that case is unverified (spec "Facts to verify first", S-G). Until measured, a wrong "Already there" is possible but harmless: the apply keeps its own conflict rule.

## Scope
In: the rule for arguments (fewer, more, another value, number as text) and flags, placed beside `DeclaredResourceMatcher` and tested against the measured table; the table as a constant in `core-testing` shared by `FakeDeclareCheck` scripts and the live test (pattern `ManagementStatusTable`); `RealBrokerPlanCheckTest` binds "What the check calls different is what a real broker refuses"; spec "Check against the broker" states the rule and what a read cannot see (if S-G4 finds such a case, that case is reported as "Exists with different settings" and the spec says so).
Out: the executor's own read-back matcher must not change unless the spike shows it wrong; then it is a separate decision for the architect.

## Acceptance
- BDD: `20_topology_planner_check.feature` / "What the check calls different is what a real broker refuses" (@m9-05b @broker @needs-spike; the architect removes `@needs-spike` and corrects the scenario to the measurement).
- Unit: a table test over every S-G1 and S-G2 row, expected answer taken from the measured constant.
- `M9-05`'s tests stay green; any scenario outcome that the measurement changes is corrected in `20` by the architect first.

## Design notes
Read `docs/tasks/M9-00c-spike-results.md` first. Do not guess a row the spike did not measure: add the live test instead. GET only, same allow-list, no new path.

## UX
None.

## MCP
None, same reason as `M9-05` (owner decision 2026-11-15, no MCP for the planner).

## Open points
- Waits for `M9-00c`. If S-G4 says a read cannot predict some difference, see the open point of `M9-00c`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated.

Note (architect, M9-05 step 5, 2026-11-16): while measuring, also record what a real GET answers for the names `.` and `..` (the fake says Absent; the app never sends them, M9-01 refuses them) so the fake and the check stay honest. Not a scope widening: one line in the spike log.

## Dependency, start and what this task measures (added at step 1, 2026-11-16)
- **Start order.** This task starts AFTER `M9-00c` (branch `m9-00c-spike-policies-equivalence-names`): step 2 may begin only when `docs/tasks/M9-00c-spike-results.md` exists with S-G1, S-G2 and S-G4 measured and `DeclareEquivalenceTable` is in `core-testing`. Step 1 (this brief) needs nothing from the spike. The worktree of this task is cut from main; before step 2 the coordinator brings the `M9-00c` commits in (merge `m9-00c-spike-policies-equivalence-names` into this branch, or merge it to main on the owner's word and rebase). If S-G cannot be measured, this task stops and the architect decides; it must not guess a row.
- **The constant is created by `M9-00c`, used here.** The brief's earlier wording ("the table as a constant in `core-testing`") is clarified: `M9-00c` writes `DeclareEquivalenceTable` (one row per measured difference: same, fewer, more, another value, a number sent as text, another type or flag; queue and exchange; with the broker's answer, 204 or 400, and its reason); this task adds the consumers: the pure rule beside `DeclaredResourceMatcher`, the `FakeDeclareCheck` scripts and the live scenario read that one constant. No second copy of a row.
- **What is measured today and what is prediction.** `DeclaredResourceMatcher` (used by the executor's read-back and by `ManagementDeclareCheck`) treats arguments the broker has in addition as equal: so "Already there" is a PREDICTION, not the broker's own rule. This task measures the real rule with these rows of S-G (listed here so the test developer can see the gap between prediction and broker): **S-G1** a queue declared again with the same body (expected 204), with one argument fewer (the broker's answer decides whether the matcher's "extras ignored" is right in this direction), with one argument more, with another value, with a number sent as text (`"5"` for `5`), with another type of argument, with another `durable` and `auto_delete`; **S-G2** the same for an exchange (type, `durable`, `auto_delete`, `internal`, `alternate-exchange`); **S-G4** whether `GET /api/queues/{vhost}/{name}` and `GET /api/exchanges/{vhost}/{name}` return everything the broker compares. The expected outcome table (check answer against broker answer) is in the task's unit test: a row where the matcher says "Already there" and the broker answers 400 is a bug of the matcher for the check, fixed here beside the matcher; a row where the matcher says different and the broker answers 204 is also fixed (the check must not cry wolf).
- **The executor's own read-back stays as it is** (Out above). If a row shows it wrong, the architect decides separately; this task only records the finding.
- **Live scenario.** `RealBrokerPlanCheckTest` binds "What the check calls different is what a real broker refuses" (a new test method; the scenario in `20` loses `@needs-spike`, keeps `@m9-05b @broker`). The architect corrects the scenario's example to the measured rule at step 2.5 if the S-G table says the delivery-limit example (3 against 5) is not what a real broker refuses on this version. The `@broker` image is the literal `rabbitmq:4-management` like the existing methods of that class.
- **The names `.` and `..`.** M9-00c's S-H records the real GET answer for them (queue and exchange). This task copies that answer into `FakeDeclareCheck` and `DeclareCheckTest` only if it differs from "Absent" (today the fake says Absent); otherwise the fake stays and a one-line note says so in the results of this task. The app still never sends such names (M9-01, plan validation and the parser refuse them).
- **Also run, not in scope:** `RealBrokerPlanCheckTest`'s first scenario ("The check agrees with a real broker before and after applying") was NOT RUN when `M9-05` was accepted (no container runtime). Now that Docker exists, step 2 runs the whole class once and reports both outcomes; a failure of the first scenario is a finding for the architect (it belongs to `M9-05`), not something this task fixes silently.

## Product questions
None. (A change of the check's wording is not planned: the wording is decided by Q14. If the measured rule makes a sentence untrue, the architect proposes the change and asks the owner.)

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-05b-check-argument-rule`, base main `652c42a`): brief checked against the spec ("Check against the broker", S-G), the code (`DeclaredResourceMatcher`, `PlanCheck`, `ManagementDeclareCheck`, `RealBrokerPlanCheckTest` all on main from `M9-05`) and the `M9-00c` brief. Brief CHANGED: the dependency made explicit (starts after `M9-00c` results and `DeclareEquivalenceTable` exist on the branch), who creates the constant (`M9-00c`) and who uses it (this task), the S-G rows listed with the prediction-against-measurement framing, the executor's matcher unchanged, the `.` and `..` answer recorded by `M9-00c` and copied only if it differs, the unrun first `@broker` scenario of `M9-05` run once here. No open product question. Next: wait for `M9-00c`, then step 2 (test developer).
