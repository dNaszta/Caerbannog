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
