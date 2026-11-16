# Check: the measured rule for queue and exchange arguments

Milestone: M9    Depends on: M9-05, M9-00c (S-G1 to S-G4)    Size: S

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
Read `docs/tasks/M9-00-spike-results.md` first. Do not guess a row the spike did not measure: add the live test instead. GET only, same allow-list, no new path.

## UX
None.

## MCP
None, same reason as `M9-05` (owner decision 2026-11-15, no MCP for the planner).

## Open points
- Waits for `M9-00c`. If S-G4 says a read cannot predict some difference, see the open point of `M9-00c`.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated.
