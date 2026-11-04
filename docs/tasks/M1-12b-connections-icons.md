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

## Scope
In: add the dependency (only this one); icons: lock, verified_user, help in pill and banners; check_circle on the
success banner; lock beside "TLS is always on"; add on "New connection"; delete on "Delete profile"; wifi_tethering on
"Check connection and permissions"; upload_file in the CA field; drawer brand cruelty_free and nav icon `power`;
password reveal toggle (visibility / visibility_off) in the password field's trailing slot. Icons are decorative
(`contentDescription = null`) except the reveal toggle, which has a description ("Show password" / "Hide password").
Out: other nav items, radio and switch icons (keep Material `RadioButton` and `Switch`), any behaviour change.

## Acceptance
- UI tests (first, red): the reveal toggle: default masked (`EditableText` is bullets), click shows the typed text,
  click again masks it; the toggle has the content descriptions; the typed password is never shown by default and
  switching profile resets the toggle to masked.
- UI tests: pill and banner icons exist by test tag for the verified (verified_user), declared (help) and locked
  (lock) states; the pill text assertions from M1-07 still pass.
- Dependency check: `./gradlew :ui:jvmTest` green; `:desktopApp:run` opens the screen; note the new installer size
  after `:desktopApp:packageDmg` in the Status (optional, informational).
- Manual (user): checklist of `M1-12a` plus: every icon in the mockup is present and at a similar size (small icons in
  pills and buttons 18, others 24).

## Design notes
`ui` only. A small `ConnectionsIcons` file mapping our names to `Icons.Outlined.*` keeps the library import in one place.
The reveal toggle state is local UI state (`remember`, reset per selected profile like the other form fields); not in
the view model. Never log or persist the visibility state.

## First commit of M1-12b (carry-over from M1-12a round 2, architect 2026-11-04)
Before any icon work, as its own commit on the same files: the Password hint on a saved profile is ellipsized at 1280x880
('Leave blank to keep the saved pass...'; the grid cell is about 192 to 244dp). Change the text to exactly
`Blank keeps the saved password` (about 30 characters, fits; same style as the CA hint 'Blank uses the system trust
store'). Test developer first: `ConnectionsScreenUiTest.kt` line 1399, `passwordHint` becomes
`"Blank keeps the saved password"` (it is the only string to change; the tests that use `passwordHint` follow it);
also add one assertion that the hint node is not truncated (`TextLayoutResult.hasVisualOverflow` false, or hint width
within the cell) at the 1280 wide test size. Senior developer: `ConnectionsScreen.kt` line 175, same string. Then look at
`:ui:screenshotTest` PNG 02 to confirm no ellipsis. No other change in that commit.

## Open points
Compatibility of 1.7.3 with Compose Multiplatform 1.9.3 is expected but unproven (see above).

## Definition of done
Tests first and passing, only one dependency added, no unrelated changes, README row, Status with the dependency and
installer notes, manual check recorded.

## Status
- 2026-11-04 (architect): product owner decided "use material defaults" (standard Material icons); artifact verified
  on Maven Central; briefed. Not started.
