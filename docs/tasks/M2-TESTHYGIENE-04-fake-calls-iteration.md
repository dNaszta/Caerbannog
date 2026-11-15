# A test that counts a fake's recorded calls while another thread records them must not throw

Milestone: M2 (test hygiene)    Depends on: none (independent of M2-17b, where it was found; may land before or after it)    Size: S

## Goal
Reading the `calls` list of `FakeStreamCatalog`, `FakeStreamOffsets` and `FakeStreamMessageReader` (count, filter, loop) while the code under test appends to it on another thread never throws `ConcurrentModificationException`. `StreamClientsViewModelThreadTest` "refreshNow and retry from a plain thread load on the scope thread only" no longer fails under load. Test-side only: no production file changes.

## Why
Found by the M2-17b addendum step 4a (test developer, 2026-11-15): in the first of two full `:core:jvmTest` runs the test failed once with `ConcurrentModificationException` at `StreamClientsViewModelThreadTest.kt:183`; it passed in the second full run and 10 of 10 alone. The file was last changed in M2-06a (e66cb0e); the M2-17b branch did not touch it.

Cause (architect, read in the code, the flake itself was not reproduced by the architect): `FakeStreamCatalog.calls` (`core-testing/src/main/kotlin/dev/caerbannog/core/connections/fakes/FakeStreamCatalog.kt`, line 61) is `Collections.synchronizedList(ArrayList())`. Its `add` and `size` are locked, but iteration is not: `calls.count { ... }` walks the plain `ArrayList` iterator, and the view model's scope thread ("ui-sim") appends in between. The Javadoc of `Collections.synchronizedList` requires the caller to hold the list's lock while iterating. Lines 181 and 183 of the thread test do not; the established pattern elsewhere does (`ClientsHarness.count`, `StreamListHarness.count`, `ClientsSteps.clientCalls`, `StreamListSteps.listCalls`: `synchronized(catalog.calls) { ... }`). `FakeStreamOffsets.calls` (line 21) and `FakeStreamMessageReader.calls` (line 23) have the same shape. The test rule: a flaky test is a bug (`.claude/agents/test-developer.md`, "Test principles").

## Ruling on how (architect)
Fix the fakes, not each reader. Make the three `calls` lists `CopyOnWriteArrayList<String>()` (the declared type stays `MutableList<String>`): iteration is then a snapshot and cannot throw, whichever thread appends, and no test needs an edit. Reasons: the trap is in a shared fake that many tests read in many ways (`calls.count`, `calls.filter`, `calls.toList()`, `calls.size`), and a fix at two call sites leaves every future reader exposed; the lists hold a handful of entries, so the copy on write costs nothing. The existing `synchronized(...) { ... }` blocks stay as they are (harmless; do not touch them).

Option rejected: `synchronized(r.catalog.calls)` at lines 181 and 183 only. It works, but it fixes one reader and the next `calls.count` on a thread test flakes the same way.

## Scope
In:
- Test first, seen red: for each of the three fakes, one test in the class that already tests that fake (`FakeStreamCatalogTest`, `FakeStreamOffsetsTest`; for the reader fake `core/src/jvmTest/.../browser/StreamMessageReaderContractTest.kt` or the smallest new test class next to it) that has one thread record calls through the fake's own public methods in a loop while another thread counts `calls` in a loop until the writer is done. Bounded: a start latch, a fixed number of appends (tens of thousands), a join with a timeout and a message that names what was expected; no sleep. Red on the current code is `ConcurrentModificationException` (probabilistic, so show it red 3 of 3 runs and say so in the Status); green after the change, and it cannot fail afterwards (a `CopyOnWriteArrayList` iterator never throws it).
- The change: three one-line edits in `core-testing`, plus one sentence in the KDoc of each `calls` ("safe to read while calls are being recorded") so the contract is written where the next reader looks.
Out:
- Editing `StreamClientsViewModelThreadTest` or any other test that reads `calls` (they stay unedited and must stay green).
- Other recording lists in the fakes (`FakeConnectionGateway.log`, `FakeStreamManager.createdStreams`, `FakeMessagePublisher.publishedMessages`, ...): same hazard in principle, no observed failure; do not widen. If the test developer finds one that is read across threads in a test, say so in the Status; the architect decides.
- Any production file, any `tests/bdd` file, `:data-rabbit`.

## Acceptance
- BDD: none (no user-visible behaviour); unit tests as above.
- The three new tests red on the old fakes (3 of 3 runs each), green on the new.
- `StreamClientsViewModelThreadTest` green 10 of 10 alone and 10 of 10 while the machine is loaded (as in M2-TESTHYGIENE-03: run it under CPU load before and after; the "before" run may not reproduce, say so).
- Fast set once before the merge: `task test` (the fakes are test-scope dependencies of `:core`, `:ui`, `:acceptance-tests`, `:data-rabbit`, `:desktopApp` and `:mcp-server`) and `verifyTestDiscovery`.

## Design notes
`core-testing` is test support (a `testImplementation` or test-scope dependency of the six modules above, checked in the `build.gradle.kts` files; the test developer owns it). Nothing in `core`, `data-rabbit`, `ui`, `desktopApp` or `mcp-server` changes. Steps: 2 (test developer) the three tests, red; 3 (test developer, the change is one line each in test support, so the builder step is folded into the same role) the fakes; 4 verify (fast set, load runs); 5 architect review. One commit per step.

While there, one observation for the test developer to check and report, not to fix unless it proves a real problem: `Rig.await` in the thread test evaluates its condition only when the view model's state emits (`vm.state.first(condition)`), so a condition about `calls` that becomes true without a later state emission would wait for the 20 s timeout instead of failing at once. If it is real, write it in the Status as a finding with the line; the architect decides the follow-up.

## UX
None.

## MCP
None (test support only; no product capability).

## Open points
None. The flake itself was seen once, under the load of a full parallel run, and is explained by the code; if the "before" runs never reproduce it, the red stress tests are the evidence.

## Definition of done
Three tests red then green, `StreamClientsViewModelThreadTest` unedited and stable under load, no production file changed, no other edit in tests that read `calls`, README row added (coordinator).

## Status
- Branch: `m2-testhygiene-04-fake-calls-iteration` (worktree `.claude/worktrees/testhygiene-04`, from main 1a7a758). Current step: 5 (architect review) next. Round: 1. Last verdict: none yet (step 4 PASS, no blocker, no major). Open questions for the owner: none.
- Step 1 (brief): the architect's brief of 2026-11-15 used as written (no open point); the test developer was told to report any contradiction with the code and found none (lines 181, 183 and the three lists matched).
- Steps 2 and 3 (test developer, 2026-11-15): 6be3089 (red, tests only) and dc8d8eb (green, fakes only). Three tests `calls can be counted while another thread records calls` in `FakeStreamCatalogTest`, `FakeStreamOffsetsTest` and `StreamMessageReaderContractTest` (30,000 appends, reader counts `calls` in a loop, 60 s joins, no sleep), sharing one helper `core/src/jvmTest/.../ReadWhileRecording.kt` (`assertReadableWhileRecording`, test-side, not listed in the brief). Red on the old fakes 4 of 4 runs each (`ConcurrentModificationException`), green 5 of 5 each on `CopyOnWriteArrayList`. `:core:jvmTest` green once (the developer wrote 2,488 tests; the verify step's fresh XML sums to 2,336 in 212 classes, main's 2,333 plus the 3 new tests, so 2,336 is the number to trust). `StreamClientsViewModelThreadTest` 10 of 10 alone and 10 of 10 under 8-process CPU load, both before and after; the flake did NOT reproduce in 20 "before" runs, so the red stress tests are the evidence. Diff against main: 7 files, +107/-6, no production file, no `tests/bdd` file.
- Findings for the architect: (1) `Rig.await` (`StreamClientsViewModelThreadTest.kt:106`) re-evaluates its condition only when the state emits; at line 183 a state change follows in practice (`updatedAtMillis`), so no flake, but a calls-only condition would wait the 20 s time-out instead of failing at once (late, not a hang; architect decides the follow-up). (2) No other recording list is iterated across threads by a test (`FakeConnectionGateway.log` is iterated in two single-threaded tests; the rest is `size` or `toList()`); the hazard remains in principle.
- Step 4 (test developer, fresh agent, 2026-11-15, branch at b378884): PASS, no blocker, no major. Diff against main 9 files, +115/-7, no production, `tests/bdd` or `:data-rabbit` file; the helper reads, overlaps and fails as intended (latch, 60 s joins, no sleep, final count 30,000 asserted). The one mutation (catalog `calls` back to `synchronizedList`): `FakeStreamCatalogTest` red 3 of 3 (`ConcurrentModificationException`, "after 1932 reads", `FakeStreamCatalogTest.kt:264`), file restored, `git status` clean. `StreamClientsViewModelThreadTest` 10 of 10 green under 8 `yes` processes on top of an already busy machine (load average 9 to 18), PIDs killed by number. Fast set once: `./gradlew test verifyTestDiscovery --continue` BUILD SUCCESSFUL in 1 m 48 s: core 2336, ui 816, desktopApp 139, data-rabbit 768, mcp-server 41, dev-seed 90, acceptance-tests 1641 (1103 skipped by tag filter), 0 failed; the four classes are `ok` in the discovery report with source count equal to XML count. Findings: minor, the test count above (corrected here); minor, informational, `FakeTopologyCatalog.calls` (`core-testing/.../FakeTopologyCatalog.kt:57`) has the same `synchronizedList` shape, read only via `.size` in `TopologyViewModelThreadTest`, so no current flake (architect decides); idea, the green path cannot prove a read overlapped a write and a minimum read count would be non-deterministic, so no change.
- Not run: `brokerTest`, `keychainTest`, screenshot tests (not needed: test-side only), the GUI; the original flake was never reproduced on main (20 runs before, 10 under load after), the red stress tests are the evidence.
