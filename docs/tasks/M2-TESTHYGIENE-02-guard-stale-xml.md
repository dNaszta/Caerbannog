# Test discovery guard: ignore stale XML and fix the task wiring

Status: step 2 tests first done (test developer, 2026-11-05). Fixtures added, but NOT red: the guard already treats a tagged class whose XML is older than its SOURCE as unchecked (existing StaleTaggedFixtureTest), so (a) (b) (c) pass on current code. The failure in M2-14b is therefore not reproduced by the mtime-vs-source rule; architect to say whether the reference must be the compiled class file (needs a new runGuard input) or what the failing case was. Wiring (mustRunAfter) untouched, for the developer.

Milestone: M2 (hygiene)    Depends on: M2-TESTHYGIENE-01    Size: S

## Goal
`verifyTestDiscovery` gives a true answer after a filtered run (`--tests ...`) and can be called in the same Gradle invocation as `screenshotTest`, `brokerTest` or `keychainTest`.

## Why
Found in M2-14b verify. A `--tests` run leaves a `TEST-*.xml` in `build/test-results/<task>/` with fewer testcases than the sources; the guard compares counts and rejects it, although nothing is wrong. Calling `verifyTestDiscovery` and `screenshotTest` together fails with an implicit-dependency error (Gradle: a task reads the output directory of another task without declaring it). Each costs a full re-run to clear.

## Scope
In: `buildSrc/src/main/kotlin/TestDiscoveryGuard.kt` and `gradle/test-discovery-guard.gradle.kts`.
 1. Wiring: declare `mustRunAfter` (not `dependsOn`, the tagged tasks must stay optional) from `verifyTestDiscovery` to `:<m>:brokerTest`, `:<m>:keychainTest`, `:<m>:screenshotTest` and to the `test` tasks' output, so the implicit read becomes an ordered one. Alternative if Gradle still complains: `inputs.files(...)` on the result dirs with `@Optional`/`@IgnoreEmptyOutputs`, whichever is the smaller change.
 2. Stale XML: for a test class, count only XML files that are newer than the compiled test class (or newer than the source file). A class whose XML is older is reported as "unchecked (stale result)", the same wording as the tagged classes without XML, not as a failure. The plain `test` task stays strict: it is always run in full by the guard (`dependsOn :m:test`), so there the XML is fresh by construction.
Out: any change to the style check; new tags; running the tagged tasks automatically.

## Acceptance
- No BDD scenario (build tooling). Tests first in the existing fixture mechanism `verifyTestDiscoveryFixtures` (`tests/guard-fixtures/`): add (a) a result file older than its class with a missing testcase: must be reported "unchecked", not rejected; (b) a fresh result file with a missing testcase: still rejected; (c) the existing bad sources still rejected.
- Manual proof, in the report: run `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlainTest*'` then `./gradlew verifyTestDiscovery` green; and `./gradlew verifyTestDiscovery :ui:screenshotTest` in one call, no implicit-dependency error.

## Design notes
Configuration cache compatible (file inputs only, no Project at execution), as today. Time comparison on file modification time of the XML versus the class file; say in a comment why (a filtered run never refreshes the other classes' XML).

## Open points
Mtime comparison can be fooled by a clock change; accepted for a dev guard. The guard must still fail for a fresh XML with a missing testcase (the proof that it can fail).

## Definition of done
Fixtures first (red for the right reason), then green; `./gradlew test` unchanged green; no unrelated changes.
