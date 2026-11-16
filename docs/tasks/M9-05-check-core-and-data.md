# M9-05: Check against the broker: the read-only port, the plan check, the management implementation

Milestone: M9    Depends on: M9-00c (S-G, S-H), M9-01, M9-02    Size: M

PLANNED 2026-11-15. Not started. The first commit (extracting the dry-run rules) needs no spike; the comparison rule waits for S-G.

## Goal
For every step of a plan the app can say, from reads alone, what the broker has (absent, the same, different, unreadable) and whether the existing dry run blocks the step. The port is in `core`, the implementation in `data-rabbit` sends GET requests of the executor's allow-list only, and a 3.x connection has no such port.

## Why
Spec "Topology planner (M9)", "Check against the broker" and A6. Today's dry run (`MigrationRunner.dryRun`) says only allowed or blocked; it does not look at the broker. The owner's concept: check per step before the existing Apply.

## Scope
In:
- `core`, package `dev.caerbannog.core.migrations` (it is about actions, not about the planner only):

```kotlin
/** What an action would meet on the broker right now. Read-only: an implementation sends GET requests only and never a path under a queue's name. */
interface DeclareCheck {
    suspend fun find(action: MigrationAction): BrokerFinding
}
sealed interface BrokerFinding {
    data object Absent : BrokerFinding
    data object Same : BrokerFinding
    data object Different : BrokerFinding
    /** [reason] is one of the executor's fixed texts ("the broker did not answer", "the user is not allowed to do this", ...). */
    data class Unreadable(val reason: String) : BrokerFinding
}
```

- First commit, a pure move with no test edited: the rules inside `MigrationRunner.dryRun` and `dryRunPerAction` become a top-level pure function (`MigrationDryRunRules.evaluate(actions, hasConfigurePermission, controlLevel, vhost, userTags)`) that the runner calls, so the planner can use the same rules without a runner, a history or an executor.
- `core`, package `dev.caerbannog.core.planner`: `PlanCheck.run(plan, context, check: DeclareCheck?, onStep)` returning one `StepCheck(stepId, finding, blocked, notes)` per step. `StepFinding`: `WillBeCreated`, `AlreadyThere`, `Different`, `Missing(what)`, `NotChecked(reason)`, and for a delete step `WillBeDeleted`, `AlreadyGone`. Rules: `blocked` is the dry-run reason of that action; a step whose virtual host is not the connection's is blocked and not read; a binding whose exchange or destination is neither on the broker nor declared by an earlier step is `Missing` with the fixed sentence (the ends are probed with `find` on a declare of that name: `Absent` means it is not on the broker, `Same` or `Different` means it is, `Unreadable` makes the step `NotChecked`); an `Unreadable` is never turned into "will be created"; with no `DeclareCheck` (3.x, unknown version, not connected) the whole check is refused with the connection's reason and nothing is sent.
- `data-rabbit`: `ManagementDeclareCheck(reader: ResourceReader)`: one GET per action through `ExecutorPathAllowList` (the path of `MigrationReadRequests`), 404 is `Absent`, 200 is compared, 401 and 403 and every failure is `Unreadable` with the text of `ExecutorReasons.forReply`. Comparison: a policy with `ConflictCheck.comparePolicy`; a binding with `DeclaredResourceMatcher` (key and arguments; another binding between the same two is `Absent`); a stream is `Same` when the item is of type stream and `Different` when it is another kind of queue; a queue or exchange by the rule S-G measures (type and flags as today, arguments by the measured table). `HeldConnection.declareCheck: DeclareCheck?` and `StreamSource.declareCheck`, null exactly when `migrationExecutor` is null (same one `GET /api/overview`, `MigrationAccess`), wrapped by the same "still current" guard as `GuardedMigrationExecutor`.
- `core-testing`: `FakeDeclareCheck` (scripted per action, records every call).

Out: the screen (`M9-06`); policy overlap notes (`M9-08`); the same comparison in the dry run of the Migrations screen (later); any write.

## Acceptance
- BDD: `20_topology_planner_check.feature` / "The check says what each step would meet on the broker" (@fake-broker @smoke)
- BDD: `20_topology_planner_check.feature` / "Something that exists with other settings is found before applying" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "Each kind of step is compared with what the broker has" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A policy that exists with the same settings is already there" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A binding to something the plan declares earlier is fine" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A binding to something that is neither on the broker nor in the plan" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "A step blocked by the level is marked blocked and still compared" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "Without configure permission every step is blocked and still compared" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "A plan for another virtual host is blocked and nothing is read from that virtual host" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "A delete in an opened file needs Full control" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "A delete of something that is already gone says so" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "A step that cannot be read is never shown as "Will be created"" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "A user who may not read an item gets "Could not be checked"" (@fake-broker @negative)
- BDD: `20_topology_planner_check.feature` / "The check sends reads of the executor's allow-list and nothing else" (@fake-broker @negative @safety)
- BDD: `20_topology_planner_check.feature` / "A RabbitMQ 3.x connection is never checked" (@fake-broker @negative @safety)
- BDD: `20_topology_planner_check.feature` / "After applying the saved plan, the check finds everything already there" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "The check does not decide whether a plan can be saved" (@fake-broker)
- BDD: `20_topology_planner_check.feature` / "The check agrees with a real broker before and after applying" (@broker)
- BDD: `20_topology_planner_check.feature` / "What the check calls different is what a real broker refuses" (@broker @needs-spike)
- The pure-move gate: the existing runner tests (`MigrationRunnerVhostTest`, `AccessRunnerRulesTest`, `MigrationsViewModelTest`) pass unedited after the first commit; no new test is needed for it.
- Unit tests, written first: `PlanCheckTest` (one test per rule above, the order of precedence, the fixed sentences, no call for a blocked-by-vhost step, no call at all without a port); `ManagementDeclareCheckTest` (data-rabbit, Ktor mock engine: the status table 200, 404, 401, 403, 500, timeout, garbage body per action kind; a body with a sentinel never reaches a result); `DeclareCheckGetOnlyTest` (every request of every outcome is a GET of an allowed shape; a queue named `get`, `contents` or `purge` is a name like any other; a sensitivity test proves the guard would see a PUT); `RabbitMqConnectionGatewayDeclareCheckTest` (4.x gives a port, 3.13.7 and an unreadable version give none and no extra request).
- Live: `RealBrokerPlanCheckTest` (tag `broker`) binds the two `@broker` scenarios of `20` by name: before and after applying with the real runner and executor, and the conflict case.
- The fake and the live test share the S-G table as a constant (`core-testing`), so the fake cannot invent an answer.

## Design notes
One source of truth: the check reuses the executor's reader, allow-list, reasons and matchers; it adds no path and no comparison of its own except the S-G rule for queue and exchange arguments, which lives beside `DeclaredResourceMatcher` and is tested against the measured table. The check is sequential (one GET per step; a plan is small) and cancellable; `onStep` reports progress for `M9-06`. `core` sees only the interface; where the broker is faked: `FakeDeclareCheck` in core and acceptance tests, Ktor mock engine in data-rabbit unit tests; where it is real: `RealBrokerPlanCheckTest`.

## UX
None in this task (results are drawn by `M9-06`; wording in `docs/ux/Planner.dc.html`, "9. Check against the broker").

## MCP
None. Owner decision 2026-11-15 ("no MCP"). The check is read-only, so a tool would not break a safety rule; offering one later is a new owner decision (spec "Topology planner (M9)", "MCP evaluation").

## Open points
- Follow-up from the M9-04 review (architect, 2026-11-16), small and unrelated to the check, do it in the first commit that touches `PlanReviewCard`: (F4) at 1280 px a small exchange-to-queue plan overflows the side-by-side card; raise `SIDE_BY_SIDE_FROM` in `PlannerScreen.kt` (900 dp, suggest about 1400 dp) after the owner agrees (item 11 of the M9-04 owner review list), and check the `PlanReviewGraphRound2UiTest` fit test at 1280. (F5) in `PlannerViewModel.syncKnown`, a reader that throws `CancellationException` while the scope lives leaves `knownPending` true; clear it in a `finally`-like path (answer `null` unless the scope itself is cancelled) and add a core test.
- S-G decides the argument rule. Until it is measured the build stops after the first commit and the port with `FakeDeclareCheck`.
- Q14 (wording of the results, decided 2026-11-15 as written) is used by `M9-06`; the core types carry no wording.
- A broker that changes between check and apply: the apply keeps its own conflict rule; nothing to build.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

Note (architect, M9-02 step 2.5): the `@m9-02` scenario "The planner cannot change the broker by itself" says "I check the plan, save it and dismiss a second plan", but the M9-02 glue step only saves and dismisses (no Check yet). This task extends that step to call Check, with the recording executor still asserting reads only.
