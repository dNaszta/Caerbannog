# Late CA validation result must not land on the wrong profile (M2-14 follow-ups F1, F2, R6)

Milestone: M2    Depends on: M2-14a (`validateCaCertificateOffThread`, `panelEpoch`)    Size: S (about half a day to one day)

## Goal
A CA validation answer that arrives after its profile was renamed or deleted is dropped (no exception, nothing saved, no message), a rename invalidates running validations like a panel switch does, `ConnectionsViewModel` says in its KDoc that it is confined to the UI thread, and the form's "which CA path do I send" rule lives in a testable form state holder instead of inside `EditPanel`.

## Why
Architect review of M2-14a (`docs/tasks/M2-14-plain-connections.md`, step 5, follow-ups F1, F2, R6) and the owner's request "F1/F2/R6". Today a Save with a rename while a focus-loss validation runs lets the late result call `applyCaCertificate(oldName, ...)`; `profileNamed` throws `IllegalStateException` inside `panelScope` (uncaught in a UI coroutine). The window is milliseconds and no data is lost, but a result for the wrong environment is what the rule "each profile is its own environment" exists to prevent.

## Scope
In:
- F1: KDoc on `ConnectionsViewModel`: all methods are called on the UI thread only; `applyCaCertificate`, `updateConnection` and `panelEpoch` rely on it (read-then-write without a lock). State that the suspend methods return to the caller's context after `withContext`.
- F2, core (`ConnectionsViewModel`):
  - `validateCaCertificateOffThread(name, ...)`: when `name` no longer exists in `repository.list()` after the read returns, return null (dropped) before touching state. Never call `profileNamed` after a suspension point.
  - `updateConnection` with a rename (`input.name != originalName`) and a `Saved` result bumps `panelEpoch`; `confirmDelete` bumps it too when it deletes the selected profile (today the screen clears the selection, but the view model must not depend on that).
  - `setCaCertificate` (synchronous, BDD and programmatic) stays as is; it throws for an unknown name by design and is not an async path.
- R6, ui: a `ConnectionFormState` holder for the `EditPanel` fields (name, host, virtual host, username, password, CA path, tls, ports, level, declaration, `caEdited`, `caHadFocus`, `caValidatedPath`). The "send the CA path or null" rule (`sendCa`, `ConnectionsScreen.kt` line 358: a new profile always sends; a saved profile sends only when changed and not just validated) moves out of the composable into one pure function, unit tested without Compose. `EditPanel` only reads and writes the holder and calls its `toInput()`.

Out:
- Re-running the validation for the new name after a rename (decision D-1).
- Any change to Connect/Check texts, the gateway or TLS switch behaviour (that is M2-14c).
- A view model owned `CoroutineScope` (decision D-2).
- Other screens' state holders.

## Acceptance
Tests first, red for the right reason (today: `IllegalStateException` from `profileNamed`, and no epoch bump on rename).
- BDD: `tests/bdd/features/01_connections.feature` / "A late CA certificate answer for a profile that was renamed or deleted is dropped" (outline, examples: renamed, deleted; tags @negative @fake-broker; added in this step, steps still to write). New small steps for `acceptance-tests/.../connections/ConnectionsSteps.kt`: `the check of a valid CA certificate file for {string} is still running` (starts `validateCaCertificateOffThread` with a gated validator), `the connection {string} is renamed to {string} before the check finishes`, `the connection {string} is deleted before the check finishes`, `the check finishes`, `the late answer is dropped without an error` (result is null, job ended normally), `no CA certificate message is shown` (`lastCaCertificateValidationResult` is null), `no saved connection has a CA certificate path`.
- Unit (core, `ConnectionsViewModelTest`, one behaviour per test, no real IO, no sleeps):
  - late result for a deleted profile returns null, does not throw, state unchanged;
  - late result for a renamed profile returns null, the profile under the new name has no CA path;
  - a rename bumps the epoch: a validation started before the rename and finished after it returns null even when the old name is added again afterwards (proves the epoch, not only the existence check);
  - a same-name update does NOT drop a running validation (the existing UI test "Save during a running focus-loss validation loses neither path nor other edits" stays green);
  - `confirmDelete` of the selected profile bumps the epoch;
  - regression: a panel switch still drops the result; a result for `name == null` still applies.
- Unit (ui, new `ConnectionFormStateTest`, no Compose): the CA send rule as a table: new profile with typed path sends it; saved profile unchanged sends null; changed and not validated sends the path; changed and equal to `caValidatedPath` sends null; blank typed on a saved profile that had a path sends blank (clear); TLS off still sends the typed path (O3: kept, unused). The holder resets when the selected profile changes.
- Existing Compose tests in `ConnectionsScreenUiTest` and `ConnectionsScreenTlsUiTest` stay green unchanged; if one needs editing, the refactor changed behaviour.
- Rule checks: no plain path or TLS default touched; the password stays in memory as typed text only, as today; nothing logged.

## Design notes
- Files: `core/.../connections/ConnectionsViewModel.kt` (F1, F2); `ui/.../connections/ConnectionsScreen.kt` (`EditPanel` fields at lines 160 to 194, `sendCa` line 358, `panelScope` line 179); new `ui/.../connections/ConnectionFormState.kt`; `ConnectionProfileInput.kt` is not expected to change (keep the rule next to the holder that knows `caValidatedPath`). Tests: `core/src/jvmTest/.../ConnectionsViewModelTest.kt`, new `ui/src/jvmTest/.../ConnectionFormStateTest.kt`, `acceptance-tests/.../ConnectionsSteps.kt`, and a gated validator in `core-testing` (a `CaCertificateValidator` fake whose `validate` waits until the step releases it, or a suspending test dispatcher; pick the one that keeps the step small and needs no sleep).
- Async-effect rule ("all public view model methods hop onto their scope"): this view model owns no scope on purpose (the `close()` comment: no scope that someone must close). It is met like this: the only async method, `validateCaCertificateOffThread`, hops to the given dispatcher for the file IO and returns to the caller's context for every state read and write, so those are on the UI thread. Do not add a scope in this task; if the owner means a view model owned scope for every public method, that is D-2.
- Drop rule in one place: a small private `isStillCurrent(panel, name)` (epoch equal and, for a name, the profile still exists) used by `validateCaCertificateOffThread`, not two scattered checks. The return type stays `CaCertificateValidationResult?` (null = dropped); callers already handle null.
- Holder shape: a plain class `ConnectionFormState(selected: ConnectionProfile?)` with Compose `mutableStateOf` properties, created with `remember(selected?.name)` as the fields are today. Pure functions (the send rule, `toInput`) take plain values so they test without a composition. It is not a second view model and holds no repository or broker calls.
- The epoch is an `Int` touched on the UI thread only; the KDoc says so. No atomics.
- Faked: validator and repository use the existing fakes; no real broker, no files.

## UX
No visual change. `docs/ux/Main.dc.html` Connections edit panel: the CA validation message shows only for the profile on screen; a dropped answer shows nothing (no banner, no flicker).

## Open points
- Overlap with the in-flight M2-14c (Connect hint "may not use TLS", branch `m2-14c-connect-hint-tls`). Its brief names: `ConnectionFailureReason` and the shared Check text constant in core, `RabbitMqConnectionGateway`, `ManagementHttp` and `ManagementReply` in data-rabbit, `FakeConnectionGateway` in `core-testing`, `RealBrokerPlainTest`, `RealBrokerCatalogTest`, `ConnectionCheckTextsTest`, and `13_connection_lifecycle.feature` (plus likely `ConnectionLifecycleSteps.kt`). This task touches `ConnectionsViewModel.kt`, `ConnectionsScreen.kt`, `ConnectionFormState.kt` (new), `ConnectionsViewModelTest.kt`, `ConnectionsSteps.kt`, `01_connections.feature` and `docs/tasks/README.md`. Possible conflicts:
  1. `core-testing`: put the gated validator in a NEW file (`GatedCaCertificateValidator.kt`), do not edit `FakeConnectionGateway.kt`.
  2. Cucumber step text: grep `ConnectionLifecycleSteps.kt` and the M2-14c step list before writing steps, an identical phrase in both files is an ambiguous step. The phrases above are specific to the CA check.
  3. `ConnectionsViewModel.kt`: M2-14c should not need it (`connect`, `checkConnection` are not changed here either); if it does, the merge is textual only.
  4. `docs/tasks/README.md`: adjacent rows, trivial merge.
  Merge order is free; rebase this branch on whichever lands first.
- Verified in code (not assumed): `panelEpoch` is bumped only in `selectConnection` and `startNewConnection`; `confirmDelete` clears `selectedProfileName` but does not bump.

## Decisions for the product owner
- D-1 (the CA path typed before a rename, while it was being validated): (1) drop the answer; Save already sent null = keep, so the path is not stored and the user leaves the field again (recommended: smallest, no hidden work, window is milliseconds); (2) re-run the validation for the new name automatically (a second async path, hard to test, little gain). Nothing is blocked; default is (1).
- D-2 (only if the "async-effect rule" means a view model owned scope): (1) keep the confinement rule plus KDoc, the caller passes the dispatcher (recommended, matches M2-14 as accepted); (2) give the view model a scope and make every public method launch on it (changes the synchronous BDD steps and the `state.value` guarantee; a separate task). Blocks nothing here.

## Definition of done
Tests written first and passing (core cases, ui holder table, the new scenario outline with steps), KDoc present, `EditPanel` shorter and free of the send rule, no unrelated changes, README row updated, follow-up rows F1, F2, R6 in `M2-14-plain-connections.md` marked done.

## Status
- Reconciled 2026-11-17: this task is merged into `main` (3f4ada4, 2026-11-05); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.
Step 1 (architect, 2026-11-05): brief written; scenario outline "A late CA certificate answer for a profile that was renamed or deleted is dropped" added to `01_connections.feature` (steps not yet written, so it is pending until step 2); no app code. Next: step 2 test developer writes unit tests and steps (red), step 3 senior developer builds, step 4 verify, step 5 architect review.

Step 2 (test developer, 2026-11-05): tests first. core `ConnectionsViewModelTest` +9 (4 red for the right reason, 5 guards green), `GatedCaCertificateValidator` in `core-testing`, `01` outline steps (2 examples red: `IllegalStateException: No saved connection named 'uat-eu'`), ui `ConnectionFormStateTest` (red by compile). No app code. Owner decisions D-1 and D-2 = recommended. Next: step 3 senior developer builds.

Step 3 (senior developer, 2026-11-05): built. `ConnectionsViewModel`: UI-thread KDoc, private `isStillCurrent(panel, name)` (epoch equal and, for a name, the profile still in `repository.list()`), epoch bump on a successful rename and when `confirmDelete` deletes the selected profile. New `ConnectionFormState`; `EditPanel` reads and writes it and calls `toInput()`. Green: core `ConnectionsViewModelTest` 32, ui connections 141, acceptance `RunConnectionsAcceptanceTest` (166 run in the module, 10 skipped as before, 0 failed), desktopApp 58. Not run: brokerTest, screenshots, other modules. Next: step 4 verify, step 5 review.

Step 4 (test developer, 2026-11-05): verified on 29493e2, PASS. Core 616, ui 308, acceptance 166 (10 skipped as before), desktopApp 58, verifyTestDiscovery ok; 5 of 5 mutations killed (one after adding a test). Next: step 5 architect review.

Step 5 (architect review, 2026-11-05, reviewed `git diff main...m2-14d-ca-late-result`, head deaf339): ACCEPTED WITH FOLLOW-UPS. Id renamed from M2-14d to M2-14e (M2-14d is `M2-14d-ipv6-management-host.md` on `m2-14c-connect-hint-tls`). D-1 and D-2 hold as built. Not run by anyone: `@broker`, GUI, screenshots, full suite.

| # | Severity | File:line | Finding | Suggested fix |
| --- | --- | --- | --- | --- |
| 1 | minor | `ConnectionFormState.kt` `caPathToSend` (captures `selected` at creation) | The old `EditPanel` compared against the latest `state.selectedProfile`; the holder keeps the profile it was created with (the `remember` key is the name only). After a same-name Save that stored a new CA path, a second Save without touching the field sees "typed differs from the old stored path and from caValidatedPath" and sends the path again: the file is read twice and, if it vanished meanwhile, an Invalid message shows (the stored path is kept, nothing is lost). No test covers it. | Test first in `ConnectionFormStateTest`: after the profile's stored path equals the typed one, the form sends null. Then give `toInput` the current saved profile (or compare against it); the rest stays. |
| 2 | idea | `ConnectionsViewModel.kt` `isStillCurrent` | Correct: `repository.list()` is the only state read after the suspension, `profileNamed` is reached only after the check. A future second async path must reuse the same check. | None. |
| 3 | minor | test, KDoc and feature comments | The old id M2-14d is still written there. | Developer renames to M2-14e. |

Checked and fine: `isStillCurrent` (same epoch and, for a name, the profile exists; `name == null` skips the existence check); epoch bumps only on a Saved rename and when `confirmDelete` removes the selected profile (failed rename, same-name update and deleting another profile do not bump, all tested); the rename-and-add-again test proves the epoch apart from the existence check; KDoc states the UI-thread confinement and the return to the caller's context; no scope added (D-2); defaults, password (blank is null, memory only), remember key and the CA send rule for new profile, TLS off and clear equal the old `EditPanel`; no logging, no plain path or TLS default touched; `ConnectionsScreenUiTest` and `ConnectionsScreenTlsUiTest` are not in the diff; the scenario outline uses a gated validator, no sleeps.

Follow-up: finding 1 (S, tests first, before merge if the owner wants the refactor strictly behaviour neutral, else with the next task touching `ConnectionsScreen.kt`). The branch is stacked on the M2-14 work, so the merge proposal waits for the owner's A1 hand trial.

Finding 1 fix (senior developer): `toInput(saved)` compares the typed CA path with the current saved profile (`state.selectedProfile` at Save time); the holder's `selected` only seeds initial values. Tests green.
