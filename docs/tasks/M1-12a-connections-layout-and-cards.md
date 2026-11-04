# Connections screen layout and cards (app bar, drawer, field grid, card styling, carry-over fixes)

Milestone: M1    Depends on: M1-07 (done)    Size: M    Split from: M1-12 (2026-11-04); icons are in `M1-12b`

## Goal
The Connections screen has the structure of `docs/ux/Main.dc.html`: drawer, app bar with "New connection", saved
profiles section, two-column field grid with the ports as read-only fields, profile cards and pills in the mockup's
colours. No behaviour changes except the one core fix below. A screenshot helper lets the user compare with the mockup.

## Why
The product owner said the screen "isn't straight the design" (2026-11-04). Spec: Screens table, Connections; UX source
`docs/ux/Main.dc.html`, `docs/ux/m3.css`. Carries the M1-07 follow-ups.

## Product decision (2026-11-04)
"Use material defaults": Material 3 default components and theme values wherever the mockup does not demand a custom
look. The mockup uses the M3 baseline palette, and the app already runs on the default `MaterialTheme`, so the
baseline colours match; only the mockup's `ok` and `warn` colours are custom (already in `PermissionColors`).
Icons: see `M1-12b`.

## Gap list against the code on main (confirmed 2026-11-04)
| Area | Mockup | Code now |
| --- | --- | --- |
| Frame | Drawer 280 (`sc-low`), main with app bar (min 64, "Connections" 22/28, filled "New connection" at the right), content | One `Row`: 280dp list, 24dp gap, panel; no app bar, no drawer; "New connection" is a full-width button in the list |
| Drawer | Brand ("Caerbannog", "behind the rabbit"), Connection card (name, pill, host), nav items | none |
| Profile list | Section 340 wide in the content, label "Saved profiles" | 280dp column |
| Profile card | padding 16, gap 4, `surface` background, 1dp `outline-variant` border, radius 12; selected: `secondary-container`, no border | `Card` with `surfaceVariant`, padding 12, no border |
| Pill | 24 high, radius 12, NEUTRAL `sc-highest`/`on-surface-variant`, locked `error-container`/`on-error-container` | NEUTRAL `surfaceVariant`, LOCKED `errorContainer` (same hex for LOCKED in the baseline scheme; NEUTRAL differs: `#E7E0EC` vs `#E6E0E9`) |
| Fields | `auto-fit minmax(220px,1fr)`, gap 24 16; order Name, Virtual host, Host, Username, Stream port, Management port, Password, CA | One column; order Name, Host, Virtual host, Username, Password, ports as text lines, CA |
| Panel card | `sc-low`, radius 12, padding 16 | `surfaceContainer` |
| Banner/pill type | 12/16 medium pill | done in M1-07 |

## Scope
In:
- Frame: drawer (brand, Connection card, one "Connections" nav item; text only, icons come in `M1-12b`), app bar with
  "Connections" and the "New connection" button, content area with the profile section and the edit panel.
- Drawer scope decision: only what exists in M1. The Connection card shows the profile currently selected in the panel
  (there is no session yet; real "connected" is M2): name, permission pill, host; nothing selected shows
  "No connection selected". The Search field and the Streams, Topology and Migrations nav items are NOT built (their
  screens do not exist; they come with M2 to M4). Record this in the Status.
- Field grid in the mockup's order, two columns that collapse to one when narrow (`FlowRow`, or two `Row`s with
  `weight` plus a width check; developer's choice, the check is the mockup's 220dp minimum per column). Ports become
  read-only `OutlinedTextField`s with the labels "Stream port (TLS)" and "Management port (HTTPS)" (values 5551 and
  15671 unless the profile has others). Confirm: ports stay not editable in M1 (TLS and standard ports; the profile
  already stores them).
- Profile card: padding 16, border `outline-variant` 1dp, background `surface`, selected `secondaryContainer` without
  border, radius 12. Pill: NEUTRAL colours as the mockup (`surfaceContainerHighest` / `onSurfaceVariant`), LOCKED
  `errorContainer` / `onErrorContainer`; keep all in `PermissionColors`. Panel card background `surfaceContainerLow`.
- Pill test cleanup: remove `the long declared pill text stays on one line` (cannot fail) and replace it with a test
  that can: assert the pill's measured width is at most the card's inner width and the pill text is on one line when
  the profile name is long (the name must ellipsize, the pill must not wrap). Mutation check: remove `softWrap = false`
  and `weight(1f)` on the name; the test must fail.
- Core fix: a late `checkConnection` result must not set `lastCheckResult` when the profile was deleted meanwhile (or,
  same hole, when the selection changed meanwhile: the banner belongs to one profile). Same guard style as the
  permission-check token. Core test first.
- Screenshot helper (see Acceptance).
Out: icons and the password reveal toggle (`M1-12b`), the other drawer items, any other behaviour, new dependencies.

## Acceptance
- Automated: all existing UI tests pass (tests that click the card keep working); new UI tests: the app bar shows
  "Connections" and a "New connection" button that opens the empty panel; the field labels exist in the mockup order and
  the two port fields are read-only with the right values; the drawer shows the selected profile's name, host and pill
  (and "No connection selected" when none); the replaced pill test.
- Core test (first, red): `checkConnection` for a profile deleted while the check runs leaves `lastCheckResult` null;
  same for a selection change while it runs. Put it in `ConnectionsViewModelTest` or a new
  `ConnectionsViewModelLateResultTest` with a gated fake reachability checker (no sleeps).
- No BDD scenario: no behaviour change visible in Gherkin. Test developer confirms none of
  `tests/bdd/features/01_connections.feature` changes.
- Visual (manual, by the user): checklist below plus the screenshots.

### Screenshot artifact (feasibility, assessed 2026-11-04)
Feasible without a new dependency. `runDesktopComposeUiTest` (already used) can capture the root with
`onRoot().captureToImage()` and write a PNG through `javax.imageio` (JVM only, `ui/src/jvmTest`). Add an opt-in test
tagged `screenshot` (same pattern as `keychainTest`: excluded from the default `test`, run by a Gradle task and a
Taskfile entry, e.g. `task test:screenshots`) that renders these states at 1280x880 (the mockup's preview size) into
`ui/build/screenshots/`: empty list, three profiles with one selected (green, red and neutral pills), the edit panel
scrolled to the permission banner, the delete dialog. It asserts nothing about pixels (no golden files: fragile across
fonts and machines); it only fails if rendering throws. Honest limits: Skia on the desktop does not use the mockup's
Roboto/Material Symbols web fonts unless a font is bundled, so glyph shapes and text widths differ slightly from the
HTML; layout, spacing, colours and structure are comparable. The mockup side is the user opening
`docs/ux/Main.dc.html` in a browser at 1280x880 (it needs `support.js` and network fonts; we do not produce a PNG of
it). Side by side by eye is the acceptance, not a pixel diff.

### Manual visual checklist (user, against `Main.dc.html`)
1. Drawer on the left, about 280 wide, soft lilac background, brand at the top, Connection card, "Connections" item
   highlighted.
2. App bar: title "Connections" at the left, filled "New connection" button at the right.
3. Saved profiles: label, then bordered white cards with a name, a pill at the right, host and status lines; the
   selected card lilac without a border.
4. Pills: green, red, amber, grey colours as the mockup (grey is the light grey `#E6E0E9`).
5. Edit panel: one rounded card; fields in two columns in this order: Name, Virtual host, Host, Username, Stream port,
   Management port, Password, CA certificate; ports greyed or read-only.
6. Narrow the window: fields fall to one column, nothing overlaps, the pill never wraps.
7. Banners and the thin scrollbar as in M1-07.

## Design notes
`ui` only, plus one change in `core` (view model). No broker code in `ui`. Keep `ConnectionsScreen.kt` from growing:
split the new frame into `ConnectionsFrame` (drawer, app bar), `ProfileCard`, `ConnectionFields` files in the same
package. Colours only from `MaterialTheme.colorScheme` plus `PermissionColors`; no new hex values except where the
mockup has a custom constant.

## UX
`docs/ux/Main.dc.html`, `docs/ux/m3.css` (`.drawer`, `.appbar`, `.pcard`, `.pill`, `.tf`, `.card`). States: empty,
new, editing, checking, error, delete dialog (all exist).

## Open points
- Ports read-only: confirm with the product owner when seen (assumed).
- The mockup shows a "Connected" status on a profile; M1 has no session, so cards keep "Editing" / "Not connected".
  Mockup text stays as is; real status comes with M2.

## Definition of done
Tests written first (core test red first), all passing, no unrelated changes, screenshot task works, README row,
Status written, manual check by the user recorded.

## Status
- 2026-11-04 (architect): briefed and confirmed against code on main; split from M1-12. Not started.
- 2026-11-04 (test developer, step 2, tests first, branch `m1-12a-connections-layout-and-cards`): red state written, no production code. Core: `ConnectionsViewModelLateResultTest` (5 tests, gated reachability checker, no sleeps; control passes, 4 fail: delete, selection switch, New connection, delete plus select). UI (`ConnectionsScreenUiTest`, 83 tests, 15 red, all on assertions): app bar, field order and two columns, collapse to one column at 600 wide, read-only ports (no SetText, values 5551/15671 and a profile with other ports), drawer (name, host, pill, "No connection selected", brand/nav text, no Search/Streams/Topology/Migrations), pill containment (1280 and 640 wide). Old `the long declared pill text stays on one line` removed; old `New connection` clicks go through one helper (`hasText and hasClickAction`). Screenshot helper `ConnectionsScreenshotTest` (`@Tag("screenshot")`, 4 PNGs in `ui/build/screenshots/`, asserts exist and non-empty) compiles and passes today because the tag is not excluded yet. Test tag contract for the developer: `connectionsAppBar`, `connectionsDrawer`, `drawerConnectionCard`, `drawerPermissionPill` (not `permissionPill:<name>`), `profileCard:<name>` (card, the click target). Drawer scope decision (architect, 2026-11-04) recorded: only brand, Connection card and the Connections item; Search, Streams, Topology, Migrations come with M2 to M4. No change to `01_connections.feature`. Pill test mutation proof is in the step-2 report.
- 2026-11-04 (developer, step 3, build): code written. Core: `checkConnection` writes `lastCheckResult` only if the selection at the end equals the selection at the start and the profile still exists (a check with nothing selected still writes); permission-check token logic untouched. UI split into `ConnectionsScreen.kt` (screen, edit panel), `ConnectionsFrame.kt` (drawer, app bar, content with stack-when-narrow below 844dp), `ProfileCard.kt` (list, card, pill, `PermissionColors`), `ConnectionFields.kt` (two-column grid via `BoxWithConstraints`, collapses under 456dp; read-only port fields). Pill NEUTRAL is `surfaceContainerHighest`; panel `surfaceContainerLow`. Screenshot: `tasks.test` (`jvmTest`) excludes tag `screenshot`; `:ui:screenshotTest` (not wired from test/check), `task test:screenshots`, spec Local dev commands row. Results: core 111/0, data-rabbit 17/0, desktopApp 5/0, ui 79 run, 1 red (default run no longer contains the screenshot class). The one red is a test defect, not code: `the app bar shows the title Connections and a New connection button at its right` (line 1160) reads the button through `nodeBounds` (unmerged tree) with `hasText and hasClickAction`; in the unmerged tree the Button node has the click action and its child Text node has the text, so no single node matches. With that one line using the merged tree (`onNode(...)` without `useUnmergedTree`) the whole ui suite is green (checked locally, file restored). Needs the test developer. Drawer: no Search and no Streams/Topology/Migrations items (M2 to M4). Pill name squeeze: not changed, containment tests pass; no min width for the name.
- 2026-11-04 (test developer, step 4, verify): test defect fixed (app bar test now reads the button from the merged tree, still checks right half, right edge and inside the bar). Clean `./gradlew test --rerun-tasks --continue`: exit 0; XML counts core 111, data-rabbit 17, desktopApp 5, ui 79 (0 failed, 0 skipped), acceptance-tests 43 (5 skipped, pre-existing @broker/needs tags); `ConnectionsScreenshotTest` is not in the default run; `./gradlew test --dry-run` still lists :core:test and :ui:test, no screenshotTest. `:ui:screenshotTest` wrote the 4 PNGs (non-empty). Mutations all caught (core guard removed 4 red, profile-only guard 2 red, ports editable 3, host field removed 18, port order swapped 2, drawer Search text 1, drawer Streams item 1, drawer pill tag reused 16, New connection in list 9, button no-op 5, card without click 43); removing the screenshot exclusion is NOT caught by any test (class then runs in default test; it is harmless but silent). No new dependency, M1-06/07/10 behaviours unchanged by diff. Screenshot findings for the user: see the step 4 report (panel top alignment and title spacing, truncated Password/CA labels, panel corners cut, no icons (12b)). Verdict pass with findings, no blocker or major; waiting for the manual check by the user.
- 2026-11-04 (architect, step 5, review): CHANGES REQUIRED (small), see the rulings. Reviewed: full diff, core guard, ui split, Gradle/Taskfile wiring, spec row, the four PNGs against `Main.dc.html` and `m3.css`. Not reviewed: runtime behaviour beyond the test report. Layering clean (`ui` commonMain has no broker code; screenshot test is JVM-only in `jvmTest` with `javax.imageio`; no new dependency); M1-06/07/10 behaviour unchanged by the diff; no BDD change.
  Rulings. S1 (panel level with the 'Saved profiles' label): ACCEPTED, the mockup does the same (`align-items:flex-start`, panel and list section both start at the content top). S2 (corners/top padding): ACCEPTED, screenshot artefact: the empty-state PNG shows the rounded card and 16dp padding; in the other PNGs the panel is scrolled by 16dp, and the padding lives inside the scroll content (the mockup says the panel scrolls). S3 (truncated Password and CA labels): MUST FIX NOW. S7 (rhythm 32 vs 24): ACCEPTED, the Compose outlined field reserves 8dp above the border for the label; part of the default component ("use material defaults"). Minimum width for the profile name: ACCEPTED, the mockup has none either; the name ellipsizes and the pill never wraps (tests cover both). F1 (unguarded screenshot exclusion): ACCEPTED, no test; a test that reads the Gradle file is worse than the risk (the class is harmless in the default run). Unused imports in `ConnectionsScreen.kt` (and `Box` after `Row` import order in `ConnectionFields.kt`): FIX NOW. Icons: M1-12b. Drawer 'Connection' card shows the selected profile as a stand-in for the connected one (KDoc on `ConnectionsDrawer` says so); for M2 the card must switch to the open `ConnectionSession` and the pill to the session's permission state (note carried into the M2 briefs).
  Developer and test items: (1) test developer, first: in `ConnectionsScreenUiTest` line 990 change `onNodeWithText("Password (leave blank to keep unchanged)")` to `onNodeWithText("Password")` and add a test that the label of the password field is exactly `Password` and of the CA field exactly `CA certificate (optional)` for both a new and a saved profile, and that for a saved profile the supporting text 'Leave blank to keep the saved password' is shown (new profile: not shown); CA supporting text 'Blank uses the system trust store'. Lines 1140 to 1216 (substring matching) keep working; tighten them to exact if easy. (2) senior developer: `ConnectionsScreen.kt` lines 187 and 199: short labels `Password` and `CA certificate (optional)` as the mockup, long text moved into `supportingText` (single line, `bodySmall` default); remove unused imports; the field grid must still line Password and CA on one row. Then test developer reruns the full suite plus `:ui:screenshotTest` and looks at the PNG. Optional idea (not blocking): the guard in `checkConnection` calls `repository.list()` inside `_state.update`, which can retry; read it before the update.
- 2026-11-04 (test developer, round 2, step 2): tests first for the short Password and CA labels with single-line supporting text (hints 'Leave blank to keep the saved password' for saved profiles only, 'Blank uses the system trust store' for both). Password masked test now uses exact 'Password'; grid tests use exact labels (Password and CA certificate (optional) still asserted on one row). Red: 6 UI tests fail on assertions (password masked, CA hint, short labels, hint under Password, grid order, narrow grid). No production code.
- 2026-11-04 (developer, round 2, step 3): S3 done: labels `Password` and `CA certificate (optional)`, single-line supportingText ('Leave blank to keep the saved password' for a saved profile only; 'Blank uses the system trust store' always); both stay on one grid row. I1 done (unused imports removed; `getValue`/`setValue` kept, they are needed by the `by` delegates). C1 done (`repository.list()` read before `_state.update`). Results: core 111/0, data-rabbit 17/0, desktopApp 5/0, ui 82/0, acceptance 43 (5 skipped)/0; `:ui:screenshotTest` passes. Screenshot: labels no longer truncated; the Password supporting text is still ellipsized at the 1280 width ("Leave blank to keep the saved pass...", about 244dp cell, 12sp text); the CA text fits.

