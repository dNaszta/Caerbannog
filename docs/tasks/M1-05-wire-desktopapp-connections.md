# Wire desktopApp to the real Connections screen

Milestone: M1    Depends on: M1-02-connection-profiles (accepted), M1-04-connections-screen-feedback-gaps (accepted; branch base)    Size: S

## Goal

Running `./gradlew :desktopApp:run` opens the real Connections screen, backed by the real
`JsonFileConnectionProfileRepository`, `JavaKeyringSecretStore`, `StreamAndManagementReachabilityChecker`
and `X509CaCertificateValidator` — not `M1-01`'s placeholder `App()` text.

## Why

`M1-02-connection-profiles`'s own Goal promised "a real Connections screen", but its Scope named only
"The Compose Connections screen in `ui`" — deliberately leaving `desktopApp` wiring out, since that would
need `desktopApp` to depend on `data-rabbit`, a change not named in that task's Scope or Definition of
done. The senior developer flagged this at handover as a natural, narrow follow-up rather than doing it
unasked. `docs/spec.md`'s Milestones table gate for M1 is "Connects to a local broker" — the automated
proof already exists (`data-rabbit`'s `RealBrokerReachabilityTest`, plus the `01_connections.feature`
suite), but today a human running the actual shipped app still cannot reach it: they see the M1-01
placeholder. This task makes the gate demonstrable end-to-end, not only test-proven.

## Scope

**In:**

- `desktopApp/build.gradle.kts`: add `implementation(project(":data-rabbit"))` and
  `implementation(project(":core"))` next to `project(":ui")`. `core` is needed because `ui` declares it
  `implementation` (not `api`), so `ConnectionsViewModel` and the interfaces are not visible transitively.
- A composition-root function in `desktopApp` (jvmMain), e.g. `createConnectionsViewModel(profilesFile: File
  = JsonFileConnectionProfileRepository.defaultFile(), secretStore: SecretStore = JavaKeyringSecretStore())`,
  that builds `JsonFileConnectionProfileRepository(profilesFile)`, the secret store,
  `StreamAndManagementReachabilityChecker()` and `X509CaCertificateValidator()` and returns the
  `ConnectionsViewModel`. Defaults are the real implementations; the parameters exist so a test can
  inject a temp file and an in-memory store (see Acceptance).
- `Main.kt`: `main()` calls that function and renders `ConnectionsScreen(viewModel)` in the existing
  `Window { ... }` instead of `App()`.

**Out:**

- Any new screen, navigation, menu, or environment-variable/system-property override for the data path.
- Any change to `ConnectionsScreen`, `ConnectionsViewModel` or any `data-rabbit` class.
- The M1-04 follow-up notes (banner clearing on name edit, scrollbar, accessibility role); revisit after
  the first human run.
- `M1-01`'s `App()` stays in `ui` (still used by `AppUiTest`); `main()` simply stops calling it.
- `M1-03` (permission check) is unbriefed and is not a dependency.

## Acceptance

No new Gherkin scenario: composition-root wiring is not domain behaviour that Cucumber drives. The
`01_connections.feature` suite and `data-rabbit`'s `RealBrokerReachabilityTest` already prove the behaviour;
this task proves the real pieces are assembled.

Automated (required, safe: never touches the real `~/.caerbannog` or the real keychain):

- A `desktopApp` jvmTest (JUnit 5, same setup as `ui`/`data-rabbit`; no new library) that calls the
  composition function with a JUnit `@TempDir` file and an in-memory `SecretStore`, then checks the view
  model starts with an empty profile list, and that adding a profile writes the temp file and reads it
  back through a second call to the function (proves the real `JsonFileConnectionProfileRepository` is
  wired). Test must fail first (function does not exist).
- Rule: no test in this task may call the zero-argument/default form of the function, construct
  `JavaKeyringSecretStore`, or use `defaultFile()`. Review checks this.
- Existing suites unchanged and green: `core`, `ui`, `data-rabbit` (non-@broker), acceptance-tests
  (`not @needs-M2`).

Manual only (not automatable here; Compose `application {}` and the real keychain are not driven by CI):

- `./gradlew :desktopApp:run` opens a window with the sidebar and "New connection", not the M1-01
  placeholder text. Record in Status.
- Optional manual check against a local broker (Taskfile broker, TLS 5551/15671): save a profile, Check
  connection, restart the app, profile is still listed. This touches the real files and keychain (see
  Open points); do it with a throwaway profile name and delete the profile afterwards.

## Design notes

`desktopApp` becomes the first module depending on `ui`, `core` and `data-rabbit`; this matches
`docs/spec.md`'s Module layout table (`desktopApp` = `main()`, window, menus, packaging, i.e. the
composition root). `ui` still never imports `data-rabbit`. The only seam added is the function's
parameters; `Main.kt` stays a thin caller so the logic is testable without a window. The view model and
screen are created once in `application {}` via `remember` so recomposition does not rebuild them.
Everything in the automated test is real code except the secret store (in-memory) and the file location
(temp); no broker is contacted.

## UX

No new screen; this task only changes which screen `desktopApp` shows on startup.

## Open points

No product decision and no new dependency or build-tool choice is needed. Nothing blocks step 2.

- Manual runs read and write the real `~/.caerbannog/connections.json` (absent or empty on first run,
  harmless) and, if a password is saved, the real OS keychain under service `dev.caerbannog.connections`.
  Expected, not a surprise to debug later. Mitigation: throwaway profile name, delete it after.
  Option (not in scope, recommend deferring): a data-directory override (system property or env var) for
  isolated manual runs; add only if the human wants it.
- Windows keychain remains unverified (spec); this task does not change that.
- M1-04's minor notes may surface in the first human run; record them in Status, do not fix here.

## Definition of done

- Automated composition test written first, passing, and free of real file/keychain access.
- Manual run confirmed and recorded in the Status block.
- No unrelated changes.
- `docs/tasks/README.md` updated.

## Status

- Step: 2 done (test developer). `desktopApp/src/test/kotlin/dev/caerbannog/desktopapp/CompositionRootTest.kt`
  written, red (unresolved reference `createConnectionsViewModel`). Test calls it positionally as
  `createConnectionsViewModel(profilesFile: File, secretStore: SecretStore)`; returns `ConnectionsViewModel`.
  Test-side build change only: `desktopApp/build.gradle.kts` got `testImplementation` for `:core`, kotlin test,
  JUnit 5, launcher, and `tasks.test { useJUnitPlatform() }`. Next: step 3, senior developer adds the function,
  `implementation(project(":core"))` and `(":data-rabbit")`, and `Main.kt` wiring.
- Step: 3 done (senior developer). `createConnectionsViewModel` in `desktopApp/.../CompositionRoot.kt`;
  `Main.kt` renders `ConnectionsScreen` via `remember`; `desktopApp` now has `implementation` of `:core` and
  `:data-rabbit`. `./gradlew test`: desktopApp 3/3, core 6, ui 6, data-rabbit 10 (non-@broker), acceptance-tests
  17 run, 0 failed, 1 skipped. Manual `:desktopApp:run` NOT done (touches real ~/.caerbannog and keychain; human step).
- Step: 4 done (test developer, verify): PASS, no blocker or major. `./gradlew test --rerun-tasks` green:
  desktopApp 3/3, core 6 (SpikeSanity 1, ConnectionsViewModel 5), ui 6 (ConnectionsScreenUi 5, AppUi 1), data-rabbit 10
  non-broker, acceptance-tests 17 run, 0 failed, 1 skipped = "Switch between connections" (`@needs-M2`, by design).
  `@broker` (`:data-rabbit:brokerTest`) ran but both tests were skipped by `disabledWithoutDocker` (Testcontainers
  could not reach Docker here), so not proven this round (unchanged by M1-05). Mutation checks on
  `createConnectionsViewModel`: ignore profilesFile -> 2 tests fail; ignore secretStore -> password test fails;
  repository on a different temp file -> all 3 fail; tree restored clean. Review by reading: no M1-05 test or build
  step uses `defaultFile()`, the default parameters or `~/.caerbannog`. Not done: `:desktopApp:run` (human step).
  Minor: reachability checker and CA validator wiring are not asserted by any test (swapping them would stay green);
  accepted as out of the brief's stated test. Note: pre-existing M1-02 `KeyringSecretStoreTest` uses the real
  `JavaKeyringSecretStore` with a throwaway profile name (touches the real keychain on every `test` run), outside
  this brief's rule but worth the architect's attention.
- Round: 1
- Branch: `m1-05-wire-desktopapp-connections`
- Base branch: `m1-04-connections-screen-feedback-gaps` (accepted, not merged), so the first human run of
  the real screen has both M1-04 fixes. Rebase onto `main` once M1-04 is merged.
