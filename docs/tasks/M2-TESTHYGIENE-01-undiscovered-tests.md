# Audit and guard: tests JUnit never discovers

Milestone: M2    Depends on: none (can run beside M2-03 part 2; start from `main` after part 1 is merged)    Size: S to M

Branch: `m2-testhygiene-01-undiscovered-tests` from `main`.

## Goal
Every test method in every module actually runs, and a permanent check fails the build when a `@Test` function can be silently skipped again.

## Why
Found in M2-03 part 1 step 4: a JUnit 5 test written as `fun x() = runBlocking { ... assertIs<...>() }` returns a non-Unit value; JUnit 5 does not discover it and does not report it as skipped. Six tests never ran until a mutation survived. Earlier tasks (M1, M2-01, M2-01b, M2-02, M2-03a) in `core`, `ui`, `data-rabbit`, `desktopApp` and `acceptance-tests` may have hidden tests, and with them hidden failures. "A task is not done until its scenarios pass" is only true if the tests run.

## Scope
In:
1. Audit, step A (test developer, no production change): for every module and test class compare the number of `@Test`, `@ParameterizedTest`, `@RepeatedTest` and `@TestFactory` methods (source scan) with the `testcase` count in `build/test-results/**/TEST-*.xml` after a clean run. List every class where they differ, and every `@Test` function whose return type is not `Unit`, in a table in the Status of this brief. Include the Compose UI tests in `ui` and the `@broker` tests (those only count when run; for them compare against a `brokerTest` run if Docker is available, otherwise list them as unchecked).
2. Fix, step B: make each affected test return Unit (`runBlocking<Unit> { ... }` or an explicit `: Unit`/block body). Do not change assertions in this step.
3. Hidden failures, step C: if a newly running test fails, do NOT fix it inside this task and do not delete or ignore it. Record it (class, test, failure) in the Status, keep it failing only on the audit branch, and report to the architect; the architect writes a separate fix task per finding (or one grouped task) with severity. The audit branch merges only when it is green: so either the fix lands first in its own task, or the failing test is tagged and excluded with a written reason and a follow-up id, decided by the architect. A test that fails because it was wrong is fixed by the test developer; a test that fails because the production code is wrong is a production bug and goes to the senior developer through a brief.
4. Guard, step D: a permanent check that fails the build when a test-annotated function has a non-Unit return type. Options, in order of preference (no new dependency without asking the architect and the product owner): (a) a plain source-scan JUnit test in `core` jvmTest (or a Gradle task wired into `check`) that reads the test source directories of every module and fails with file, line and function name for any `@Test` fun with an expression body or declared return type other than Unit; (b) a Gradle `check` task comparing `@Test` count with XML testcase count after the test tasks, which is the more reliable guard because it catches any reason a test is not discovered (also `private` or `internal` mistakes, wrong import `org.junit.Test` versus `org.junit.jupiter.api.Test`); (c) a detekt or compiler rule only if detekt is already in the build. Recommend (b) plus a fast source scan for the clear message; say which was built and why. The guard must itself be tested (a fixture with a bad test function that the check rejects, kept outside the normal test sources).
5. Add a line to the developer rules in `tests/bdd/README.md`: tests that call suspending code use `runBlocking<Unit>` or `runTest`.
Out: any production code change; fixing hidden failures (step C); changing assertions.

## Acceptance
- Status table lists per module: methods found, XML testcases, difference, and the fix.
- After step B: for every class `@Test` count equals the XML count (script or the guard prints it).
- The guard fails on the bad fixture and passes on the repository; `./gradlew test` and `task test` stay green (apart from a hidden failure handled by step C).
- `./gradlew check` or `task test` runs the guard so nobody has to remember it.

## Design notes
Build-only change under `docs/`, test source and Gradle files; keep the guard in the Gradle build logic that already exists (no new module unless the architect agrees). Keep it fast (source scan under a second). Gradle configuration cache must still work.

## Open points
- Whether hidden tests fail is unknown until step A; step C describes the handling. Report the count of newly running tests and of newly failing tests to the architect before step D.

## Definition of done
Audit table recorded, every undiscovered test fixed or listed with an owner, the guard merged and proven by the bad fixture, `tests/bdd/README.md` rule added, task index row updated, architect review.
