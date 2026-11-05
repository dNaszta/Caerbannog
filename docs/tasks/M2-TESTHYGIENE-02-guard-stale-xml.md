# Test discovery guard: ignore stale XML and fix the task wiring

Status: STEP 4 VERIFIED (test developer, 2026-11-05): pass, no findings; fixtures self-test green, test + verifyTestDiscovery green, combined 'verifyTestDiscovery :ui:screenshotTest' ok, filtered brokerTest then guard green with the 'partial result 1 of 4' line, --configuration-cache works (stored and reused, also combined), 3 of 3 guard mutations turn the self-test red; ready for architect review. Before: STEP 3 BUILD DONE (developer, 2026-11-05): runGuard reports partial tagged results as unchecked, mustRunAfter wiring, headers updated; self-test green, both manual proofs run; ready for test/review. Before: STEP 2 ROUND 2 DONE (test developer, 2026-11-05): fixtures adjusted per the ruling, self-test RED on current code for the right reason (FreshTagged still a violation, unchecked 1 not 2, no 'partial result'); added ExtraTaggedFixtureTest (tagged, fresh, 2 testcases for 1 method, stays a violation, so violations expected 6 = 5 + Extra). Ready for the developer, step 3. Before: ARCHITECT RULING (2026-11-05) on the step 2 question, see "Ruling" below; brief Scope and Acceptance changed; step 2 fixtures must be adjusted (tests first), then step 3 (developer). Earlier: step 2 tests first done (test developer, 2026-11-05). Fixtures added, but NOT red: the guard already treats a tagged class whose XML is older than its SOURCE as unchecked (existing StaleTaggedFixtureTest), so (a) (b) (c) pass on current code. The failure in M2-14b is therefore not reproduced by the mtime-vs-source rule; architect to say whether the reference must be the compiled class file (needs a new runGuard input) or what the failing case was. Wiring (mustRunAfter) untouched, for the developer.

Milestone: M2 (hygiene)    Depends on: M2-TESTHYGIENE-01    Size: S

## Goal
`verifyTestDiscovery` gives a true answer after a filtered run (`--tests ...`) and can be called in the same Gradle invocation as `screenshotTest`, `brokerTest` or `keychainTest`.

## Why
Found in M2-14b verify. A `--tests` run leaves a `TEST-*.xml` in `build/test-results/<task>/` with fewer testcases than the sources; the guard compares counts and rejects it, although nothing is wrong. Calling `verifyTestDiscovery` and `screenshotTest` together fails with an implicit-dependency error (Gradle: a task reads the output directory of another task without declaring it). Each costs a full re-run to clear.

## Scope
In: `buildSrc/src/main/kotlin/TestDiscoveryGuard.kt` and `gradle/test-discovery-guard.gradle.kts`.
 1. Wiring: declare `mustRunAfter` (not `dependsOn`, the tagged tasks must stay optional) from `verifyTestDiscovery` to `:<m>:brokerTest`, `:<m>:keychainTest`, `:<m>:screenshotTest` and to the `test` tasks' output, so the implicit read becomes an ordered one. Alternative if Gradle still complains: `inputs.files(...)` on the result dirs with `@Optional`/`@IgnoreEmptyOutputs`, whichever is the smaller change.
 2. Partial result for tagged classes (replaces the old "stale XML" rule, see Ruling): in `runGuard`, a class with a tag (broker, keychain, screenshot) whose XML has FEWER testcases than the source has @Test-family methods is reported `unchecked (tag X: partial result N of M testcases, probably a --tests run; run <task> in full to check)` and counts in `unchecked`, not as a violation. The plain `test` and `jvmTest` classes (no tag) stay strict: the guard always runs them in full (`dependsOn :m:test`). Keep the existing rule "tagged XML older than the source is unchecked". A tagged class with MORE testcases than methods (non dynamic) stays a FAIL.
Out: any change to the style check; new tags; running the tagged tasks automatically.

## Ruling (architect, 2026-11-05)
The tester is right: for a tagged class a filtered run and a genuinely incomplete fresh run give the same XML (Gradle's XML carries no filter, and the mtime is newer than the source in both). Case (b) "fresh tagged XML with a missing testcase is rejected" and the goal "filtered runs do not fail the guard" cannot both hold. The goal wins for tagged classes, because they are optional by design and the guard already reports them "unchecked". What is lost is small: the style check (step 1 of the guard, source scan, no XML needed) still names every non-Unit `@Test` in tagged classes, and the plain `test` task, where the count check is the real guard, stays strict. Detecting a filtered run from the XML or the compiled class is not feasible (no filter info; the class file mtime is as fooled as the source mtime); so the compiled-class reference in the first brief is dropped.
Consequence: a tagged class with a really undiscovered test is only caught by the style check and by a full tagged run showing N of M in the report ("partial"); the report must print the numbers so a human can tell. Accepted for a dev guard.

## Acceptance
- No BDD scenario (build tooling). Tests first in `verifyTestDiscoveryFixtures` (`tests/guard-fixtures/`):
  (a) KEEP `StaleTaggedFixtureTest` (tagged, XML older than source, missing testcase): unchecked.
  (b) CHANGE `FreshTaggedFixtureTest` (tagged, fresh XML, 2 of 3): now expected unchecked with "partial result 2 of 3" in the report, NOT a violation. Remove its entry from `mustFlag` (violations 5 instead of 6); `r.unchecked` expected 2 (both tagged fixtures); footer expected "2 classes unchecked ..."; add an assertion that the report line of FreshTaggedFixtureTest contains "partial result".
  (c) KEEP `BadNonUnitFixtureTest` and `HiddenCountFixtureTest` (plain `test` dir, fresh XML, missing testcases) as rejected: they are the proof that the guard can still fail on counts. The three bad sources (declared return type, runBlocking assertIs, runBlocking assertFailsWith) stay rejected.
  Red for the right reason: after the fixture change the self-test fails on current code (FreshTagged is still a violation); green after the runGuard change.
- Manual proof, in the report: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerPlainTest*'` then `./gradlew verifyTestDiscovery` green (the filtered class shows "partial"); and `./gradlew verifyTestDiscovery :ui:screenshotTest` in one call, no implicit-dependency error.

## Design notes
Configuration cache compatible (file inputs only, no Project at execution), as today. Update the header comment of TestDiscoveryGuard.kt and test-discovery-guard.gradle.kts (tagged classes: partial result is unchecked, why: a filtered run is indistinguishable from an incomplete one). Wiring item 1 is independent of the ruling and unchanged.

## Open points
Mtime comparison (tagged XML older than source) can be fooled by a clock change; accepted for a dev guard. The proof that the guard can still fail is now the plain-test fixtures (c), not a tagged one. No product decision needed. Follow-up idea (not in scope): make the tagged tasks wipe their result dir before a run so that only fresh XML remains.

## Definition of done
Fixtures first (red for the right reason), then green; `./gradlew test` unchanged green; no unrelated changes.
