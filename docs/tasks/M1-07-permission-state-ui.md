# Permission state in the UI (sidebar pill, edit panel banner, scrollbar)

Milestone: M1    Depends on: M1-03, M1-05, M1-06, M1-09, M1-10, M1-11 (all done)    Size: M

## Status
- Briefed and confirmed by the architect (rewritten after the M1-06/M1-10 changes). Not started.
- Product owner decisions recorded here (2026-11-04):
  1. Pill wording follows the mockup: "Can change topology" (class `pill ok`) and "Topology locked" (class `pill bad`).
     The old wording "Topology unlocked" is dropped.
  2. A declared result uses the WARNING colour (`pill warn`, `banner warn`), never the ok green. Wording:
     "Can change topology (declared)" / "Topology locked (declared)". Mockup `docs/ux/Main.dc.html` and `m3.css`
     (`.banner.warn`) updated.
  3. New request: a visible cue that the edit panel scrolls (scrollbar), and the panel should look like the design
     again. Verdict in "Layout verdict" below.
- Step 2 (tests first, test developer): done, red. Written: core `PermissionSummaryTest`,
  `ConnectionsViewModelPermissionChecksTest`; BDD steps in `PermissionCheckSteps` (five scenarios untagged, runner
  filter `not @needs-M2 and not @broker`); UI tests in `ConnectionsScreenUiTest` (existing tests use a
  `selectProfile(i)` helper that clicks the card via its `permissionPill:<name>` tag); `CompositionRootTest`
  (closed loopback port, no network); `desktopApp` got a test-only coroutines dependency. Red: core, acceptance and
  desktopApp tests do not compile (missing contract types); `:ui:jvmTest` compiles and fails (32 of 54).
- Step 2 follow-up (architect rulings): `CompositionRootTest` now passes fake checkers through the new
  `permissionChecker`/`reachabilityChecker` parameters (red: compile error until the seam exists); UI tests added for
  the New-connection panel without banner/pill and for the saved-but-unchecked case; core test asserts the Unreadable
  banner has null detail and dimNote. Test-only `kotlinx-coroutines-core` in `desktopApp` accepted.
- Step 3 (build, senior developer): done, green except one test. Core, acceptance (the five scenarios pass, the two
  `@needs-M2` stay skipped), desktopApp green; `:ui:jvmTest` 56 of 57. The one failing test is
  `the password field is masked`: it asserts `onNodeWithText("hunter2")` does not exist, but `hasText` also matches the
  test-only `InputText` property, which holds the raw typed text; the field IS masked (`EditableText` = bullets,
  `Password` semantics present). Needs a test-developer fix (assert `EditableText` is bullets).
  Built: `PermissionSummary.kt`, `Unreadable.reason`, view model flows, composition-root seam, pill/banner/scrollbar/
  layout. `VerticalScrollbar` compiles in commonMain (no expect/actual). No new dependency. The profile card is not
  `clickable` (it merges children and hides the pill tag from the merged tree): it uses `pointerInput` plus button
  semantics, so it has no keyboard focus yet (follow-up for M1-12). Manual look still to do.
- Step 4 (verify, test developer, 2026-11-04): pass with findings, no blocker or major. Full `./gradlew test
  --rerun-tasks --continue` green: core 100, acceptance 43 (5 skipped by design: 3 `@needs-M2`, 1 `@broker`, 1
  deferred "Switch between connections"; the five former `@needs-M1-07` scenarios run and pass), data-rabbit 19,
  desktopApp 5, ui 57. Test fix: `the password field is masked` now asserts `EditableText` is the bullet mask (the
  senior developer was right: `hasText` also reads the test-only `InputText`); mutation (remove
  `PasswordVisualTransformation`) makes it fail. `selectProfile` now uses the unmerged tree. 27 production mutations
  killed (pill text/tone per row, dim note, declaration over Checked, userMessage, Save path, rename key, delete,
  Select/start-up check, reachability skipped or guarding, New-connection banner, scrollbar tag, Checking/Done). Open
  minor findings in the report (banner layout versus mockup, card focus). Manual look by the user still pending.
- Step 5 (architect review, 2026-11-04): **Changes required** (small). Items:
  - Senior developer: (a) `ProfileCard` must be keyboard focusable again: use `Modifier.clickable` (focus, Enter/Space,
    indication) instead of `pointerInput` plus hand-made button semantics; (b) the WARNING tone is a new amber
    `warn-container` (`#FFE2B0` on `#3B2700`, now in `docs/ux/m3.css` as `--warn-container`/`--on-warn-container`) in
    `PermissionColors`, not `tertiaryContainer` (pink, reads as staging/error, not as a warning); (c) banner: bold
    headline, detail in 12sp, and the dim note drawn BELOW the banner as its own dim line (as in the mockup); (d) pill
    12sp / weight 500, `maxLines = 1`, name `maxLines = 1` with ellipsis; (e) stale-write guard: a check result is
    stored only if the profile it was started for still exists under that name (and for a save, the new name); same
    for the `Checking` entry.
  - Test developer: (f) pill and banner lookups in `ConnectionsScreenUiTest` use `useUnmergedTree = true` where the
    card is now `clickable` (merged); add a test that the card takes keyboard focus and Enter/Space selects it; (g) a
    core test for (e): delete or rename while a gated check is in flight leaves no map entry, and a new profile with
    the reused name shows "Not checked".
  - Product owner decision 1 (open point 1) was taken by the coordinator, not the user: the check runs only on the
    button "Check connection and permissions" and after a successful Save of an existing profile. After launch every
    pill reads "Not checked". The user should confirm or change this before M2.
  - Moved to `M1-12`: column widths and card colours (item 5), icons in pill and banner.
- Branch: `m1-07-permission-state-ui` from `main`.

## Architect rulings (2026-11-04, after step 2)
1. **Composition root seam: yes.** `createConnectionsViewModel` gets `permissionChecker: PermissionChecker =
   ManagementApiPermissionChecker()` and `reachabilityChecker: BrokerReachabilityChecker =
   StreamAndManagementReachabilityChecker()`, real defaults, next to the existing `profilesFile` and `secretStore`. The
   session is built from `permissionChecker` and the same `secretStore`. Test change: `CompositionRootTest` should pass fake
   checkers instead of using a closed loopback port (deterministic, no socket); a fake that returns a known result is
   enough to prove the session is wired (pill/state shows it).
2. **New connection panel (no saved profile): the Permission check section is absent** (no banner, no pill). There is
   nothing to check before a profile is saved. A saved profile that was never checked shows the neutral banner
   "Permission check has not run yet." and the pill "Not checked". The removed assertion in the empty-state test is
   therefore correct for New connection; keep a test for the saved-but-unchecked case.
3. **Detail texts.** Confirmed: unlocked without write: headline "<user> can change topology on virtual host <vhost>."
   detail "The broker confirmed it, so topology screens and migrations are unlocked for this connection. Test publishing
   is disabled: no write permission." Declared detail is two lines joined by a single "\n": "The broker could not confirm
   it and still enforces its own permissions." then `Unreadable.reason`. Unreadable banner: headline
   `Unreadable.userMessage`, `detail` = null, `dimNote` = null.
4. **Pill inside the clickable card: yes.** The whole profile card is the click target and the pill (tag
   `permissionPill:<name>`) is a child of it.
5. **Password masking:** `PasswordVisualTransformation` on `OutlinedTextField` makes Compose emit
   `SemanticsProperties.Password`, so the test assumption stands. If it does not on this Compose version, add
   `Modifier.semantics { password() }` on the field; the test stays unchanged.
6. **`kotlinx-coroutines-core` as `testImplementation` in `desktopApp`: accepted** (already used in core, not a new
   library, test scope only). Record it in the Status.

## Goal
The user can always see what the permission check found for each connection (broker-read, declared by the user, could
not be read, not checked yet, checking), what that locks, and can see that the edit panel scrolls.

## Why
Spec "Broker-read permission" (wording and colour rule, new bullet) and its UX rule: a declared result must never look
like a verified one. Screen: `docs/ux/Main.dc.html`.

## Scope
In:
1. **Pill per profile** in the sidebar card (`SavedProfilesList` / `ProfileCard`; "sidebar" in the code is the Saved
   profiles list; there is no drawer yet, see `M1-12`).
2. **Edit panel "Permission check" banner** replacing the inert `PermissionCheckPanel`.
3. **Wiring**: `ConnectionSession` into the view model and composition root; the edit Save calls `saveProfileChanges`;
   the button "Check connection and permissions" runs the reachability check and the permission check.
4. **Scrollbar** on the edit panel plus the small layout fixes listed in "Layout verdict".
Out: topology-screen text (needs the M2 topology screen; scenarios tagged `@needs-M2`), any declare/delete screen (M5),
stream list (M2), the bigger layout pass (`M1-12`), automatic checks at start-up (see Open points).

## Layout verdict (current edit panel versus `docs/ux/Main.dc.html`)
The user is right: it no longer matches. Deviations found by reading `EditPanel` against the mockup:

| Area | Mockup | Now | Verdict |
| --- | --- | --- | --- |
| Container | One `card` (surface container, 12 px radius, padding) with `gap:20px` between groups | Bare `Column`, hand-placed `Spacer(8/16)` | Small, fix here |
| Section grouping | Each section (Topology control, Declared permissions, Permission check) is a group: title, dim hint, content, 8 px gap | Same content, but hints use body colour and the spacing is uneven | Small, fix here |
| Hint text | `dim` (on-surface-variant) | Default text colour | Small, fix here |
| Scrolling | Not drawn; panel fits | `verticalScroll`, no indicator | Fix here: scrollbar |
| Button row | Delete (left), spacer, "Check connection and permissions", Save (right) | Same order, label is "Check connection" | Small, fix here (label) |
| Profile card | Whole card clickable, selected card highlighted, pill on the right of the name | Card plus a separate "Select" text button, no highlight | Small, fix here (card clickable, `secondaryContainer` when selected, drop "Select") |
| Password | Masked | Shown in clear (no `visualTransformation`) | Small, fix here: `PasswordVisualTransformation` (no reveal icon: icons need a dependency, see Open points) |
| Fields | Two-column grid, ports as fields | One column, ports as text | Larger: `M1-12` |
| App bar, drawer, "New connection" in the header, icons (lock, verified_user), Material Symbols | Present | Absent (needs `material-icons-extended` or vector assets, a dependency decision) | Larger: `M1-12` |

So: the small fixes are in this task (they are all inside `EditPanel`/`ProfileCard`, no new dependency); the grid, app
bar, drawer and icons go to `docs/tasks/M1-12-connections-screen-layout-pass.md`. The pill has no lock icon until then.

### Scrollbar
- Use Compose Desktop `VerticalScrollbar(rememberScrollbarAdapter(scrollState))` next to the `verticalScroll` column,
  in a `Row`/`Box` so the bar sits on the right edge of the panel and the content has end padding for it. Make it always
  visible (the Desktop scrollbar is not auto-hiding by default).
- `VerticalScrollbar` is desktop-only API. `ui` has only `commonMain` and a `jvm()` target, and commonMain cannot see
  desktop-only declarations. The developer must verify this first. If it does not compile in commonMain: add a tiny
  `expect fun PanelScrollbar(scrollState: ScrollState, modifier: Modifier)` in commonMain and the `actual` in a new
  `ui/src/jvmMain`, and say so in the Status. Either way no new Gradle dependency (`compose.foundation` already has it).
- Add `Modifier.testTag("editPanelScrollbar")` to the bar (see UI tests).

## Behaviour contract (names the test developer follows)

### core (pure, no Compose, no JVM-only types)
File `core/.../connections/PermissionSummary.kt`:

```kotlin
enum class PillTone { OK, WARNING, LOCKED, NEUTRAL }          // OK green, WARNING tertiary, LOCKED red, NEUTRAL grey
data class PermissionPill(val text: String, val tone: PillTone)
data class PermissionBanner(val tone: PillTone, val headline: String, val detail: String?, val dimNote: String?)

sealed interface PermissionCheckStatus {                       // what the screen knows about one profile
    data object Checking : PermissionCheckStatus
    data class Done(val result: PermissionCheckResult) : PermissionCheckStatus
}                                                              // no entry in the map = not checked yet

fun permissionPill(status: PermissionCheckStatus?): PermissionPill
fun permissionBanner(profile: ConnectionProfile, status: PermissionCheckStatus?): PermissionBanner
```

Exact texts (the single source of truth; BDD steps and UI tests assert these strings):

| Status | Pill (text, tone) | Banner headline (tone) |
| --- | --- | --- |
| null | "Not checked", NEUTRAL | "Permission check has not run yet." (NEUTRAL) |
| Checking | "Checking...", NEUTRAL | "Checking permissions..." (NEUTRAL) |
| Done(Checked, unlocked) | "Can change topology", OK | "<user> can change topology on virtual host <vhost>." (OK); detail: publishing enabled -> "The broker confirmed it, so topology screens and migrations are unlocked for this connection. Test publishing is enabled." else "... Test publishing is disabled: no write permission." |
| Done(Checked, locked) | "Topology locked", LOCKED | "<user> cannot change topology on virtual host <vhost>." (LOCKED); detail: "The broker reports no configure permission, so topology screens open read-only. Streams can still be browsed." |
| Done(DeclaredByUser, unlocked) | "Can change topology (declared)", WARNING | "You declared that <user> <claim>." (WARNING); detail: "The broker could not confirm it and still enforces its own permissions." + a second line with the reason |
| Done(DeclaredByUser, locked) | "Topology locked (declared)", WARNING | same pattern |
| Done(Unreadable) | "Topology locked", LOCKED | `Unreadable.userMessage` (LOCKED), every cause including UNEXPECTED |

`<claim>` from the declaration booleans: both "can change topology and can publish test messages"; configure only "can
change topology"; write only "can publish test messages"; neither "has no topology or publishing rights". `<user>` and
`<vhost>` come from the profile. The reason line for DeclaredByUser is the new `Unreadable.reason` (the current
`userMessage` without its trailing "Topology stays locked ..." sentence; add the property and build `userMessage` as
`reason + suffix`, so existing tests stay green). The dim note "Your declaration is not used, because the broker
answered." is `dimNote` when status is `Done(Checked)` and `profile.declaredPermissions != null`, else null.

`ConnectionsState` gains `permissionChecks: Map<String, PermissionCheckStatus> = emptyMap()` (keyed by profile name).

`ConnectionsViewModel` (session stays a nullable constructor parameter; null means no permission features, as today):
- `suspend fun checkConnectionAndPermissions(name: String)`: runs `checkConnection(name)` (reachability, sets
  `lastCheckResult`), then sets `Checking`, calls `session.open(profile)`, sets `Done(result)`. Both always run, the
  permission check does not wait for a reachability success (an unreachable broker then shows Unreadable UNREACHABLE).
- `saveProfileChanges` additionally sets `Checking` before `session.reopen` and `Done` after, and removes the old name
  from `permissionChecks` on a rename. A failed save changes nothing.
- `confirmDelete` also calls `session.forget(name)` and drops the map entry.
- `selectConnection` / `startNewConnection` do not touch `permissionChecks` (a pill belongs to the profile, not to the
  selection), unlike the result banners.
- The ViewModel never runs a check on its own at start-up.

`desktopApp/CompositionRoot.kt`: `createConnectionsViewModel` builds one `ConnectionSession(ManagementApiPermissionChecker(),
secretStore)` with the same `secretStore` it gives the view model and passes it in. `ui` still imports nothing from
`data-rabbit`. The existing seam parameters stay (temp file, in-memory store); the real `~/.caerbannog` and keychain are
never touched by tests.

### ui
- `ConnectionsScreen`: edit Save for a saved profile does `scope.launch { viewModel.saveProfileChanges(...) }`; add
  stays `addConnection`. The button label is "Check connection and permissions" and calls
  `checkConnectionAndPermissions`. The button shows for saved profiles only (as the old one).
- `ProfileCard` shows `permissionPill(state.permissionChecks[profile.name])`; `EditPanel` shows
  `permissionBanner(...)` for the selected profile in place of `PermissionCheckPanel`, plus `dimNote` when present.
  Tone to colour: OK `primaryContainer`-free green is not in the M3 scheme, so use the mockup's `ok-container` colours
  as constants in one place (a small `PermissionColors` object in `ui`), WARNING amber `warn-container` (step 5; was tertiary, which is pink),
  LOCKED `errorContainer`/`onErrorContainer`, NEUTRAL `surfaceVariant`.
- Test tags: pill `permissionPill:<profileName>`, banner `permissionBanner`, scrollbar `editPanelScrollbar`.
- The ignored-declaration rule needs no new state: `dimNote` is computed from the saved profile.

## Acceptance
- BDD (`tests/bdd/features/02_permission_check.feature`; steps in `acceptance-tests/.../permissions/`; the tag
  `@needs-M1-07` is removed everywhere):
  - "A locked connection says so in the sidebar" (`@fake-broker`)
  - "A verified connection says so in the sidebar and in the edit panel" (`@fake-broker`)
  - "Declared permissions are shown as the user's own claim" (`@fake-broker`)
  - "A declaration is not used when the broker answers" (`@fake-broker`)
  - "Permissions that could not be read are shown as locked with the reason" (`@fake-broker`)
  - Split off as `@needs-M2` (stay pending, outside this task): "The topology screen explains why it is locked",
    "The topology screen says declared permissions are not confirmed".
  - These run in core, not through Compose: the new steps call `permissionPill(Done(session.permissions(name)))` and
    `permissionBanner(profile, ...)` and compare with the strings above. New steps to write: "the user has no configure
    permission on {string}" (not yet in `PermissionCheckSteps`), "the sidebar shows {string} next to {string}", "the edit
    panel says ...", "the edit panel tells me that permissions could not be read" (assert the banner headline equals
    `Unreadable.userMessage`). Existing steps are reused. Fakes only (`PermissionFakes`); no real broker, keychain or
    `~/.caerbannog`.
  - Runner filter in `RunPermissionCheckAcceptanceTest.kt` becomes `not @needs-M2 and not @broker`; update its KDoc.
  - `tests/bdd/README.md`: `@needs-M1-07` row retired (done in this brief's commit), `@needs-M2` row updated (done).
- Unit tests (core, `jvmTest`): `permissionPill` and `permissionBanner` table above, every row and every
  `UnreadableCause`; `Unreadable.userMessage` unchanged; ViewModel: `checkConnectionAndPermissions` sets Checking then
  Done (use a gated fake checker, no sleeps), runs the permission check even when reachability fails, `saveProfileChanges`
  success/failure/rename/delete effects on `permissionChecks`, no automatic check at construction.
- UI tests (`ConnectionsScreenUiTest`, fakes, no real keychain): per state (not checked, checking, verified, declared,
  declared-ignored dim line, locked/unreadable with its message, UNEXPECTED) the pill text by tag and the banner text;
  Save on a saved profile triggers a second check (fake checker call count); the button label; "Select" button gone and a
  click on the card selects; the scrollbar node with tag `editPanelScrollbar` exists. Update existing tests that look for
  "Check connection" or "Select".
- `desktopApp` `CompositionRootTest`: the view model it builds runs a check through the session (temp file, in-memory
  store, fake or unreachable endpoint; assert Unreadable, never a real broker).

## What stays manual (be honest)
- That the edit panel matches the mockup visually (spacing, colours, card, proportions) is a manual look by the user
  against `docs/ux/Main.dc.html`; no test can assert it. Record the check in the Status block after a `:desktopApp:run`.
- That the scrollbar is visible, sits at the right edge, drags and follows the content: manual. A test can only prove
  the tagged node exists and that the content scrolls (`performScrollTo` on the Save button, already used in the tests).
- Colour values of pills and banners: the tone to colour mapping is verified by reading the code and by eye; tests assert
  text and tag, not pixels.

## Design notes
- UI holds no broker code; it reads `state.permissionChecks` only. Wording lives in core so Cucumber can assert it.
- Reuse `ConnectionSession` unchanged; the declaration rule stays in `decideDeclared`.
- `PermissionCheckStatus.Checking` exists only in the view model state; the session never reports it.

## UX
`docs/ux/Main.dc.html` (updated: declared pill and banner, "Not checked" pill, dim line, unreadable banner, scrolling
note, `.banner.warn` in `m3.css`). `docs/ux/Topology.dc.html` is out of scope.

## Open points
1. When does the check run? Recommended: only on "Check connection and permissions" and after a successful Save of an
   existing profile (per the spec "again whenever the profile is saved with changes"); the M2 stream list will call
   `session.open` when it connects ("before the first connection"). Options: also on Select (touches the keychain and the
   network per click) or at start for all profiles (keychain prompts and N network calls at launch). Blocked on nothing;
   decide before M2. Until then pills read "Not checked" after start.
2. Pill for a declared "no configure" claim: this brief shows "Topology locked (declared)" in the warning colour so that
   the claim is always visibly a claim. Alternative: plain red "Topology locked". Recommend keep.
3. The pill describes the broker or declared permission only, not the self-constraint level (Read-only level can still
   show "Can change topology"). The effective permission is a topology-screen concern (M2/M5). Flag to the user.
4. Icons (lock, verified_user, visibility toggle) need `material-icons-extended` or vector assets: a new dependency,
   decided in `M1-12`. No new dependency in this task.

## Definition of done
Tests first and passing (core, ui, acceptance-tests, desktopApp), no unrelated changes, the Status block updated, the
manual look by the user recorded, `tests/bdd/README.md` and `docs/tasks/README.md` in sync.
