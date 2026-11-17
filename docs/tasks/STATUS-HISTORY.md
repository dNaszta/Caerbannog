# Task status history

The long status narrative of every row of `README.md`, moved here unchanged on 2026-11-17 when the index was reconciled with git. The index now holds one short line per task. This text is history: it is not kept in sync and may say "not merged" or "waiting" about work that has long been merged.

## M1-01-skeleton-and-spike

done

## M1-TASKFILE-01-local-dev-commands

done

## M1-02-connection-profiles

done

## M1-04-connections-screen-feedback-gaps

done (accepted, round 1; merged into local `main`, not pushed)

## M1-05-wire-desktopapp-connections

done (accepted with follow-ups, round 1; merged into local `main`, not pushed; manual `:desktopApp:run` by human still pending)

## M1-TASKFILE-02-test-task-runs-all-modules

done (architect: Accepted, 2026-11-04; merged into local `main`, not pushed)

## M1-03-permission-check

done (accepted with follow-ups, round 1; merged into local `main`, not pushed)

## M1-06-profile-declared-permissions

done (accepted with follow-ups, round 0; merged into local `main`, not pushed; follow-up `M1-11`)

## M1-07-permission-state-ui

done (accepted with follow-ups, round 2); merged into local main (not pushed); follow-ups in M1-12; M2 prerequisite on `ConnectionSession.open` in the spec; manual look by the user pending; branch `m1-07-permission-state-ui`

## M1-12a-connections-layout-and-cards

done (accepted with follow-up, round 2, 2026-11-04); follow-up R1 (Password hint text) is the first commit of M1-12b; manual look by the user pending; branch `m1-12a-connections-layout-and-cards`; merged into local `main` (not pushed)

## M1-12b-connections-icons

done (accepted, architect review 2026-11-04, no blocker or major; minor: import order); mockup CA label updated to match; manual look by the user pending; branch `m1-12b-connections-icons`; merged into local `main` (not pushed)

## M1-09-permission-check-hardening

done (accepted with follow-ups, round 0; merged into local `main` with --no-ff, not pushed; adds `UnreadableCause.UNEXPECTED`)

## M1-08-keychain-test-opt-in

briefed and confirmed (sharpened 2026-11-04: tag `keychain`, `keychainTest` task, `task test:keychain`, no flag; proof by XML count 19 to 17; real run once only with user approval); red state recorded (step 2: 19 tests in data-rabbit incl. both Keyring classes, no keychainTest task, brokerTest 11); build done (step 3); verified (step 4, pass, no findings; keychainTest run once: 2 tests pass; literal mutation not run to avoid a second keychain touch); renamed from M1-06 to avoid clash with M1-06-profile-declared-permissions; done (architect: Accepted, step 5, 2026-11-04; branch `m1-08-keychain-test-opt-in`, merged into local main 2026-11-04 with --no-ff, not pushed)

## M1-10-topology-level-on-new-connection

done (accepted with follow-ups, round 0; merged into local `main`, not pushed)

## M1-11-declared-permissions-polish

briefed and confirmed; mockup radios done; title test written (red), built (step 3), step 4 verify passed (test engineer), done (accepted, round 0; merged into local `main`, not pushed)

## M2-01-connection-session

done (architect: Accepted with follow-ups, step 5, 2026-11-04); merged into local `main` (not pushed); branch `m2-01-connection-session` kept; follow-up `M2-01b-session-single-state`; no Docker

## M2-01b-session-single-state

done (architect: Accepted with follow-ups, step 5, 2026-11-04; branch `m2-01b-session-single-state`; merged into local `main`, not pushed, branch kept); follow-up F1 (document "observers must not throw") goes into M2-04; no Docker

## M2-02-management-api-spike

done (architect: Accepted with follow-ups, step 5, 2026-11-04; branch `m2-02-management-api-spike`; merged into local `main` 2026-11-04 together with the Q2 spike test stability fix (`m2-02c-spike-test-stability`), not pushed); findings in the brief Status and `docs/spec.md` "Management API facts"; S1 and S2 done, S3 open; needs Docker

## M2-03-stream-gateway

part 1 (`m2-03-connection-lifecycle`) done: architect Accepted with follow-ups (step 5, 2026-11-04), merged into local `main` (not pushed); follow-ups are the part 2 checklist in `M2-03-stream-gateway.md`; part 2 `m2-03-stream-catalog`: step 2 tests first done (test developer, 2026-11-04; compile-level red, contract and decisions in the brief Status); architect rulings and build handoff written (2026-11-04: `NotFound` confirmed, accessor and paging and publishers go to M2-04, fakes stay in core `jvmTest`); step 3 build done (developer, 2026-11-04): stream-client 1.12.0 added (jar +14.7 MB, dmg +14.5 MB), catalog, offsets, gateway and composition root built; unit suites green, `brokerTest` 55 of 56 (the one failure is a test bug, ruled 2026-11-04: create the stream with `x-max-age=7D`; see Status "Part 2, step 3 rulings"); next test developer step 4 verification (handoff in the brief), then architect step 5; step 4 verification done (test developer, 2026-11-04: PASS, brokerTest 57 of 57, 8 of 9 mutations killed, 1 equivalent); step 5 architect review done (2026-11-04): ACCEPTED WITH FOLLOW-UPS, part 2 merged into local `main` (not pushed, 2026-11-04); follow-ups F1 to F3 in the brief Status; M2-11 shutdown hook (optional)

## M2-TESTHYGIENE-02-guard-stale-xml

M2-TESTHYGIENE-01; briefed (architect, 2026-11-05), size S; step 2 fixtures added, green on current code; architect ruling (2026-11-05): tagged partial XML becomes unchecked (filtered run is indistinguishable from an incomplete one), plain test stays strict, FreshTagged fixture flips to unchecked, wiring unchanged; fixtures adjusted (step 2 round 2); step 3 built (developer, 2026-11-05); step 4 verified (test developer, 2026-11-05: pass, no findings, 3 of 3 mutations killed, combined call and filtered run proven, configuration-cache compatible), accepted (architect, 2026-11-05)

## M2-TESTHYGIENE-01-undiscovered-tests

done (architect: Accepted with follow-ups F1 to F3, step 5, 2026-11-04; follow-ups applied; merged into local main e71b410, not pushed); audit: no hidden test in any module; guard `verifyTestDiscovery` (buildSrc, wired into `test` and `check`) proven by fixtures; no Docker for the review

## M2-03a-stream-stats-permissions

done, architect review Accepted with follow-ups (2026-11-04, branch `m2-03a-stream-stats-permissions`, merged into local main, not pushed); spec and M2-03 brief updated; test developer; first step of M2-03; needs Docker

## M2-04-stream-list-core

briefed and confirmed (architect, 2026-11-04, branch `m2-04-stream-list-core`); `03` got 3 new scenarios (publishers by tag, no plugin, paging counts streams) and "Filter by kind" is `@needs-M2` (M2-07); step 2 tests first done (test developer, compile-level red); architect rulings and build handoff written (2026-11-04); owner answer "5s trial, a": 5 s stays a trial, if the measured paging cost is too high keep in-memory paging and raise the default interval (no broker name filter); step 3 build done (senior developer, 2026-11-04): `core-testing` module, view model, data-rabbit and composition root; all suites green, brokerTest 59/59; calls per tick measured on 1000 queues: 53 (2 list, 1 publisherCounts, 50 offsets) every 5 s; next: test developer step 4 verification, then architect step 5; D2 stays proposed; small open question: offsets plain or grouped; verify (2026-11-04) failed with one major, F-1 (streams of other vhosts listed): architect ruling `StreamListRequest.vhost` and `GET /api/queues/{vhost}`, new scenario "Another vhost's streams are not listed" in `03`, round-1 handoff in the brief; D2 confirmed and offsets plain (2026-11-04); round 1 step 3 (developer): F-1 fixed, the list asks `GET /api/queues/{vhost}` (`StreamListRequest.vhost` required), fake filters per vhost; `RunStreamListAcceptanceTest` fails only on the undefined steps of "Another vhost's streams are not listed" until the test developer adds them; re-verify of F-1 passed (test developer, 2026-11-05, head 979fbcd); step 5 architect review done (2026-11-05): ACCEPTED WITH FOLLOW-UPS, MERGED into local `main` (merge commit 4332cf3, 2026-11-05, not pushed, branch kept); follow-ups go to M2-05 (F-2 UI dispatcher as acceptance line, clearer text for a 404 vhost, plain offsets) and the next touch (reword four "D2 proposed" comments); default stays 5 s for the owner's trial (53 calls and about 100 ms per tick on a local 1000-queue vhost; raise to 10 s if a remote broker is slow); brief lines 5 and 11 repaired

## M2-05-stream-list-screen

input note with the chip UX (`M2-05-stream-list-screen.md`), full brief after M2-04; mockups updated 2026-11-04; `AppSettings` and `settings.json` (per app) decided; full brief written (architect, 2026-11-05, branch `m2-05-stream-list-screen`): three parts, 05a (UI dispatcher via `kotlinx-coroutines-swing` 1.9.0 in `desktopApp`, F-2 thread test, `JsonFileAppSettings`, `LOAD_FAILED` text, four D2 comments), 05b (`ConnectionControlModel`, chip and popup, app frame, the `13` app bar scenarios and switch scenarios), 05c (list screen, all states, plain offsets, copyable values, `01` Switch between connections); `Streams.dc.html` updated (plain offsets, Retention column, states block); step 2 05a tests done (test developer); step 3 05a build done (senior developer, 2026-11-05, all suites green); step 4 05a verified (pass with minor findings); step 5 05a architect review: ACCEPTED WITH FOLLOW-UPS (2026-11-05, three minors, see brief); step 2 05b tests done; architect rulings on 05b given (2026-11-05: `13` scenario edit approved, six points approved, note also in popup row, three assertions to add); step 3 05b build done (senior developer, 2026-11-05; all green except one test-side bug in `ConnectionsScreenUiTest`, see brief); step 4 05b verified (pass with minor findings); step 5 05b architect review: ACCEPTED WITH FOLLOW-UPS (2026-11-05, default window size and one core no-reconnect test to land first, see brief); follow-ups done; step 2 05c tests done (test developer); architect rulings on 05c given (2026-11-05: all 14 points approved, no test changes, mockup wording aligned, self-constraint level for Create stream deferred to M3); step 3 05c build done (senior developer, 2026-11-05; all suites green, no test edited, see brief); step 4 05c verified (pass with findings); step 5 05c and whole M2-05 architect review: ACCEPTED WITH FOLLOW-UPS (2026-11-05): F1 CA path validator must not throw (tests first, then fix, inside M2-05 round 2), mockup Type cell aligned to lower case, A1 hand trial by the user before the merge proposal; M2-14 (plain connections switch) to be written

## M2-14-plain-connections

depends on M2-05; briefed (architect, 2026-11-05, branch `m2-14-plain-connections`, split M2-14a app and M2-14b dev tooling); decided by the user 2026-11-05; step 2 14a tests first done (test developer, spikes S-D and S-E answered); architect rulings on P1 to P9 given (2026-11-05: all approved, no test changes; no Connect hint in 14a, follow-up M2-14c; Save does not abort on an invalid CA; ports stay read-only); step 3 14a built, step 4 verified (pass with findings); step 5 14a architect review: CHANGES REQUIRED (2026-11-05): R1 chip name squeezed (max 520.dp, name floor), R2 CA path on create input (fix the M1 gap in 14a), R3 accessible name for Use TLS switch, all tests first, then delta re-review, then M2-14b; round 2 R1 to R3 built and verified; step 5 delta review: 14a ACCEPTED WITH FOLLOW-UPS (2026-11-05: F1 KDoc on UI-thread confinement, F2 late CA result for a renamed profile must be dropped, tests first); 14b may start (brief updated); step 3 14b built (2026-11-05: dev compose plain ports, dev-seed --no-tls, dev:seed:plain, README A2); step 4 14b and end-of-task full run verified (test developer, 2026-11-05: pass, no findings, brokerTest 74/74, 4 of 4 mutations killed); step 5 whole-task architect review: ACCEPTED WITH FOLLOW-UPS (2026-11-05): F1 KDoc, F2 late CA result and rename epoch, R6 form holder, M2-14c Connect hint, guard follow-up `M2-TESTHYGIENE-02`; architect agent TLS rule updated; NOT merged: needs the user's A1 hand trial (plain profile and TLS profile with CA typed at create) and the user's word

## M2-06-publishers-consumers

briefed (architect, 2026-11-05, branch `m2-06-publishers-consumers`, split M2-06a core, data, acceptance and wiring (spikes S-A to S-C, `StreamClientsViewModel`, shared refresh driver, navigation, control follows the shown screen) and M2-06b stream screen and tab in `ui`); `04` extended (7 scenarios, `@needs-spike` removed, jump scenario `@needs-M3`), `Clients.dc.html` and `Streams.dc.html` updated, spec paragraph added; decision D10 (connection cell) decided (a) by the owner 2026-11-05; ROUND 2 follow-ups review 2026-11-05: Accepted with follow-ups (F-B, F-D, F-F, F-G done; F-E closed, D10 decided (a)); NEXT: hand trial A1, then merge. (History: step 2 06a stopped at spike S-A (caught-up lag 2 and 3 on a real broker); architect lag ruling 2026-11-05: Caught up only at 0, else "N behind" with a stored-offsets tooltip, no tolerance constant; `04`, mockup, spec updated; step 2 resumed and finished; architect rulings before the build 2026-11-05 (all points approved, null user is an em dash, adapter moved to 06b, driver extraction first as its own commit with a no-test-edit gate, else documented duplication); step 3 06a built (senior developer, 2026-11-05): driver extraction gate passed (no test edited), `StreamClientsViewModel`, `RefreshTarget`, control follows the shown screen, navigation and composition wiring; one test assertion corrected (see brief); step 4 verify 06a PASS (tester); step 5 architect review 06a: Accepted with follow-ups (2026-11-05); NEXT: step 3 for 06b (spike on the name link first), then step 4 verify 06b, full run once, hand trial A1)

## M2-14c-connect-hint-tls

step 1 done (brief and scenarios, 2026-11-05); step 2 done (test developer, 2026-11-05: measured, separable on the Connect path, D-A not needed, tests red on compile; D7 finding on the management host name check); step 2b architect rulings (2026-11-05): D7 management host name fix FOLDED IN as step 3a (trust manager wrapper in `TlsSupport`, no JVM flag, no new dependency, no user decision), classification rule accepted with 3 conditions, IPv6 literal split to M2-14d; step 3a built (`dd4a1a9`, D7 wrapper) and 3b built (`2c4ccf9`, typed split, `NO_TLS_ANSWER`), step 4 verified (test developer, 2026-11-05: pass, 5 of 5 mutations killed, flaky `PermissionCheckerFailureTest` TIMEOUT is pre-existing, follow-up proposed); step 5 architect review (2026-11-05): ACCEPTED WITH FOLLOW-UPS (no rule violation; fixture `127.0.0.1` accepted; follow-ups `M2-TESTHYGIENE-03` flaky TIMEOUT test and hanging loop test, `M2-14e` Check says "may not use TLS" for a wrong CA); spec D9a updated; NOT merged: needs the owner's hand trial (see brief) and the user's word

## M2-14d-ipv6-management-host

done (architect: Accepted, 2026-11-06; merged into local `main`, not pushed)

## M2-14f-check-tls-reason

done (architect: Accepted, 2026-11-06; merged into local `main`, not pushed)

## M2-TESTHYGIENE-03-flaky-timeout-and-loop-tests

done (architect, 2026-11-06: ACCEPTED, merged into main)

## M2-14e-ca-late-result

depends on M2-14a; briefed (architect, 2026-11-05, branch `m2-14d-ca-late-result`, brief `M2-14e-ca-late-result.md`, renamed from M2-14d on 2026-11-05 because M2-14d is the IPv6 brief); scenario outline added to `01`; may touch the same area as M2-14c (see brief Open points); decisions D-1 and D-2 = recommended (owner); step 2 tests first done (test developer, 2026-11-05): core 4 red plus 5 guards, `GatedCaCertificateValidator`, `01` outline steps (2 red), ui `ConnectionFormStateTest` (red by compile); step 3 built (senior developer, 2026-11-05): `isStillCurrent`, epoch bump on rename and on deleting the selected profile, KDoc, `ConnectionFormState` used by `EditPanel`; tests green; step 4 verified (test developer, 2026-11-05: pass, 5 of 5 mutations killed); step 5 architect review: ACCEPTED WITH FOLLOW-UPS (2026-11-05, one minor: the form holder pins the profile at creation, see brief finding 1); F1, F2, R6 marked done in M2-14; NOT merged; the old id M2-14d is still in test comments

## F-C-lazy-clients-list

step 5 review done (architect, 2026-11-05): accepted with follow-ups; before merge F-C-03 (`F-C-03-lazy-clients-test-gaps.md`: clamp, stale popup, row swipe tests), owner hand trial, `task test` once; F-C2 briefed (`F-C2-lazy-stream-list.md`); earlier: briefed (architect, 2026-11-05, branch `f-c-lazy-clients-list`, brief `F-C-lazy-clients-list.md`); F-H prep done (StreamScreen.kt split as a pure move, tests and screenshots unchanged), F-H is done; S-1 and S-2 done (no fallback), red tests in; step 3 build done (senior developer, 2026-11-05, branch `f-c-impl`): ui jvmTest 309 with 1 red (test 6 second half, finding F-C-T6), S-1 after: 5000 rows first paint 67 ms; architect rulings R-FC1 to R-FC5 given (publisher key `<connection>#<publisherId>` plus `~n`, key as first constructor field, two test-side edits by the test developer, step 2b next); asked for by the owner; decisions D-FC1 (do it now, recommend yes) and D-FC2 (F-H split first as its own commit, recommend yes) open; depends on M2-06 merge

## F-C-03-lazy-clients-test-gaps

briefed (architect, 2026-11-05, `F-C-03-lazy-clients-test-gaps.md`)

## F-C2-lazy-stream-list

briefed (architect, 2026-11-05, `F-C2-lazy-stream-list.md`); depends on F-C merged

## M2-07-super-streams-in-list

briefed (architect, 2026-11-05, branch `m2-07-super-streams-in-list`, brief `M2-07-super-streams-in-list.md`); depends on M2-04 (merged), M2-05 and M2-06 (screen and name link); `06` got 7 new scenarios (order, find by partition, paging counts entries, refresh keeps expanded, no partial total, grouping unreadable, `@broker` real broker), "Browse one partition" and "Global search" are `@needs-M3`, "Filter super streams" moved to `03` "Filter by kind"; note row added to `Streams.dc.html`; spikes S-A (one-call layout: `/api/exchanges/{vhost}`, `/api/bindings/{vhost}`) and S-B (hand-made shapes) first, no product decision needed; step 2 tests first done (test developer, 2026-11-05, red on compile stubs, spikes answered in the brief Status); architect rulings on the step 2 questions (2026-11-05): 2 calls per tick confirmed with the skip-bindings saving adopted, zero-partition super stream not listed, same-name plain and super stream both listed with kind-aware ids, em dash in Size (scenario wording in `06` and `03` to change), `typeText` in core; no pinned test changes, 4 small test additions listed in the brief; step 3 build DONE (senior developer, 2026-11-05): core, data-rabbit, ui, desktopApp and the real-broker test green; the two test-side items fixed; step 4 verified (test developer, 2026-11-05, pass with one minor: Type cell ellipsized); step 5 architect review done (2026-11-05): ACCEPTED WITH FOLLOW-UPS, rulings 1 to 5 hold, no rule violation; spec rows "Super stream" and "Stream list" updated; before merge: Type column width fix (`M2-07a-followups.md` item 1) and the owner's hand trial on the dev broker; row ids and the Table/Screen split go to M3 (items 2 and 3); follow-up item 1 (Type column width) done 2026-11-05

## M2-08-topology-read-only

briefed (architect, 2026-11-05, branch `m2-08-topology-read-only`); owner answers 2026-11-05: T1 every vhost the user may see, T2 bindings as a graph, T3 (a), T5 yes; architect round 2 (rulings R1 to R10 in the brief): graph split out as 08d, vhost switch in 08b/08c, `09` got vhost and graph scenarios; decided 2026-11-05: T4 no write control, U1 architect extends the mockup (done, owner to review before 08c step 2), V1 yes (spec updated), G1 hand-drawn graph; open: G2 (keep the table beside the graph), U2 (graph design), both for 08d only; 08a build is not blocked

## M2-08a-topology-core-and-data

step 2 done incl. round 2 (test developer, 2026-11-05: spikes S-A to S-C and S-E measured, `vhosts()` contract, tests RED on compile stubs, see the brief Status); architect accepted streams dropped in the catalog and `NotFound` for an invisible vhost (R1, R2); step 3 DONE (senior developer, 2026-11-05, tests green: unit 51, broker 12), ready for verify and review; round 2 (`vhosts()` contract and S-E, test developer) in parallel; step 4 verified (test developer, pass, 5 of 5 mutations caught); step 5 architect review (2026-11-05): ACCEPTED, two optional minors (consumers toInt wrap, fully qualified import) go to the first 08b commit; unknown binding destination type stays Unexpected; no hand trial; mergeable alone, dead code until 08b; depends on M2-04, M2-07 (merged)

## M2-08b-topology-view-model

step 2 done (test developer, RED on stubs); architect rulings 2026-11-05 on its six questions (brief, "08b step 2: architect rulings"): banners list accepted (screen draws the first), case-insensitive search, `NotReadable` uses the check's reason, super stream boolean kept (`ExchangeRow.superStream`, small step 2b by the test developer first); owner decision O1 = (b) level first (step 2c); step 3 BUILD DONE (senior developer, 2026-11-05, commits 19c9176, 791b528, 4640917): view model and wiring green, step 4 verified (test developer, pass with notes, 5 of 5 mutations caught); step 5 architect review (2026-11-05): ACCEPTED WITH FOLLOW-UPS (3 minors ride on 08c: headline constant into `TopologyTexts`, optional file split, draw only the first banner), ruling 6 text corrected (default exchange always on Exchanges), mockup `banner-reasons` updated, mergeable alone (dead code until 08c, nothing runs at start), no hand trial; depends on 08a round 2; T1, T3, T5 decided

## M2-08c-topology-screen

step 2 done (test developer, RED on stubs); architect rulings on the eight gaps (2026-11-05, brief section "08c: architect rulings"): all confirmed, mockup extended, two `02` scenarios get a Given (level Declare and edit), no test change, owner decision O2 (detail placement) not blocking; step 3 BUILD DONE (senior developer, 2026-11-05, commits b84d0b5, 0bf9af2, 7b0c21f): screen in `ui/.../topology/` (7 files), adapter, navigation, shown target, Main; no test edited; next: step 4 verify, then architect review; avoid `ui/streams/*` (f-c); step 4 verified (test developer, pass with notes); step 5 architect review (2026-11-06): ACCEPTED WITH FOLLOW-UPS (F1 vhost home caption, S; F2 share search field, message, CopyableText, banner with the streams package after merge, S); tester findings ruled: caption drawn, widow accepted, mockup note dropped; 08a+08b+08c mergeable together (no conflict with 6cfa794); owner hand trial open, O2 open, G2/U2 open for 08d

## M2-08f1-vhost-home-caption

step 1 brief confirmed; step 2 tests first (test-developer, red on assertions, commit `e7a6693`); step 3 build done (senior-developer, commit `53f5db1`); step 4 verified (test-developer, unit/UI/desktopApp/acceptance green); step 5 architect review (solution-architect): ACCEPTED, merged into main

## M2-08f2-shared-components

step 1 brief confirmed; step 2 guard & test first (test-developer, commit `919bcb1`); step 3 build done (senior-developer, commit `1841e66`); step 4 verified (test-developer, tests & screenshots green); step 5 architect review (solution-architect): ACCEPTED, merged into main

## M2-08d-bindings-graph

step 1 brief confirmed; step 2 tests first (test-developer, BDD scenarios un-skipped & unit tests); step 3 build done (senior-developer, pure DAG in core, Compose canvas & cards); step 4 verified (test-developer, all unit/UI/BDD tests & screenshots green); step 5 architect review (solution-architect): ACCEPTED, merged into main

## M2-08d1-bindings-graph-fixes

done (architect: Accepted, step 5, 2026-11-06; branch `m2-08d1-bindings-graph-fixes`, merged into `main` and pushed)

## M2-11-shutdown-hook

done (architect: Accepted, 2026-11-06; merged into local `main`, not pushed)

## M2-12-local-dev-environment

briefed (`M2-12-local-dev-environment.md`), no dependencies; owner answers 2026-11-05 recorded and the three open questions closed: topology-only "migration" (no write feature in the app), the 1000-queue set generated at seed time with a parameterised count (no committed file), the throwaway test certificates reused; survey of the real-broker test helpers added to the brief (TLS fixtures, container recipe, rabbitmqctl and management HTTP helpers, stream publish loop: reuse them, one home for the TLS files); step 1 confirmed (architect, 2026-11-05, branch `m2-12-local-dev-environment`): `dev-seed` does not depend on `data-rabbit` (its TLS and management classes are internal), fixtures stay in `data-rabbit/src/test/resources/tls`, first acceptance A1 (broker with data, then the app lists streams once M2-05 exists), checks C1 to C9, spikes S-A to S-C first; no product decision needed; step 2 spikes S-A to S-C all worked, 34 red seed tests; step 3 (architect): `--queues` absent = 0, `--password` only required flag, `ManyQueuesDefinitions` and `--max-age` in scope, spec line added, build handoff written (commit 1 broker files, commit 2 seed and wrappers); step 3 build done (senior developer): both commits made, 34 seed tests green with no test edit, `dev:up`/`dev:seed`/`dev:reset` and `dev/README.md` in, C1 to C9 recorded in the brief Status; step 5 review (architect): Accepted with follow-ups, ready to merge into local main, follow-ups in `M2-12b-dev-seed-hardening.md` (CA hint, reject `--max-age` with `--queues` off `/`, README notes)

## M2-12b-dev-seed-hardening

step 2 done (test developer): 10 new unit tests in `dev-seed` (4 red for the right reason, 6 green guards), stub `caHint` in `CaHint.kt`; step 3 build done (senior developer): hint, flag rejection, README with measured `--queues` ceiling (50,000); one existing test (`every flag is read`) used the now-rejected mix; step 4 (test developer): that test edited (`--max-age` read in a second call), `./gradlew test` green (`dev-seed` 44), mutation check 4 red as expected, no finding; step 5 (architect): Accepted, ready to merge into local main, nothing pushed (`M2-12b-dev-seed-hardening.md`)

## M2-06-f1-dev-seed-hold

step 3 done (developer, 2026-11-05): 11 tests green, real check passed; step 4 verify (tester): pass with findings, 83 dev-seed tests green, real plain and TLS checks; step 5 (architect): Accepted with follow-ups, ready to merge into local main, nothing pushed

## M2-10-testcontainers-2x-migration

briefed (optional, needs bump approval); no hurry

## M2-09-gate-stats-match

done (architect: Accepted, 2026-11-06; merged into local `main`)

## M-MCP-01-stdio-server

done (accepted, solution-architect 2026-11-06; merged into local `main`, not pushed; branch `m-mcp-01-stdio-server` kept; 7 BDD scenarios green, 5 unit tests green)

## M2-15-podman-fedora-dev

done (architect: Accepted, step 5 round 2, 2026-11-06; branch `m2-15-podman-fedora-dev`; 48/48 Gradle tasks and 98/98 `@broker` tests green on Fedora 45 + rootless Podman)

## M2-16-management-http-stale-connection

briefed (architect, 2026-11-15): decision option (c) both, D2 unchanged; size S; one new `@broker` scenario in `13`; no owner question (optional product question about tolerating a failed poll is not asked, see the brief); steps 1 to 3 done (2026-11-15; one guard bound corrected by architect ruling), DONE, architect verdict Accepted (2026-11-15), full fast suite green, waiting for the owner to merge branch `m2-16-management-http-stale-connection`; depends on none (5ad11dd on main)

## M2-17-s-a-lag-steady-state

briefed (architect, 2026-11-15): decision the test over-asserted, the screen rule is unchanged; size S, test-only; independently confirmed by a probe (steady state: every consumer 99 and 2 on the four-consumer set-up, 99 and 0 with no stores, 99 and 4 with ten stores); step 1 confirmed by the architect on branch `m2-17-s-a-lag-steady-state` (2026-11-15, brief corrected in place, READY); step 2 done (S-A reshaped, new S-A2, class green 3 of 3, stop condition did not fire); architect ruled S-A must also observe the steady state (12 s, third pass-through), built in step 2b (09a7ef7, class green once, S-A reaches the steady state 99/4); step 4 verified PASS (five green runs in a row, loaded run green, mutations (a) to (c) and (e) red where expected, no blocker or major); step 5 architect verdict Accepted (2026-11-15, no blocker or major, two wording fixes applied, no follow-up task), full `./gradlew test` green (7635 tests, 0 failed); DONE, waiting for the owner to merge branch `m2-17-s-a-lag-steady-state`; step 3 not applicable (no production change); depends on M2-06 (merged)

## M2-17b-lag-hint-wording

done (coordinator, 2026-11-15), merged to main (8c4719b): owner confirmed option A (tooltip) and F1 (footnote) on 2026-11-15; architect verdicts Accepted with follow-ups (tooltip and addendum, round 0); fast set green (core 2333, ui 816, acceptance 0 failed, verifyTestDiscovery; one pre-existing flaky core test, see M2-TESTHYGIENE-04), screenshot class run and looked at; depends on M2-17 facts

## M2-17c-consumer-row-missing-lag

briefed (architect, 2026-11-15, 1c978f6, branch `m2-17c-consumer-row-missing-lag`); size S; not a product decision (existing state and words); step 2 done (test developer: shim 6de4ab2, live probe S-D green 125 of 125 raw reads, tests red 11f080d), step 3 done (senior developer, 031726c: three commands green twice), step 4 verify PASS (3 mutations red, fast set green, `verifyTestDiscovery` ok), step 5 architect review: Accepted (2026-11-15; no blocker, no major, ideas only; doc edits applied, 8eabc66), waiting for the owner's merge word

## M2-17d-hint-popup-bottom-row

briefed (architect, 2026-11-15); size S; the probe is RED (test developer, f6544f1 on branch `m2-17d-hint-popup-bottom-row`: the popup of the bottom-most visible row ends 55 px (top) and 65 px (middle) below the list viewport), so the defect is real; step 3 done (senior developer, e4b89c4: below by default, above when it does not fit, clamped otherwise; probe green), step 4 verify PASS (3 mutations red, fast set green, `verifyTestDiscovery` ok), step 5 architect review: Accepted with follow-ups (2026-11-15; no blocker, no major; mockup note applied, 936f35f), waiting for the owner's merge word; depends on none

## M2-17e-hint-popup-short-window

idea (architect, 2026-11-15, follow-up of M2-17d); size S; no brief

## M2-17f-consumer-offset-text-first

idea (architect, 2026-11-15, follow-up of M2-17c; the architect proposed id M2-17e, renamed M2-17f by the coordinator because M2-17e is taken on the M2-17d branch); size S; trigger: the owner sees "0" or "Caught up" flicker on a single-active group in hand use, or someone next changes `parseConsumers` or `isSettling` (then bundle it); no brief

## M2-TESTHYGIENE-04-fake-calls-iteration

briefed (architect, 2026-11-15); size S; test-side only; steps 2 and 3 done on branch `m2-testhygiene-04-fake-calls-iteration` (6be3089 red, dc8d8eb green), step 4 verify PASS (fast set green, `verifyTestDiscovery` ok), step 5 architect review: Accepted with follow-ups (2026-11-15; no blocker, no major), waiting for the owner's merge word; depends on none

## M2-TESTHYGIENE-05-await-polls-calls

idea (architect, 2026-11-15, follow-up of M2-TESTHYGIENE-04); no brief

## M2-TESTHYGIENE-06-other-recording-lists

idea (architect, 2026-11-15, follow-up of M2-TESTHYGIENE-04); trigger-based, no brief

## M3-01-browser-gateway-and-reader

done (architect: Accepted, 2026-11-06; branch `m3-01-browser-gateway-and-reader`)

## M3-02-browser-view-model-and-paging

done (architect: Accepted, 2026-11-06; branch `m3-02-browser-view-model-and-paging`; 12 unit tests green, 16 BDD scenarios green, mutations killed)

## M3-03-browser-screen-and-navigation

done (architect: Accepted, 2026-11-06; branch `m3-03-browser-screen-and-navigation`; UI tests green, BDD scenarios unskipped and green, mutations killed)

## M3-04-gate-real-broker-paging-and-safety

done (architect: Accepted, 2026-11-06; branch `m3-04-gate-real-broker-paging-and-safety`; real TLS RabbitMQ 4 broker tests pass, mutations killed)

## M4-01-payload-viewer-core-and-formatting

done (architect: Accepted, 2026-11-06; branch `m4-01-payload-viewer-core-and-formatting`; 12 unit tests green, 7 BDD scenarios green)

## M4-02-filter-scan-engine

done (architect: Accepted, 2026-11-06; branch `m4-02-filter-scan-engine`; 5 unit tests green, 11 BDD scenarios green)

## M4-03-viewer-and-filter-ui

done (architect: Accepted, 2026-11-07; branch `m4-03-viewer-and-filter-ui`; 11 Compose UI tests green)

## M4-04-gate-large-bodies-and-safety

done (architect: Accepted, 2026-11-07; branch `m4-04-gate-large-bodies-and-safety`; 2 broker tests green)

## M5-01-publish-test-message-core

done (architect: Accepted, 2026-11-07; branch `m5-01-publish-test-message-core`; 10 unit tests green, 8 BDD scenarios green)

## M5-02-publish-test-message-ui

done (architect: Accepted, 2026-11-07; branch `m5-02-publish-test-message-ui`; 12 Compose UI tests green)

## M5-03-stream-management-create-delete

done (architect: Accepted, 2026-11-07; branch `m5-03-stream-management-create-delete`; 11 BDD scenarios green, 11 Compose UI tests green, 10 ViewModel tests green)

## M5-04-topology-migrations

done (architect: Accepted, 2026-11-07; branch `m5-04-topology-migrations`; 14 BDD scenarios green, 10 Compose UI tests green, 8 ViewModel tests green)

## M5-05-verify-host-name

done (architect: Accepted, 2026-11-08; branch `m5-05-verify-host-name`; all tests green)

## M5-06-gate-packaging-and-broker

done (architect: Accepted, 2026-11-08; branch `m5-06-gate-packaging-and-broker`; 5 broker tests green, DMG packaged)

## M6-01-mqtt-connection-profile

done (architect: Accepted, 2026-11-07; branch `m6-01-mqtt-connection-profile`; all tests green)

## M6-02-mqtt-topology-core-and-data

done (architect: Accepted, 2026-11-07; branch `m6-02-mqtt-topology-core-and-data`; all tests green)

## M6-03-mqtt-topology-ui

done (architect: Accepted, 2026-11-07; branch `m6-03-mqtt-topology-ui`; all tests green)

## M6-04-mqtt-sender-engine

done (architect: Accepted, 2026-11-08; branch `m6-04-mqtt-sender-engine`; all tests green)

## M6-05-mqtt-sender-ui

done (architect: Accepted, 2026-11-08; branch `m6-05-mqtt-sender-ui`; all tests green)

## M6-06-mqtt-mcp-tools

done (architect: Accepted, 2026-11-08; branch `m6-06-mqtt-mcp-tools`; all tests green)

## M7-01-stream-log-tape

done (accepted by the product owner 2026-11-09, retroactive brief; built outside the team loop; commit 923cd3d, on `main`)

## M7-02-p0-wiring-fixes

done (accepted by the product owner 2026-11-09, retroactive brief; commit ad6bd15)

## M7-03-p1-layout-ergonomics

done (accepted by the product owner 2026-11-09, retroactive brief; commit 544025a; number assigned in commit order)

## M7-04-mqtt-to-stream-workflow

done (accepted by the product owner 2026-11-09, retroactive brief; commit 6fb91e3; number assigned in commit order)

## M7-05-rolling-sparklines-lag-trends

done (accepted by the product owner 2026-11-09, retroactive brief; commit c9b51de; number assigned in commit order)

## M7-06-pipeline-graph-mqtt-subscribers

done (accepted by the product owner 2026-11-09, retroactive brief; commit 0fe7b1b; number assigned in commit order)

## M7-07-partition-balance-bar

done (accepted by the product owner 2026-11-09, retroactive brief; commit 9982df1)

## M7-08-queue-message-inspection

done (accepted by the product owner 2026-11-09, retroactive brief; commit d501d11; OPEN RISK: delivery-limit effect of requeue, live test recommended)

## M7-09-headers-properties-inspection

done (accepted by the product owner 2026-11-09, retroactive brief; commit 21193c7; no tests added)

## M7-10-mqtt-headers-default-payload

done (accepted by the product owner 2026-11-09, retroactive brief; commits 85987cc, 09f1d91)

## M7-11-review-follow-ups

done (accepted by the product owner 2026-11-09, retroactive brief; commits 9134e60, dc5f82b; number assigned in commit order)

## M8 milestone status paragraph (as of 2026-11-11, removed from the index on 2026-11-17)

**Status 2026-11-11 (M8): BUILT, awaiting the owner's acceptance.** `M8-01` to `M8-12` are done and merged (the gate `M8-11` is built and in review); not built: `M8-06f` (view model cleanup), `M8-13` follow-up spikes, the polish ideas `M8-05c`, `M8-09b`, `M8-12b-polish`; `M8-06d` is superseded. Owner to do: a hand trial of the Migrate from 3.x screen against a real 3.x broker, and the review of mockup sections 10b and 14.

## M8-00-spike-live-3x-vs-4x

DONE 2026-11-09 (results `M8-00-spike-results.md`, tests `RealBrokerM8Spike*Test`; 23 tests pass; findings folded into spec and briefs)

## M8-01-definitions-model-and-file-import

brief confirmed and sharpened (architect, team loop step 1 done 2026-11-09, round 0; branch `m8-01-definitions-model-and-file-import`); owner decided 2026-11-09: a per-vhost export is accepted and the user names the virtual host (P17), brief updated; tests first written, red for the right reason (test developer, step 2 done 2026-11-09, round 0); build done, tests green (senior developer, step 3 done 2026-11-09, round 2); test developer verification done (step 4, round 0): F1/F2 fixed and re-verified (round 1), one minor open (F3: bare `True` argument becomes `Flag(false)`, red test `DefinitionsParserNonJsonLiteralTest`); built and verified; architect verdict ACCEPTED WITH FOLLOW-UPS (step 5, round 1, commit 4d3aa71, full suite green); branch NOT merged, waiting for the owner's word to merge; follow-up `M5-07`; depends on M8-00

## M8-02-live-3x-source

brief exact against the merged code and re-cut (architect, team loop step 1 done 2026-11-10, round 0; branch `m8-02-live-3x-source`, base fe181be); M8-02a built (step 3 round 1 done 2026-11-10; one desktopApp test blocked by a test-side fault); 14 `@m8-02 @fake-broker` scenarios, 1 `@m8-02b`, 3 `@broker-3x @m8-02` in `16`; mockup `Main.dc.html` updated; no new dependency; one owner decision (disabled navigation, 2026-11-10) built; M8-02a BUILT AND VERIFIED, ACCEPTED WITH FOLLOW-UPS (architect step 5, 2026-11-10, HEAD 89cf7e1, branch not merged, waiting for the owner's word; F4 drawer reason shown four times: DECIDED owner 2026-11-10: option B, one note under the drawer plus tooltip and description, in M8-06c; A stays until then); M8-02b built (senior developer, step 3 round 0, 2026-11-10, branch `m8-02b-live-3x-reads`): all stubs replaced, one test blocked by a test-side fault (301 case of `ManagementThreeXReadsTest`); live class 5 of 5 green; only the three `@broker-3x` scenarios need Docker or Podman; depends on M8-00, M8-01, M8-03, M5-08a (merged); M8-06c waits for it

## M8-03-compatibility-analyzer

brief exact against the merged M8-01 code (architect, team loop step 1 done 2026-11-09, round 0; branch `m8-03-compatibility-analyzer`); 25 scenarios in `16` and 5 in `18` tagged `@m8-03`; no new dependency; no blocking product decision, four architect defaults for the owner to veto (see the brief's Open points); tests first written, red for the right reason (test developer, step 2 done 2026-11-09, round 0; 46 scenarios and 13 unit test classes red on compile stubs `M803TestStubs.kt`; golden list and 10 questions in the brief's Status); build done, tests green (senior developer, step 3 done 2026-11-09, round 0; stub file replaced by 21 real files in `core/.../migrate3x/`, no new dependency, no build change); test developer verification done (step 4, round 0, commit 292d1c8): PASS WITH FINDINGS, 6 mutations caught (e is an equivalent mutant, variants caught), 1 major (F1: regex hang on a hostile policy pattern), 3 minor, 2 ideas; next: architect verdict (step 5); Round 1 done: own linear-time pattern matcher, text escaping, unique ids, clearer header; verified; architect verdict ACCEPTED WITH FOLLOW-UPS (step 5 round 1, 2026-11-09); built and verified, branch not merged, waiting for the owner's word; four architect defaults listed for a possible veto (brief, Status); depends on M8-00, M8-01 (merged)

## M8-04-widen-migration-model

brief exact against the code as it is (architect, team loop step 1 done 2026-11-09, round 0; branch `m8-04-widen-migration-model`); 8 `@m8-04` scenarios in `10` and 1 in `16`; no new dependency; FINDING: no real migration executor exists (no-op in the desktop app, in-memory files and history), so it is split off as `M5-08`; owner decided 2026-11-09: A (`M5-08` separate task), both architect additions kept; step 2 tests first DONE (test developer, round 0, red on compile stubs: 43 core + 18 data-rabbit + 2 ui unit tests, 9 scenarios of `10` red; live test written, not run); step 3 implementation DONE (senior developer, round 0, review round 1 fixes F1 to F4 done: wrong-typed values give Invalid, dot names, one-line detail; all `@m8-04` scenarios and unit tests green, live test compiled and not run; ready for step 4 verification); verified (step 4 PASS, live test 4/4 on 4.x); round 1 done (wrong-typed values refused with fixed messages, dot names, one-line detail); verified (core 1428, live 4/4 on 4.x, all mutations caught); architect verdict ACCEPTED WITH FOLLOW-UPS (step 5 round 1, 2026-11-09); built and verified, branch not merged, waiting for the owner's word; follow-up: version validation inside `M5-08`; depends on M8-00, M8-01, M5-07

## M8-05-conversion-proposals-and-review-core

brief confirmed and cut to size M (architect, 2026-11-10, round 0; branch `m8-05-conversion-proposals-and-review-core`); 19 `@m8-05` scenarios in `16`; no owner decision needed; no dependency on M8-12 any more; tests first DONE incl. architect answers applied (test developer, step 2c: core 84 of 104 red, 19 of 19 `@m8-05` scenarios red, stubs marked `M8-05 STUB`; questions for the architect in the report); architect answered the step 2 questions (step 2b, 2026-11-10: document order, unwritable items outside `pending`, unknown queue type is a Warning accepted as in the source, version = highest + 1, `Replace` with a delete refused); the test developer adjusts a few tests; step 3 (senior developer, round 0) DONE: stubs replaced, core 1738 green (104 M8-05 tests), 19 of 19 `@m8-05` scenarios green, step 4 (test developer, round 0, build 0538baa) PASS: full fast set green, `verifyTestDiscovery` green, mutations a-h all caught, 7 exploratory tests added, no blocker or major; step 5 (architect, 2026-11-10) ACCEPTED WITH FOLLOW-UPS: ready to merge, follow-up `M8-05b-replace-validation` (S), spec updated, MCP none; depends on M8-01, M8-03, M8-04 (merged)

## M8-05b-replace-validation

brief written (architect); tests first DONE (test developer, step 2, round 0): core 4 of 458 migrate3x tests red by assertion (3 in `ItemDecisionTest`, the tightened `ConversionExploratoryTest` case), stub `ReplaceInvalid` marked `M8-05b STUB`, no new BDD scenario (core-level rule, no user-visible step before M8-06); depends on M8-05 (merged); build DONE (senior developer, step 3, round 0): `decide` round-trips a Replace through the migration parser and refuses another vhost, stub marker removed, core 1749 and acceptance-tests 725 green, step 4 (test developer, round 0, build 9ce08db) PASS, mutations a-c caught; step 5 (architect, 2026-11-10) CHANGES REQUIRED, round 1: vhost names in `ReplaceInvalid` must follow the `echoableName` rule (F1 major), drop the unreachable null-vhost branch and name the constants (minor); (round 1 done and verified; step 5 re-review 2026-11-10 ACCEPTED, branch not merged, waiting for the owner's word) was: tests first for F1, then senior developer; step 3 round 1: F1-F3 done, BLOCKED on one exploratory test (sentinel is echoable by the rule)

## M8-06-report-and-review-ui

re-cut (architect, team loop step 1, 2026-11-10, round 0; branch `m8-06-report-and-review-ui`): the old L brief depended on the unbuilt M8-02 and M8-12; UX mockup `docs/ux/Migrate3x.dc.html` written, owner review needed before step 2 of M8-06a; all nine owner decisions made 2026-11-10 (entry A, grouping A, closed groups A, held in memory A, wording ok, skipped items B: separate "Skipped by you" group with un-skip, generating again A, Replace editor A, cut as proposed); owner still looks at the mockup

## M8-06a-file-source-and-report

13 `@m8-06a` scenarios in `16`; owner decisions made; briefed (architect, 2026-11-10); depends on M8-01, M8-03 (merged) and the owner's mockup review; tests first DONE (test developer, step 2, round 0): 16 `@m8-06a` scenarios bound (step 2c applied the architect answers), 19 of 19 runs red by assertion, core 45 of 54 and ui 30 of 32 new tests red; build DONE (step 3); step 4 (test developer, round 0) PASS: 4258 fast tests and `verifyTestDiscovery` green, mutations a-e caught, no blocker or major, no screenshot test for the screen yet; round 1 step 4 found the narrow header; round 2 step 4 PASS (4288 tests green, finding fixed, mutations caught); round 1 step 2 done (single column tests, tooltip, rule titles, escapeForText hardening tests, screenshot test), stubs marked `M8-06a STUB`, questions for the architect in the report; build DONE (senior developer, step 3, round 0, branch `m8-06-report-and-review-ui`): stubs replaced, core, ui and desktopApp wired, round 1 (single column, tooltip, `escapeForText` hardening) built, round 2 (narrow header wraps) built, all fast suites green

## M8-06e-report-polish

DONE: built and verified (HEAD 3a93c6c), architect review accepted with follow-ups (2026-11-11); one minor follow-up (memoise the filtered rows of a group with skipped items); waits for the owner's merge; M8-06f runs right after

## M8-06b-review-generate-save

16 `@m8-06b` scenarios in `16`; brief confirmed with exact signatures (architect, 2026-11-10, step 1); step 2 tests first done, step 3 built, step 4 verify PASS (test developer, 2026-11-10: full fast set green, 8/8 mutations caught, screenshots looked at, minor findings in the brief Status); step 3 round 0 built (senior developer, 2026-11-10, stubs replaced, a few expectations of the step 2 tests disagree with the core, see the brief Status); depends on M8-06a, M8-05, M8-05b, M5-08 (merged), M8-06e should land first; BUILT and reviewed (architect, 2026-11-10): accepted with follow-ups `M8-06e` (item 6 added) and `M8-06f`

## M8-06f-review-polish

READY (architect, 2026-11-11): made exact against main after 06e; depends on M8-06e merged; BEFORE M8-06d

## M8-05c-scale-test-speed

idea (architect, 2026-11-11); no owner question; becomes a task only if CI time hurts

## M8-06c-live-source-on-screen

named only (architect, 2026-11-10); BRIEFED 2026-11-10 in `M8-06c-live-source-on-screen.md` (architect, step 5 of M8-02b; five new `@m8-06c` scenarios, owner decision F8 = B made 2026-11-10: the 3.x pill on a profile card only while Connected); scope note (owner decision F4, 2026-11-10): the drawer shows ONE note `navReasonNote` instead of the reason under every disabled item and the search (tags `navItemReason:*` and `searchReason` go), tooltip and accessible description stay; depends on M8-02, M8-06a; STEP 1 DONE (architect, 2026-11-10, round 0, branch `m8-06c-live-source-on-screen`, base 99648cc): brief exact (`LiveSourceProvider` is a StateFlow interface, `HeldLiveSourceProvider` in core, picker as a second flow so `ChooseSource` stays, new states `LoadingLive`, `CannotExport`, `LiveFailed`, `HeldAnalysis.kind`, `SourceCardView`, `cardPill`, `offersMigration`, `AppNavigation.openMigrateFromThree`), tags in kebab case, 8 `@m8-06c` scenarios (2 new), new suite `RunMigrate3xLiveScreenAcceptanceTest`, mockups noted; one non-blocking owner wording question (the info line at the top; DECIDED option (b)); STEP 3 round 0 built (senior developer, 2026-11-10, branch `m8-06c-live-source-on-screen`): all `M8-06c STUB` markers replaced, see the brief Status for the test counts; BUILT (f21d15f), VERIFIED (step 4 PASS, 17 of 17 mutations); ARCHITECT REVIEW step 5 round 0 (2026-11-10): CHANGES REQUIRED, two small items: R1 the live report must not say "a definitions file cannot tell the index version" (live loss-block text, 2 tests), R2 `CannotExport` must show the export hint (the owner-approved file hint, no new wording); finding 4 (texts into core) rides along; polish for banners and icons goes to M8-06e; M8-06f (GenerationFlow) to be done before M8-06d; new scenario "A live report says that the broker reported the index version"; STEP 3 round 1 done (senior developer, 2026-11-10): R1, R2 and the text move fixed, all four suites green, see the brief Status; ROUND 1 (2026-11-10): R1 and R2 closed, re-review ACCEPTED WITH FOLLOW-UPS at HEAD 16cfc42; M8-06c DONE, waiting for the owner's word to merge; follow-ups: banner and icon polish is item 7 of M8-06e, M8-06f before M8-06d; one wording FYI for the owner (the live loss text)

## M8-06d-access-on-screen

superseded if the owner accepts D1 = A

## M8-07-shovel-core-and-safeguards

DONE: built and verified (HEAD 5c1c6ad), architect review accepted (2026-11-11); waits for the owner's merge; 23 `@m8-07` scenarios green; owner priority: M8-07, 08, 09 before 06d and 06f

## M8-08-shovel-management-implementation

DONE: built and verified (HEAD ef6e797, live 5 of 5), architect review accepted with one follow-up (remove `MAX_SHOVEL_NAME_LENGTH`, 2026-11-11); waits for the owner's merge; M8-09 next

## M8-09-forwarding-ui

DONE: built and verified (HEAD 91a3d67, 6 `@m8-09` scenarios, full `./gradlew test` and `verifyTestDiscovery` green); architect review accepted with follow-ups (2026-11-11); waits for the owner's merge; no MCP tool

## M8-09b-forwarding-polish

idea (architect, 2026-11-11); no owner question; do only if touching the file

## M8-10-mcp-report-and-shovel-status

DONE: built and verified (HEAD 7bf5a13, full fast set and `verifyTestDiscovery` green, 5 mutations red), architect review accepted (2026-11-11); waits for the owner's merge; run `RealBrokerM8ShovelTest` once before merge (shared status mapping); no new dependency

## M8-11-gate-3x-to-4x

built, green on a real 3.13 and 4.x (HEAD eb4b576), architect review accepted (2026-11-11); step 4: at most two live mutation runs (skip the access apply; a sentinel in a log line); then waits for the owner's merge. Step 4 (test developer): mutations (a) and (b) red on the live gate, clean run green, verifyTestDiscovery green

## M8-12-access-migration-actions

briefed against main `ce58253` (architect, branch `m8-12-access-migration`); UMBRELLA DONE 2026-11-11 (12a, 12c merged; 12b accepted, awaiting merge); owner decisions FINAL 2026-11-11: D1 A (no per-user review), D2 A (guest never generated; narrows P11), D3 approved warning text, D4 A (raw view hides hashes), D5 B (a differing carried vhost setting is a Conflict, `/` exempt: the owner confirms the exemption); no MCP tool; ready for step 2 of M8-12a; M8-12a STEP 3 round 0 built (senior developer, 2026-11-11): core, fake, runner, view model, acceptance green; whoami tags in the real checker not done (existing single-request test), see the M8-12a Status; M8-12c STEP 3 round 0 built (senior developer, 2026-11-11): executor, reader, allow-list, whoami tags done; one old allow-list test needs three rows removed, see the M8-12c Status; M8-12b STEP 3 round 0 built (senior developer, 2026-11-11): new tests green; 29 old tests that assume no access file need updating, see the M8-12b Status

## M8-12a-access-actions-core

DONE: built and verified (HEAD 2e293b5, 13 `@m8-12a` scenarios green), architect review accepted (2026-11-11); waits for the owner's merge; next: M8-12c

## M8-12c-access-executor-and-live

DONE: built and verified (HEAD 6325d9d, live 5 of 5 on real 3.13 and 4.x), architect review accepted (2026-11-11); waits for the owner's merge; recommended before merge: `RealBrokerM5GateTest` once (shared checker and allow-list); next: M8-12b

## M8-12b-access-generation-and-warning

DONE: built and verified (HEAD 1cbbd61), architect review accepted after round 1 (2026-11-11); waits for the owner's merge; supersedes M8-06d

## M8-12b-polish

idea (architect, 2026-11-11); no owner question; do only if touching the files

## M8-13-follow-up-spikes

briefed (architect, 2026-11-09); depends on M8-00 (done)

## M8-14-in-app-guidance

14a done (awaiting merge), 14b next. 14b step 2 (test developer): tests first, core 13 of 25 and ui 18 red; 14b built and verified (HEAD e3dfcfa, step 4 pass with findings); step 5 architect review (2026-11-12): CHANGES REQUIRED, two small fixes (dead `plainWithoutLine` branch, Rtl layout direction leaking into the dialog) plus three minors, then a delta check; app bar wrap at 1024x768 is pre-existing, follow-up `M8-14c-migrations-app-bar-narrow`

## M9 milestone status paragraph (as of 2026-11-15, removed from the index on 2026-11-17)

**Status 2026-11-15 (M9): PLANNED, nothing built; the owner answered the open questions Q1 to Q16 the same day (spec decisions O12 and O13) and then confirmed the revised Q3, answered Q17, kept the sample-value test message and ordered `M9-14` (O14), so the build can start and nothing is open for the owner except the mockup review before `M9-03` step 2.** Asked for by the owner ("planning topology with json not intuitive, can you plan a tool for planning / executing / export / import topologies", "add MQTT ability too"). Spec: `docs/spec.md`, "Topology planner (M9)" (goals, non-goals, owner decisions O1 to O14, architect decisions A1 to A15, facts to verify, MCP evaluation: no tool). All of Q1 to Q16 are decided as recommended except Q3, which the owner's IoT use case revised (O13); the architect made that revision from the owner's description and the owner CONFIRMED it on 2026-11-15 (O14). Q17 (the header key of the test message) was answered the same day, as recommended. The owner's answer to Q15 was applied the same day: feature `09` and two spec lines were rewritten, the Topology screen stays read-only.

## M9-00a-spike-mqtt-routes-and-headers

briefed (architect, 2026-11-15), extended 2026-11-15 with S-A5 to S-A8 (owner's IoT flow); no dependency; unblocks `M9-07b`, `M9-10`, `M9-11`; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-00a-spike-mqtt-routes-and-headers`: brief changed (results in `M9-00a-spike-results.md`, one file per spike; how the spike is run; contract for M9-07b, M9-10, M9-11; S-B in its own class); step 2 (tests and live run) done 2026-11-16, round 0: 4 classes, 23 of 23 pass on RabbitMQ 4.4.0, all items measured, results in `M9-00a-spike-results.md`; step 3 (architect) done 2026-11-16, round 0: ACCEPTED WITH FOLLOW-UPS (spec "Measured facts of M9-00a", A16, scenarios of `21` untagged, `M9-07b`, `M9-10`, `M9-11` updated, Owner review list of 10 items in the brief, new briefs `M9-00a-b-spike-test-hardening` and `M9-10c-mqtt-exchange-hint`); wording is for the owner, no product decision open

## M9-00a-b-spike-test-hardening

NEW, briefed (architect, 2026-11-16, step 3 of M9-00a); blocks nothing

## M9-00b-spike-dead-lettering-and-streams

briefed (architect, 2026-11-15); no dependency; unblocks `M9-07`, `M9-09`; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-00b-spike-dead-lettering-and-streams`: brief changed (results in `M9-00b-spike-results.md`; how the spike is run; the three facts S-D1 to S-D3 the one broker sentence of the response-stream note needs, listed for the owner); no open product question; step 2 (test developer) done 2026-11-16, round 0: `RealBrokerM9SpikeDeadLetterTest` (13) and `RealBrokerM9SpikeStreamDeadLetterTest` (7) pass on RabbitMQ 4.4.0, results in `M9-00b-spike-results.md`; S-D1 to S-D3 all agree with the response-stream sentence; surprises listed there; step 3 (architect) done 2026-11-16, round 0: ACCEPTED WITH FOLLOW-UPS (spec "Measured facts of M9-00b", six `@m9-09` scenarios untagged `@needs-spike`, ten scenarios added, briefs `M9-05`, `M9-07`, `M9-08`, `M9-09`, `M9-13` updated; new briefs `M9-00b-b-spike-test-hardening` and `M9-00b-c-readable-death-annotations`; Owner review list of 12 items in the brief; the one broker sentence of the response-stream note is allowed by S-D1 to S-D3 and is the owner's call); next: step 4 not needed for a spike; DONE (Accepted with follow-ups 2026-11-16, not merged yet, owner review list open)

## M9-00b-b-spike-test-hardening

NEW, briefed (architect, 2026-11-16, step 3 of M9-00b); blocks nothing; step 2 (test developer) done 2026-11-17, round 0, branch `m9-00b-b-spike-test-hardening`: R1 to R5 asserted and each flipped once, S-C5 policy variant kept, default overflow pinned to `m3, m4`, retry test not moved; 14 + 7 tests pass on RabbitMQ 4.4.0, no claim disproved; step 5 (architect) done 2026-11-17, round 1: ACCEPTED (R1 to R6 closed, spec and results wording corrected); DONE on branch `m9-00b-b-spike-test-hardening` (not merged, owner decides)

## M9-00b-c-readable-death-annotations

NEW, briefed (architect, 2026-11-16, step 3 of M9-00b); defect found by S-C7; one owner question (filter); owner item 11 decided and running approved (2026-11-17, O16): the filter matches the whole rendered text only; step 1 (architect, round 0) done; step 2 (test developer) and step 2.5 (architect) done; steps 3 and 4 done (PASS); step 5 review round 1: Accepted with follow-ups (M9-00b-d widened to any undecodable message); DONE 2026-11-17; owner item 1 (format `[a, b]` / `{key: value}`) open, recommendation accept

## M9-00c-spike-policies-equivalence-names

briefed (architect, 2026-11-15); no dependency; unblocks `M9-05b`, `M9-08`, `M9-09` (not `M9-05`: cut 2026-11-16); team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-00c-spike-policies-equivalence-names`: brief changed (results in `M9-00c-spike-results.md`; how the spike is run; it creates the core-testing constants `PolicyKeyMatrix` and `DeclareEquivalenceTable`; real GET answers for the names `.` and `..` added to S-H; starts first because `M9-05b` waits for it); no open product question; step 2 (test developer) done 2026-11-16, round 0: 25 live tests pass (policy keys 12, declare equivalence 9, names 4), results in `M9-00c-spike-results.md`, constants `PolicyKeyMatrix` and `DeclareEquivalenceTable` in core-testing; next: architect (step 3); team loop step 3 (architect) done 2026-11-16, round 0: ACCEPTED WITH FOLLOW-UPS (spec, scenarios and briefs updated; new briefs `M9-01b`, `M9-08b`; `M9-05b` rewritten; one numbered owner review list in the brief); next: step 4 not needed for a spike, the owner answered the list on 2026-11-16 ("do arch suggest", all ten items as recommended); DONE (Accepted with follow-ups 2026-11-16, merged; owner review list open)

## M9-01-plan-core-forms-and-naming

briefed (architect, 2026-11-15); 18 `@m9-01` scenarios in `19`; no spike needed; Q1, Q5, Q6, Q7, Q13 decided; team loop step 1 (Brief) and step 2 (tests first, red) done 2026-11-16, round 0, branch `m9-01-plan-core-forms-and-naming`; step 3 (implementation) done 2026-11-16, round 1, all green; step 4 (test developer verifies) done 2026-11-16, round 1, pass, no blocker or major, 17 exploratory tests added; step 5 (architect verdict) done 2026-11-16, round 1: accepted with follow-ups (notes in M9-02 and M9-03, no new task); next: merge, then M9-02; DONE on branch `m9-01-plan-core-forms-and-naming` (round 1, Accepted with follow-ups 2026-11-16; not merged, owner decides)

## M9-01b-broker-name-rules

NEW, briefed (architect, M9-00c step 3, 2026-11-16); 2 `@m9-01b` outlines in `19` (the refusal wording decided 2026-11-16); depends on M9-01, M9-00c

## M9-02-planner-view-model-draft-and-save

briefed (architect, 2026-11-15); 20 `@m9-02` scenarios in `19` (19 and, added at step 2.5, "A draft that holds a password hash is reported and kept"); depends on M9-01; Q5, Q8, Q9, Q11 decided; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-02-planner-view-model-draft-and-save` stacked on `m9-01-plan-core-forms-and-naming` (`0cb1804`, not merged); brief changed (real M9-01 API, `TopologyPlan.of`, `PlannerEntry`, shared folder state, step-id rule); no open product question; step 2 (tests first, red) done 2026-11-16, round 0: 20 `@m9-02` scenarios bound (incl. the password-hash draft), 12 core and 2 data-rabbit test classes, fakes in `core-testing`; step 2.5 (architect) done 2026-11-16, round 0: API confirmed, one scenario added; step 3 (implementation) done 2026-11-16, round 1: all built, 19 of 20 scenarios and all but 5 thread tests green, the rest are two test-side defects reported to the test developer; step 4 (test developer) done 2026-11-16, round 1, pass; step 5 (architect verdict) done 2026-11-16, round 1: accepted with follow-ups (notes in M9-03, no new task); DONE on branch `m9-02-planner-view-model-draft-and-save` (stacked on M9-01, not merged, owner decides); DONE on branch `m9-02-planner-view-model-draft-and-save` (round 1, Accepted with follow-ups 2026-11-16; stacked on unmerged M9-01; owner decides merge)

## M9-03-planner-screen-and-wizard

briefed (architect, 2026-11-15); 7 `@m9-03` scenarios in `19` and 1 outline in `09`; depends on M9-02 (merged) and the owner's mockup review (wording only, does not block steps 2 to 5); Q11, Q15 decided; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-03-planner-screen-and-wizard`; brief changed (Check drawn disabled, small `core` additions, wording list for the owner); step 2 (tests first, red) done 2026-11-16, round 0: core, ui, desktopApp and acceptance tests written, the 7 `@m9-03` scenarios and the `09` outline bound; step 2.5 (architect) done 2026-11-16: API of the tests confirmed; step 3 (build) done 2026-11-16, round 1: all new tests green except two test-side items reported to the architect and test developer (the stale tab-stop fixture of `HelpFocusOrderUiTest`, the naming-menu screenshot test that needs `dialog = true`); screenshots in `ui/build/screenshots/planner-*.png` and `migrations-entry-*.png` for the owner's wording review; step 4 (test developer verifies) done 2026-11-16, round 1: pass with findings (one major: description field after a replaced view model; three minor), the two test-side items fixed, whole suite green; round 2 (F1, F2 fixed in 3796fa6) re-verified, no open blocker or major; step 5 (architect verdict) done 2026-11-16, round 2: accepted with follow-ups (hand-over note in M9-04, owner review list in the brief); DONE on branch `m9-03-planner-screen-and-wizard` (head c041466 plus docs, not merged, owner decides); DONE on branch `m9-03-planner-screen-and-wizard` (round 2, Accepted with follow-ups 2026-11-16; owner review list open; not merged, owner decides)

## M9-04-review-graph

briefed (architect, 2026-11-15); 9 `@m9-04` scenarios in `19` (3 added at step 1); depends on M9-03 (merged); no spike; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-04-review-graph`; brief changed (known names through a core port, Graph/JSON switch, view-model hand-over fix included as its own commit); no open product question; step 2 (tests first, red) done 2026-11-16, round 0: core, ui, desktopApp and acceptance tests written, the 9 `@m9-04` scenarios bound (filter widened), red on the missing production code; step 2.5 (architect) done 2026-11-16: API confirmed (additive graph parameters, `PlannerContext.known` deleted, hand-over last); step 3 (build) done 2026-11-16, round 1: all new tests green (core, ui, desktopApp, the 9 scenarios, whole suite), Topology screenshots byte-identical after the pure move, screenshots in `ui/build/screenshots/planner-graph-*.png`; step 4 (test developer verifies) done 2026-11-16, round 1: pass with findings (one major: the graph is clipped at the right with no scrollbar at 1280 px; two minor: the pending read says "could not be read", an uncaught reader exception); extraction gate and 9 `@m9-04` scenarios confirmed, whole suite green; round 2 fixes (F1, F2, F3) verified 2026-11-16: no open blocker or major, one minor (a two-column plan still needs the horizontal bar at 1280 px); step 5 (architect verdict) done 2026-11-16, round 2: ACCEPTED WITH FOLLOW-UPS (F4 breakpoint and F5 cancellation noted in `M9-05`; Owner review list of 11 items in the brief, item 1 is the new read of broker names); DONE on branch `m9-04-review-graph` (head cb12bf4 plus docs, not merged, owner decides); DONE on branch `m9-04-review-graph` (round 2, Accepted with follow-ups 2026-11-16; owner review list open; not merged, owner decides)

## M9-05-check-core-and-data

briefed (architect, 2026-11-15); 18 `@m9-05` scenarios in `20` (1 `@broker`); depends on M9-01, M9-02 only; Q14 decided; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-05-check-core-and-data`: brief changed, the S-G argument rule and its one live scenario cut into `M9-05b` so the task does not wait for the spike (comparison = today's `DeclaredResourceMatcher`), F5 stays here as a core commit, F4 moved to `M9-06`; step 2 (tests first, red) done 2026-11-16, round 0: F5 test as its own commit, core, data-rabbit and acceptance tests written (17 `@fake-broker` scenarios bound, the `@broker` scenario bound by name in `RealBrokerPlanCheckTest`, not run: no container runtime answered), red on the missing production code; step 2.5 (architect) done 2026-11-16: API confirmed, the vhost scenario now uses `orders`; step 3 (build) done 2026-11-16, round 1: F5 fix, pure dry-run extraction, port, `PlanCheck`, `ManagementDeclareCheck` and the guarded source check in 5 commits; core, data-rabbit and the 16 + 8 outline-example `@m9-05` scenarios green; `RealBrokerPlanCheckTest` NOT run (Docker daemon down, no Podman); one test defect found (`PlanCheckTest` cancelled-check test returns a Boolean, so `verifyTestDiscovery` fails the full build; test developer to fix); step 3 (build) done 2026-11-16; step 4 (test developer verifies) done 2026-11-16, round 1: pass, no blocker or major (full suite green, 16 scenarios and the outline pass, `@broker` live test not run: no container daemon); step 5 (architect verdict) done 2026-11-16, round 1: ACCEPTED WITH FOLLOW-UPS (no blocker or major; notes in `M9-06` and `M9-05b`; Owner review list now 10 items; `@broker` scenario "The check agrees with a real broker before and after applying" NOT RUN, so the task is not done for it and the owner decides on a merge with it open); branch `m9-05-check-core-and-data` (head 3cf04b0 plus docs, not merged); DONE on branch `m9-05-check-core-and-data` (round 1, Accepted with follow-ups 2026-11-16; the @broker scenario NOT RUN; owner decides merge)

## M9-05b-check-argument-rule

REWRITTEN (architect, M9-00c step 3, 2026-11-16); 2 `@m9-05b` scenarios in `20` (@broker); depends on M9-05 (done) and M9-00c (done); not on the path of M9-06; step 2 (tests first, red) done 2026-11-16, round 0: fake made honest, unit tests for the rule and every table row, the two `@broker` scenarios bound in `RealBrokerPlanCheckTest`, all green against a throwaway implementation on RabbitMQ 4.4.0; open for the architect: the scenario row with `x-max-priority` on a quorum queue cannot be set up on a real broker, and a whole number against a decimal needs a rule beyond the brief; step 2.5 (architect) done 2026-11-16: rule 4 and the other decisions; merged main (M9-14); step 3 (build) done 2026-11-16, round 1: `HonouredArgumentRule` and the list answer in `ManagementDeclareCheck`, fast suites green, `RealBrokerPlanCheckTest` 5 of 5 pass on RabbitMQ 4.4.0 (STOP check not triggered); step 4 (test developer verifies) done 2026-11-16, round 1: PASS, one minor (list nested deeper than 64 levels); step 5 (architect verdict) done 2026-11-16, round 1: ACCEPTED WITH FOLLOW-UPS (no blocker or major; notes in the brief; owner review list none new); branch `m9-05b-check-argument-rule`, merged 2026-11-16, DONE (round 1, ACCEPTED WITH FOLLOW-UPS)

## M9-06-check-on-screen

briefed (architect, 2026-11-15); 5 `@m9-06` scenarios in `20`; depends on M9-05, M9-03; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-06-check-on-screen`: brief changed (view model gets `declareCheck` and `clock`, token and stale rules by step id, disabled-reason order, caveat line, F4 1400 dp and node subtitle decided by the owner and folded in); no open product question; step 2 (tests first, red) done 2026-11-16, round 0: core, ui, desktopApp and acceptance tests written, the 5 `@m9-06` scenarios bound, API shape confirmed; step 3 done; step 4 (verify) round 1 done 2026-11-16: green, no blocker or major, one minor, `@broker` still not run; step 2.5 done (API confirmed); step 3 (build) done 2026-11-16, round 1: 4 commits (1400 dp breakpoint and node subtitle, view model check, screen, wiring), all tests green; `@broker` M9-05 test still not run; step 5 (architect verdict) done 2026-11-16, round 1: ACCEPTED WITH FOLLOW-UPS (no blocker or major; F1 banner height at 1280 as a note in `M9-07`; Owner review list now 11 items, summary order stays the spec table order; `RealBrokerPlanCheckTest` NOT RUN, so the live agreement with a real broker is unverified and the owner decides on a merge with it open); branch `m9-06-check-on-screen`, not merged; DONE on branch `m9-06-check-on-screen` (round 1, Accepted with follow-ups 2026-11-16; M9-05 @broker test still NOT RUN; owner review list open; not merged, owner decides)

## M9-07-stream-step-and-response-stream

briefed (architect, 2026-11-15); 12 `@m9-07` scenarios in `21`; depends on M9-03, M9-04 (no longer on M9-00b: the S-D sentence is cut and the note is worded neutrally, 2026-11-16); Q2, Q6 decided; the MQTT ticks moved to `M9-07b`; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-07-stream-step-and-response-stream`: brief changed (spike cut, M9-06 F1 and layout items folded in, one scenario reworded and untagged `@needs-spike`), no open product question; step 2 (tests first, red) done 2026-11-16, round 0: core, ui and acceptance tests written, the 12 `@m9-07` scenarios bound (filter widened), red on the missing production code; four ui tests (F1, gap, step list scroll, indent) fail on existing code by design; step 2.5 (architect) done 2026-11-16, round 0: API shape confirmed as the tests have it, no test or glue changes, one feature line confirmed, stand-ins for M9-12 recorded in its brief, Owner review list of 11 wording items in the brief; step 3 (build) done 2026-11-16, round 1: 4 commits (core, ui, stacked layout, indent), all tests green, no `@Disabled` needed; round 2 (card floor 300 dp, steps floor 224 dp, A1 green); round 3 (card floor 360 dp for the graph box floor of 140 dp, PlanGraphBoxUiTest green); round 4 (owner option A: scrollable stacked page, measured card floors, tighter card, PlanGraphBoxHardCasesUiTest green); no broker call, executor change, dependency or MCP tool; step 4 (test developer verifies) done 2026-11-16, round 1: pass with findings (one major for the architect: the graph box is about 50 px high with 7 steps after a check at 1600 x 1000; two minor), full suite green, all 12 `@m9-07` scenarios ran and passed, `@broker` M9-05 test still NOT RUN (Docker daemon down); step 4 round 2 (2026-11-16): the card floor passes A1 to A6 and the suite, but the graph box of the seven-result plan is 132 dp at 1280 x 800 and 1440 x 900 (72 dp with the notice, 92 dp at 1600 x 1000), below the 140 dp floor: finding F4, two RED tests in `PlanGraphBoxUiTest`, architect decides; step 4 round 3 (2026-11-16): 360 dp floor passes all agreed tests and the suite, but the graph box is still under 140 dp at 1366 x 768 (112 dp), with a long connection name (88 dp at 1280 x 800), with a 7-character connection name that wraps the header (96 dp), and with the notice: finding F5, four RED tests in `PlanGraphBoxHardCasesUiTest`, minor, architect decides; STOPPED after 3 rounds on branch `m9-07-stream-step-and-response-stream`: 4 red graph-box tests, owner decision needed (2026-11-16); step 4 round 4 (2026-11-16): the scrollable stacked page keeps the graph box at 140 dp or more in every measured window down to 1366 x 700 and 1024 x 768 (page scrollbar when it scrolls), the four hard-case tests are green, full suite green, Topology screenshots byte-identical to main, no finding above minor; `@broker` M9-05 test still NOT RUN; step 5 (architect verdict) done 2026-11-16, build round 4: ACCEPTED WITH FOLLOW-UPS (no blocker or major; card floors accepted as measured, unused `GRAPH_BOX_MIN` a small clean-up, nested-scroll feel and focus order not verified: owner hand trial; F2 stays a note for M9-08); not merged, owner decides; DONE (round 4, Accepted with follow-ups 2026-11-16; owner review list open; M9-05 @broker test still NOT RUN; merged on the owner's word)

## M9-07b-stream-pair-mqtt-routes

NEW, briefed (architect, 2026-11-15); 12 `@m9-07b` scenarios in `21` (1 `@broker`); the owner confirmed the revised Q3 on 2026-11-15 (O14), so nothing waits for him; depends on M9-07, M9-10, M9-00a (S-A5 to S-A8), M9-12 (one scenario)

## M9-08-policy-step

briefed (architect, 2026-11-15); 11 `@m9-08` scenarios in `21`; depends on M9-00c, M9-03, M9-05, M9-07; Q4 decided (the retention block waits for S-F1)

## M9-08b-stream-retention-from-policy

NEW, briefed (architect, M9-00c step 3, 2026-11-16); 3 `@m9-08b` scenarios (`03`, `14`; 1 `@broker`); depends on M9-00c; before the retention block of M9-08

## M9-09-dead-lettering-recipe

briefed (architect, 2026-11-15); 12 `@m9-09` scenarios in `21`; depends on M9-00b, M9-00c, M9-08, M9-04; Q12 decided (the stream target and at-least-once wait for S-C)

## M9-10-mqtt-route-step

briefed (architect, 2026-11-15), extended 2026-11-15 with the sample-value test message (A15, wanted by the owner, O14); 17 `@m9-10` scenarios in `21` (1 `@broker`); depends on M9-00a, M9-03, M9-04; the largest of M9, the sample-value dialog can be cut out as `M9-10b` if it runs over; Q17 decided (`correlation-id`, user property only); the correlation-id sentence is a visible WARNING that the service must read and write the application property (O16, 2026-11-17; scenario renamed, a queue variant waits for the owner)

## M9-10c-mqtt-exchange-hint

PROPOSED (architect, 2026-11-16); waits for the owner's answer (M9-00a review list item 9); not scheduled

## M9-11-headers-bindings

briefed (architect, 2026-11-15); 8 `@m9-11` scenarios in `21` (after M9-00a; wording decided 2026-11-16); depends on M9-00a, M9-03; Q1 decided (headers exchanges are in)

## M9-12-open-pending-file-as-steps

briefed (architect, 2026-11-15); 12 `@m9-12` scenarios in `19`; depends on M9-03; Q16 decided

## M9-13-gate-planner-on-a-real-broker

briefed (architect, 2026-11-15); 1 scenario in `21`; depends on M9-01 to M9-10

## M9-14-export-keeps-arguments-and-policies

briefed (architect, 2026-11-15); found while planning; ORDERED by the owner on 2026-11-15 (O14): starts right after M9-03, depends on no planner task, begins with its own small live spike; outside the planner's first version and not part of the gate; team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-14-export-keeps-arguments-and-policies`: brief changed (spike items E1 to E7 with class, results file and fixtures; exports must leave out exclusive and MQTT subscription queues; file order; round trip as the live gate test); no open product question; step 2 (tests first) done 2026-11-16, round 0: spike run (E0 to E6, E7a green; E7 gate red on purpose), unit tests, glue and 7 new scenarios written and red (`core-testing` does not compile until the production types exist); step 2.5 done 2026-11-16: API shape confirmed, streams and operator policies in scope (owner review list in the brief), no test change; step 3 (build) done 2026-11-16, round 2 (F1: screen reads tolerant, only the export fails on an untypable argument), round 1: reads, exporter and note built, E7 and the M5 gate green live; two test-side gaps for the test developer (see the brief's Status); step 5 (architect review) done 2026-11-16, round 2: ACCEPTED WITH FOLLOW-UPS, no blocker or major open; polish in `M9-14b-export-polish` (F2 partition noise, F3 failure text, F4 over-strict check; owner wording items 3 to 6); merge needs the owner's word, two docs conflicts (README files)

## M9-14b-export-polish

briefed (architect, 2026-11-16); owner answered items 1 to 7 of the M9-14 list the same day; step 2 done, step 3 built, step 4 round 1 verified by the test developer: pass, no open finding (see the brief); team loop step 1 (Brief) done 2026-11-16, round 0, branch `m9-14b-export-polish`: wording constants decided, F3 also touches two `data-rabbit` parsers (tolerant like exchanges), F5 out, 7 `@m9-14b` scenarios written in `10`, no ui change; step 3 (build) done 2026-11-16, round 1: production code written, all fast suites and the three live classes green; step 4 round 1 pass; step 5 (architect review) done 2026-11-16, round 1: ACCEPTED WITH FOLLOW-UPS, no blocker or major open; merged 2026-11-16, DONE (round 1, accepted with follow-ups)

## M9-07c-response-stream-sentence

briefed (architect, 2026-11-17, round 0, branch `brief-owner-answers-m9`); owner decision O16 ("add it"); basis S-D1 to S-D3; 1 scenario in `21` with `@needs-M9-07c` (no runner selects it before step 2); step 3 built (senior developer, 2026-11-17, round 1, branch `m9-07c-response-stream-sentence`); step 5 (architect, 2026-11-17, round 1): Accepted, no findings; DONE

## M9-14c-stream-arguments

briefed (architect, 2026-11-17, round 0, branch `brief-owner-answers-m9`); owner decision O16 ("do it"); live rows first (S-14c-1 to S-14c-5); 10 scenarios in `10` (1 `@broker`), 1 outline in `20`, 1 in `21`, all `@needs-M9-14c` with no runner-selecting tag until step 2; Q-14c-1 answered as the owner's working default (write `balanced` always); team loop step 1 (Brief) done 2026-11-17, round 0, branch `m9-14c-stream-arguments`: brief changed (executor reads a stream with settings first, one `StreamSettingRules` for parser and export, fake broker and equivalence table pinned to measured rows, six live rows S-14c-1 to S-14c-6 with procedure, order step 2a live rows then 2b the rest, `M9-14d` cut rule, exact list of old tests and scenarios to change), Q-14c-2 (wording) proposed, not blocking; step 3 (build) done, round 1, 2026-11-17: items 1 to 5 all built, no cut to `M9-14d`, whole `./gradlew test` and the live classes green; step 4 verify PASS (round 1); step 5 review ACCEPTED WITH FOLLOW-UPS (2026-11-17, follow-up `M9-14c-f1` XS); DONE, not yet merged; M9-14d not needed

## M1-13-unreadable-connections-file

briefed (architect, 2026-11-09); size M; owner decided 2026-11-09: option B (message with path and reason, Try again, Show folder, and an explicit "Start with an empty list" that renames the file to `connections.json.unreadable-<timestamp>` after a confirmation, never deletes); scenarios exact in the brief; step 1b (architect): feature scenarios, UX state in `docs/ux/Main.dc.html` (new, not drawn yet), spec line; depends on M5-07 (merged)

## M5-08-real-migration-execution

briefed (architect, team loop step 1 done 2026-11-09, round 0; branch `m5-08-real-migration-execution`); no open product decision; order a, b, c, then M5-09; run before the apply half of `M8-05`, `M8-12` and the `M8-11` gate

## M5-08a-migration-executor

briefed (architect, 2026-11-09); team loop step 2 done (test developer, round 0); step 3 round 1 built (F1 to F4, 2026-11-10; live test 14/14; 6 old data-rabbit assertions need the test developer, see the task Status); earlier: step 3 round 0 built (senior developer, 2026-11-09): all unit tests green, 13 of 14 acceptance scenarios green, 1 blocked by a glue defect in `M508aExecutorSteps` (see the task Status), live test compiled not run; next: step 2 fix of the glue, then step 4 (verify) and 5 (review); size M; depends on M8-04, M5-07 (merged); round 1 done (conflict by facts, binding delete by the broker's key, fail closed on 3.x, transport catch, 35-row measured status table); verified (live 14 of 14 on 4.x, 15 mutations caught); architect verdict ACCEPTED WITH FOLLOW-UPS (step 5 round 1, 2026-11-10); built and verified, branch not merged, waiting for the owner's word; next `M5-08b`

## M5-08b-migration-persistence-and-folder

briefed (architect, 2026-11-09); team loop step 2 done (test developer, 2026-11-10, round 0); step 3 round 0 built (senior developer, 2026-11-10): stubs replaced, unit and acceptance tests green, `verifyTestDiscovery` was red on 7 test files, reshaped by the test developer; step 4 verified round 0 (test developer, 2026-11-10): PASS WITH MINOR FINDINGS, full set and `verifyTestDiscovery` green, mutations a to g caught; round 1 (history-first rename and delete, `NotRecorded`, device names, default permissions) built and re-verified (test developer, 2026-11-10): PASS, full set and `verifyTestDiscovery` green; next: step 5 round 1 (architect); size M; depends on M5-08a; round 1 done (history operations first, `NotRecorded`, Windows device names, migration files with default permissions); verified (full set green, 4 + 5 mutations caught, one own mutation added a test); architect verdict ACCEPTED WITH FOLLOW-UPS (step 5 round 1, 2026-11-10); built and verified, branch not merged, waiting for the owner's word; next `M5-08c`

## M5-08c-migrations-wiring-and-ux

owner reviewed and decided 2026-11-10; split into `M5-08c1` and `M5-08c2`; depends on M5-08a, M5-08b (merged)

## M5-08c1-migrations-wiring

briefed (architect, 2026-11-10); size M; 5 `@m5-08c1` scenarios; team loop step 2 done (test developer, 2026-11-10, round 0): tests and glue written, red for the right reason (core 27, data-rabbit 5, desktopApp 22, acceptance 5), compile stubs marked `M5-08c1 STUB`; step 3 (senior developer) round 0 done 2026-11-10: stubs replaced, all green except one desktopApp test whose setup has no executor (see the Status block); step 4 verified round 0 (test developer, 2026-11-10): PASS WITH MINOR FINDINGS, test fixed, full set and `verifyTestDiscovery` green, mutations a to i plus 4 own caught, real 4.x end to end and 3.x GET-only checked, java.desktop in the runtime; manual DMG check left to the owner; next: step 5 (architect); depends on M5-08a, M5-08b (merged); built (93deab4) and verified (13f1390, all suites green, 11 mutations caught, real end to end on 4.x); architect verdict ACCEPTED WITH FOLLOW-UPS (step 5, 2026-11-10), not pushed or merged; follow-ups folded into `M5-08c2`; owner manual DMG checklist in the Status block

## M5-08c2-migrations-screen-states

briefed (architect, 2026-11-10); size M; 5 `@m5-08c2` scenarios plus the c1 ones re-bound to the Compose screen; team loop step 2 done (test developer, 2026-11-10, round 0): `MigrationsStatesUiTest`, `ConnectionsScreenHistoryBannerTest`, `MigrationsViewModelFollowUpTest`, ten scenarios bound to the Compose screens, red by assertion (ui 32, core 17, acceptance 9 scenarios); step 3 (senior developer) round 0 done 2026-11-10: screen, wording, Connections banner, follow-ups built; ui, data-rabbit, desktopApp, mcp-server, acceptance (feature 10: 53 scenarios, 0 failed) green; core 1632 of 1633 green, ONE test of the step 2 contract cannot pass (shared store in `MigrationsViewModelOutcomeTest`, see the Status block); step 4 verified round 0 (test developer, 2026-11-10): FAIL WITH ONE MAJOR (`Conflict.resource` not filtered by `echoableName`, new red test), test fixed, full set green otherwise and `verifyTestDiscovery` green, mutations d, e, j, k, l and 4 own caught (2 survivors: CAS vs cheap check), real 4.x end to end ok; step 3 round 1 built (echo rule, banner text); step 4 round 1 (test developer, 2026-11-10): PASS, full set and `verifyTestDiscovery` green in one run, echo mutation caught, hostile names checked end to end; follow-ups: dispose hook for the `store.changes` collector and the double reload; manual DMG and mockup check left to the owner; next: step 5 (architect); depends on M5-08c1; built (b11cca9, 704d540) and verified (70e31c0, 643c5c0, all suites green, mutations caught, no regression end to end on 4.x); architect verdict ACCEPTED WITH FOLLOW-UPS (step 5, 2026-11-10), not pushed or merged (same branch as M5-08c1); follow-up: dispose the folder collector of a replaced view model; owner checklist in the Status block

## M5-09-mcp-migration-readonly-tools

briefed (architect, 2026-11-09); size S; depends on M5-08b

## M5-07-nesting-guard-for-untrusted-json

brief exact and scenarios written (architect, team loop step 1 done 2026-11-09, round 0; branch `m5-07-nesting-guard-for-untrusted-json`); size S; no product decision; tests first written, red for the right reason (test developer, step 2 done 2026-11-09, round 0; probe #10 overflows, so GUARD); build done, tests green (senior developer, step 3 done 2026-11-09, round 0); test developer verification done (step 4, round 0): PASS, six mutations caught, audit complete, one minor note, no open finding; architect verdict ACCEPTED WITH FOLLOW-UPS (step 5, 2026-11-09), BUILT AND VERIFIED, ACCEPTED WITH FOLLOW-UPS, branch not merged, waiting for the owner's word; follow-up (A) is brief `M1-13`: unreadable `connections.json` at start-up; run before M8-02 and M8-03; depends on M8-01 (merged)

## M5-verify-host-name

planned (hardening); brief not written

