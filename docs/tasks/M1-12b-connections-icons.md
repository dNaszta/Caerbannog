# Connections screen icons (Material icons) and password reveal toggle

Milestone: M1    Depends on: M1-12a (soft: the icon slots exist there; can also start in parallel on the same files only
after M1-12a merges)    Size: S

## Goal
The Connections screen shows the Material icons the mockup uses, and the password field has a show/hide toggle.

## Why
`docs/ux/Main.dc.html` uses Material Symbols for the pill, banners, buttons, drawer and fields. Product decision
(2026-11-04, "use material defaults"): use the standard Material icon set, not hand-drawn assets.

## Dependency decision (made by the product owner; verified by the architect 2026-11-04)
Add exactly one library: `org.jetbrains.compose.material:material-icons-extended:1.7.3` in `ui` `commonMain`
(declare the coordinate explicitly: `implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")`;
do not use the `compose.materialIconsExtended` plugin accessor, which is deprecated in newer Compose Multiplatform).
Checked on Maven Central: 1.7.3 is the latest and last release of this artifact (published 2024-12-19, far more than two
weeks old); JetBrains stopped publishing it after Compose Multiplatform 1.7, so there is no 1.9 version. It is frozen,
not removed. Compatibility: the repo uses Compose Multiplatform 1.9.3 and Kotlin 2.4.21; the build already resolves
`material-icons-core` 1.7.3 next to material3 `1.9.0-beta03`, and icons are plain `ImageVector` data, so it is
expected to work. NOT proven until the developer compiles and runs the UI tests: if it fails to compile or run, stop and
come back to the product owner (alternative then: Compose Multiplatform resources with SVG, which needs a resources
setup, or hand-drawn vectors).
Consequences: the desktop jar is about 38 MB, and jpackage will put it in the installer (installer grows by roughly
that, less after compression; ProGuard shrinking is not configured and is out of scope). Startup cost is small: icon
classes load lazily, one `ImageVector` is built the first time an icon is used. Build and IDE indexing are slightly
slower. Frozen version means new Material icons (post 2024) are not available; every icon used here was checked in the
jar (`filled`/`outlined`: AccountTree, CrueltyFree, Help, Hub, Power, Stream, UploadFile, VerifiedUser, Visibility,
VisibilityOff, WifiTethering). Lock, Add, Delete, CheckCircle, Search are in `material-icons-core`, already on the
classpath through material3 (developer verifies; if not, the extended artifact has them too).
Use the Outlined style (the mockup uses Material Symbols Outlined).

## Scope (sharpened by the architect 2026-11-04 against main 28e0bdc)
In: the dependency (only this one); the icon table below; the password reveal toggle; the Password hint fix as the first
commit. Out: other nav items (stream, hub, account_tree), the drawer search box, radio and switch icons (keep Material
`RadioButton` and `Switch`), the mockup's `toggle_on` and `radio_button_*` glyphs, any behaviour change, ProGuard.

### Icon table (Outlined style; `Icons.Outlined.*`; sizes from `docs/ux/m3.css`: `.ms` 24, `.ms.sm` 18)
| Where | Mockup name | Compose name | Size | Artifact | Test tag |
| --- | --- | --- | --- | --- | --- |
| Drawer brand, before "Caerbannog", tinted `primary` | cruelty_free | `CrueltyFree` | 24 | extended | `icon:cruelty_free` |
| Drawer nav item "Connections" (active) | power | `Power` | 24 | extended | `icon:power` |
| New connection button (app bar), leading | add | `Add` | 18 | core | `icon:add` |
| Pill, tone LOCKED (Topology locked, also unreadable) | lock | `Lock` | 18 | core | `icon:lock` |
| Pill, tone OK (Can change topology) | verified_user | `VerifiedUser` | 18 | extended | `icon:verified_user` |
| Pill, tone WARNING (declared, either wording) | help | `Help` | 18 | extended | `icon:help` |
| Pill, tone NEUTRAL (Not checked, Checking...) | none in the mockup | no icon | - | - | none |
| Permission banner, tone OK / WARNING / LOCKED | verified_user / help / lock | `VerifiedUser` / `Help` / `Lock` | 24 | extended / extended / core | `icon:<mockup name>` |
| Permission banner, tone NEUTRAL | none | no icon | - | - | none |
| Connection check banner, BothReachable only | check_circle | `CheckCircle` | 24 | core | `icon:check_circle` |
| Line "TLS is always on...", before the text | lock | `Lock` | 24 | core | `icon:lock` |
| Delete profile (text button), leading | delete | `Delete` | 18 | core | `icon:delete` |
| Check connection and permissions (outlined button), leading | wifi_tethering | `WifiTethering` | 18 | extended | `icon:wifi_tethering` |
| CA certificate field, trailing | upload_file | `UploadFile` | 24 | extended | `icon:upload_file` |
| Password field, trailing (functional) | visibility / visibility_off | `Visibility` / `VisibilityOff` | 24 | extended | `icon:visibility`, `icon:visibility_off` |

Corrections to the request: the mockup puts `help` on the declared (WARNING) banner, not on not-checked, and the
unreadable state is `lock` (LOCKED tone). Not-checked and Checking get no icon (the mockup has none). The mockup draws a
pill icon only on the locked pill; icons on the OK and WARNING pills are the same tone icons as the banners, chosen here
so the pill and the banner of one state agree (see open point 1). The icon follows the tone, not the text, so it lives in
one `PillTone` to icon mapping in `ConnectionsIcons.kt`. The CA field's upload_file is decorative and not clickable (no
file picker in M1; it is the mockup's affordance, and the validation hook stays on `onValueChange`). Error banners for
the connection check (`ErrorBanner`) get no icon (the mockup has none). Only Lock, Add, Delete, CheckCircle are in
`material-icons-core`; Search is not used here. Everything else needs the extended artifact. Layout: pill icon 18 with 4dp
gap before the text, button icons 18 with 8dp gap (`ButtonDefaults.IconSize`/`IconSpacing` where they match), banner
icon 24 with 12dp gap, top-aligned with the first line.

## Password reveal toggle (behaviour)
- Default masked (`PasswordVisualTransformation`), also for a saved profile and a new one.
- Trailing `IconButton`; click shows the typed text (`VisualTransformation.None`), click again masks. The icon shows the
  action available: `Visibility` while masked (description "Show password"), `VisibilityOff` while shown ("Hide password").
  The description is on the `Icon` inside the `IconButton`; the button is the click target (keyboard focusable).
- State: `var passwordVisible by remember(selected?.name) { mutableStateOf(false) }`, next to the other fields, so it resets
  to masked when another profile is selected and when "New connection" is pressed from a saved profile. Known existing
  behaviour (not changed here): pressing "New connection" while a new form is already open does not clear the form, so it
  does not reset the toggle either.
- Never stored in core, the view model, profile data, logs or anywhere on disk. The password value is untouched.
- Keep `semantics { password() }` on the field in both states, so assistive tech still treats it as a password field.

## Acceptance
- BDD: no Gherkin scenario changes (no broker behaviour changes). Traceability stays M1-07/M1-12a.
- UI tests (Compose, first, red for the right reason: no icon nodes, no toggle):
  1. Reveal toggle: default `EditableText` is bullets only, toggle node has description "Show password"; after typing and
     click the text is shown and the description is "Hide password"; second click masks again.
  2. Switching to another saved profile after revealing: masked again, description "Show password". Same for New connection
     pressed from a saved profile.
  3. The typed password is never in any node's text while masked (assert on `EditableText` and `Text`).
  4. Pill icons, found by `icon:*` tag under `permissionPill:<name>` (unmerged tree): Checked+unlocked has `icon:verified_user`;
     Checked+locked and Unreadable have `icon:lock`; DeclaredByUser (both lock and unlock) has `icon:help`; not checked and
     Checking have no `icon:*` node. Pill text assertions from M1-07 still pass.
  5. Banner icons under `permissionBanner`: same mapping at 24, NEUTRAL none. Also the drawer pill (`drawerPermissionPill`).
  6. Decorative icons expose no content description (no node with a description other than the two toggle strings and
     the delete icon's button text): assert that the only description set in the edit panel are "Show password"/"Hide password".
  7. Static ones present by tag: `icon:cruelty_free`, `icon:power`, `icon:add` (in the app bar), `icon:delete`,
     `icon:wifi_tethering`, `icon:upload_file`, `icon:lock` next to the TLS line, `icon:check_circle` after a BothReachable
     check and absent for the failure results.
  8. The existing layout tests (pill contained and name ellipsized at 640 and 1280, M1-12a) still pass: the pill grew by an icon.
- Dependency check: see Build order step 0. `:ui:jvmTest` green; `:desktopApp:run` opens the screen.
- Informational: jar size and, if run, `:desktopApp:packageDmg` size in the Status.
- Manual (user): the M1-12a checklist plus: every icon in the table is present at the stated size, tinted by the
  surrounding content colour (pill and banner icons share the text colour of their tone), the eye toggles the password and
  the field is masked again after selecting another profile; no ellipsis in the Password hint at 1280.

## Build order and stop rule
0. Compile proof, before any test or icon work (own commit "M1-12b: add material-icons-extended 1.7.3"): add
   `implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")` to `ui` `commonMain.dependencies`, and a
   throwaway usage (for example `val probe = Icons.Outlined.VerifiedUser` referenced from a test or from `ConnectionsIcons.kt`
   skeleton). Run `./gradlew :ui:jvmTest` and `:desktopApp:run` (or the screenshot task as a headless run). Stop rule: if it does not
   compile, resolve (version conflict with Compose Multiplatform 1.9.3 / Kotlin 2.4.21 / material3 1.9.0-beta03) or fail at
   runtime (NoSuchMethodError, ClassNotFound), stop, revert, record the error in the Status, and return to the
   product owner. Fallback is Compose Multiplatform resources with SVG. Do not fix it with other libraries or versions.
1. Password hint commit (below) can go first or second; it does not need the dependency. Order: hint commit, then step 0,
   so a failed proof leaves the hint fix on the branch.
2. Test developer: tests 1 to 8 red (they need the `icon:*` tag contract and the descriptions only, so they compile without
   the library; the tag helper can be a one-line `Modifier.testTag` in the test).
3. Senior developer: `ConnectionsIcons.kt` (internal; `ConnectionIcon(name, size, ...)` helper applying `testTag("icon:<name>")`
   and `contentDescription = null`; `PillTone` to icon mapping; the only file importing `androidx.compose.material.icons`),
   then slots, then toggle.
4. `:ui:screenshotTest`, record PNG paths; user looks.

## Design notes
`ui` only; `core` unchanged (the tone to icon mapping is presentation, so it stays out of `core`). The library import is
confined to `ConnectionsIcons.kt`. Reveal state is local UI state, not in the view model. The `icon:` test tag convention
is a test hook only; it does not change any accessible text. Accessibility: decorative icons have
`contentDescription = null` (the pill, banner, button and field texts already carry the meaning, so a screen reader must
not read "lock Topology locked"); functional icons have a description: the reveal toggle. The delete button keeps its text
label "Delete profile", so its leading icon is decorative (the request listed delete as functional: it is functional as a
button, and the button already has an accessible name). No colour is the only carrier of meaning: the pill text stays.
Distribution: the extended jar is about 38 MB and goes into the installer; ProGuard is out of scope (recorded in the
dependency decision above). Note for later: `material-icons-extended` is frozen at 1.7.3; a migration to Compose
Multiplatform resources can replace `ConnectionsIcons.kt` alone, which is why the import is confined there.

## First commit of M1-12b (carry-over from M1-12a round 2, architect 2026-11-04)
Before any icon work, as its own commit on the same files: the Password hint on a saved profile is ellipsized at 1280x880
('Leave blank to keep the saved pass...'; the grid cell is about 192 to 244dp). Change the text to exactly
`Blank keeps the saved password` (about 30 characters, fits; same style as the CA hint 'Blank uses the system trust
store'). Commit message: `M1-12b: shorten the Password hint so it is not ellipsized`. Test developer first: `ConnectionsScreenUiTest.kt` line 1399, `passwordHint` becomes
`"Blank keeps the saved password"` (it is the only string to change; the tests that use `passwordHint` follow it);
also add to the existing test at line ~1425 (`a saved profile shows the keep-password hint...`) the assertion `assertFalse(layouts.single().hasVisualOverflow)` at 1280 wide (the existing `lineCount == 1` assertion passes for an ellipsized line, so it is not enough). It must fail on the old string: write it first and watch it go red with the new constant only after the check against the old text; with the constant changed first, verify the assertion by temporarily using the long string. Senior developer: `ConnectionsScreen.kt` line 175, same string. Then look at
`:ui:screenshotTest` PNG 02 to confirm no ellipsis. No other change in that commit.

## Open points
1. Product (minor, not blocking): icons on the OK and WARNING pills and on the OK/WARNING/LOCKED banners beyond the
   mockup's locked pill. Recommendation: build as in the table (state and icon agree); the user can drop pill icons for OK and WARNING at the manual look.
2. Compatibility of 1.7.3 with Compose Multiplatform 1.9.3 / Kotlin 2.4.21 is expected but unproven: step 0 decides, stop rule applies.
3. The CA field's upload_file is not clickable in M1 (no file picker). If the user expects a picker, that is a new task.
4. Installer size effect measured only if `packageDmg` is run; informational.

## Definition of done
Tests first and passing, only one dependency added, no unrelated changes, README row, Status with the dependency and
installer notes, manual check recorded.

## Status
- 2026-11-04 (architect): product owner decided "use material defaults" (standard Material icons); artifact verified
  on Maven Central; briefed.
- 2026-11-04 (architect): brief sharpened against main 28e0bdc (icon table with sizes and artifacts, reveal toggle
  behaviour, test plan, build order with the compile proof as step 0 and the stop rule, accessibility). Ready to start on
  branch `m1-12b-connections-icons` from main. Not started.
- 2026-11-04 (test developer, step 2, tests first, branch `m1-12b-connections-icons`): two commits, no production code, no
  dependency, no Gherkin change. Commit 1 (`M1-12b: tests for the shorter Password hint`): `passwordHint` constant is
  `Blank keeps the saved password`; the keep-password-hint test also asserts `assertFalse(hasVisualOverflow)` at 1280 wide;
  mutation-proved (with the old string in the constant the new assertion fails: "must not be ellipsized at 1280 wide"; with the
  new string the test fails on the missing text). Commit 2: 16 new `ConnectionsScreenUiTest` tests (suite 97, 16 red incl. the
  hint test from commit 1, all on assertions): toggle (default masked new and saved, click shows and `Hide password`, second
  click masks, merged button has click action plus description), reset on profile switch and on New connection, typed
  password in no `Text`/`EditableText` while masked, pill/drawer pill/banner icon per status (not checked, Checking, Checked
  unlocked/locked, Declared unlocked/locked, Unreadable), the only content descriptions are the two toggle strings and
  every `icon:*` except visibility has none, brand/nav/add/delete/wifi_tethering/upload_file/visibility/lock-in-TLS-line
  placement by bounds, `icon:check_circle` only after BothReachable (absent for the four failure results). The M1-12a pill
  and name-ellipsis tests at 640 and 1280 are in the run and pass today. Tag contract: `icon:<mockup name>` on the icon
  node itself (unmerged tree), toggle description on the same node as `icon:visibility` / `icon:visibility_off` is not
  required (found by description). Sizes (18/24) are not asserted (no pixel tests).
- 2026-11-04 (senior developer, step 3, branch `m1-12b-connections-icons`): four commits. (1) Password hint shortened to
  `Blank keeps the saved password` (PNG 02 shows it whole). (2) Compile proof: `material-icons-extended:1.7.3` explicit
  coordinate in `ui` commonMain (no version catalog in the repo), throwaway probe; `:ui:compileKotlinJvm`, `:ui:jvmTest` (compiles)
  and `:desktopApp:compileKotlin` passed; stop rule not triggered. `:desktopApp:run` NOT run (it reads the real `~/.caerbannog`);
  runtime proof is the `:ui:jvmTest` and `:ui:screenshotTest` runs, which render every icon (no NoSuchMethodError or
  ClassNotFound). (3) `ConnectionsIcons.kt` (only file importing `androidx.compose.material.icons`; `AppIcon` enum, `PillTone.icon()`,
  `ConnectionIcon` with `icon:<name>` tag, no description unless given), all slots per the table, reveal toggle
  (`remember(selected?.name)`, `semantics { password() }` kept). Suite: core 111, data-rabbit 17, desktopApp 5, ui 98
  (the brief said 97: that is `ConnectionsScreenUiTest` alone, plus `AppUiTest`), acceptance 43 (5 skipped); no
  `ConnectionsScreenshotTest` in the default run. Two ui tests stay red, both test-side defects (production behaviour verified
  by PNG): (a) hint test: `hasVisualOverflow` is true for any shrink-wrapped single-line `Text` with `TextOverflow.Ellipsis`
  (the CA hint, which is plainly whole in the PNG, also reports `size.width 202 < multiParagraph.width 228` and
  `hasVisualOverflow = true`; the hint's `getLineRight(0)` is 195.7 of 228); use `isLineEllipsized(0)` instead. (b) TLS-line
  test: at 1280x880 the line is below the fold of the scrolling panel, so its bounds and the lock's are `0,0,0,0`; the test needs
  `performScrollTo()` first. One deviation to decide: with the trailing upload icon the CA label `CA certificate (optional)`
  wraps to two lines at 1280 (field 260dp wide) and the CA field is taller than the Password field (mockup: one line). Also the
  three bottom buttons no longer fit one line with their icons (Save was squeezed to one letter wide), so that row is now a
  `FlowRow` (Save wraps below when narrow). Installer size: not measured (`packageDmg` not run); extended jar 37.8 MB.
  Manual user check pending.
