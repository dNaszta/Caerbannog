# Wire desktopApp to the real Connections screen

Milestone: M1    Depends on: M1-02-connection-profiles (accepted/merged)    Size: S

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

- `desktopApp/build.gradle.kts`: add a dependency on `project(":data-rabbit")` alongside the existing
  `project(":ui")` dependency.
- `desktopApp/src/main/kotlin/dev/caerbannog/desktopapp/Main.kt`: build the composition root —
  construct `JsonFileConnectionProfileRepository(JsonFileConnectionProfileRepository.defaultFile())`,
  `JavaKeyringSecretStore()`, `StreamAndManagementReachabilityChecker()`, `X509CaCertificateValidator()`,
  wire them into a `ConnectionsViewModel`, and render `ConnectionsScreen(viewModel)` inside the existing
  `Window { ... }` instead of `App()`.

**Out:**

- Any new screen, navigation, or menu (there is only one screen so far; stream-list navigation is M2+).
- Any change to `ConnectionsScreen`, `ConnectionsViewModel`, or any `data-rabbit` class — composition-root
  wiring only.
- `M1-04-connections-screen-feedback-gaps.md`'s fixes (separate task; can land before or after this one).
- `M1-01`'s placeholder `App()` composable itself is left in `ui` untouched (still used by its own
  `AppUiTest`); `desktopApp` simply stops calling it from `main()`.

## Acceptance

- No new Gherkin scenario: wiring `main()`'s composition root is not user-visible domain behavior
  Cucumber drives.
- Manual smoke check, recorded in this brief's Status block (same pattern as `M1-01`'s own manual-run
  proof): `./gradlew :desktopApp:run` opens a window showing "New connection" / the sidebar, not the
  M1-01 placeholder text.
- Optional, not required: a `desktopApp` `jvmTest` asserting the composition root builds a
  `ConnectionsViewModel` without throwing (construction only, no window).

## Design notes

`desktopApp` becomes the first module depending on both `ui` and `data-rabbit` — expected, per
`docs/spec.md`'s Module layout table (`desktopApp`'s job is exactly "`main()`, window, menus,
packaging", i.e. the composition root). This does not weaken the `core`/`ui` boundary: `ui` itself still
never imports `data-rabbit`, only `desktopApp` does, at the outermost layer.

## UX

No new screen; this task only changes which screen `desktopApp` shows on startup.

## Open points

- First real run on a developer machine will actually read/write `~/.caerbannog/connections.json` (empty
  on first run — harmless, same as the Cucumber/`data-rabbit` tests expect) and touch the real OS
  keychain if a password is saved. Record in the Status block that this was noticed and is expected, not
  a surprise to debug later.

## Definition of done

- Manual run confirmed and recorded in the Status block.
- No unrelated changes.
- `docs/tasks/README.md` updated.
