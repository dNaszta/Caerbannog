# Migrations detail panel is about 100 px wide at 800 px window width

Milestone: M2 (UI layout, product defect found by test)    Depends on: M2-21 (merged)    Size: S/M    **Needs a product decision (owner) before the developer starts**

## Goal
At a window 800 px wide the Migrations detail panel (file steps, action buttons, the "not offered" refusal with its copy button) is usable: every button keeps its label width (spec "Layout promises (M2-21)", promise 2) and the `@Disabled` test `MigrationsEditStepsNarrowExploratoryUiTest` "at 800 wide and font scale 1_5 ..." is enabled and green.

## Why
Found by M2-21 (test developer, senior developer). At 800 wide the navigation drawer (about 304 px) and the file list (340 dp, `MigrationsScreen.kt`) leave the detail panel about 100 px. Even "Edit file" gets 76 px and wraps to 4 lines. A `FlowRow` for the refusal row was tried and reverted: it cannot help when the whole panel is 100 px. The window opens at 1280 x 800 (`desktopApp/.../Main.kt`, `rememberWindowState`) and has no minimum size, so 800 wide is reachable only by the user resizing. Promise 2 says "at every window width", so today the spec and the layout disagree at 800.

## Decision needed (owner)
This changes what the user sees on a narrow window, so I do not decide it. Options:

| | Option | Cost | Effect |
| --- | --- | --- | --- |
| A (recommended) | Below a width the Migrations page stacks: file list on top (capped height, scrolls inside), detail panel below at full content width. Threshold to be measured, start at content width (window minus drawer) under 760 dp. | S/M, local to `MigrationsScreen.kt` | Keeps promise 2 as written for any width; no change at 1280 or at 1000. The detail panel then has about 496 px at 800, enough for four buttons wrapped in a `FlowRow`. The plan page already stacks in the same spirit (`StackedPage`). |
| B | Set a minimum window size (for example 1000 x 600) in `Main.kt` and change promise 2 to "at every width the window allows". Test 2 is then replaced by one at the minimum width. | S, but a global behaviour change on all screens and on Linux window managers | Simplest, but takes away a window size users may want (tiling window managers on Fedora). |
| C | Collapse the navigation drawer to a rail (about 80 dp) below about 900 px. | M/L, touches every screen and the UX files | Gives the detail panel about 380 px at 800; it is the Material 3 guidance for medium windows, but it is a redesign and out of proportion for one panel. |
| D | Shrink the file list (240 dp minimum) at narrow widths. | S | Detail panel only about 256 px at 800: still too narrow for promise 2 at font scale 1.5. Not enough alone. |

Recommendation: A. It is local, keeps the promise honest, and costs the least visible change (nothing at 1280 or 1000). If the owner prefers B, say so and I will amend the spec line instead of the layout. Blocked until decided: the implementation of this brief (nothing else is blocked; M2-21 is complete without it and CI is green with the test still `@Disabled`, reason pointing here).

## Scope
In (option A): a stacked layout of the Migrations page below the measured threshold; keep every `testTag`; the list keeps selection and scroll; enable the 800 wide test with `assertIntact` and add it to `WideFontLayoutGuardUiTest` (pinned font) as test 7.
Out: the navigation drawer, other screens, wording, moving a control into a menu.

## Acceptance
- BDD: none (Compose UI tests, as in M2-21).
- `MigrationsEditStepsNarrowExploratoryUiTest` "at 800 wide and font scale 1_5 the applied text and the copy button are on screen": `@Disabled` removed; refusal text, copy button and Edit file pass `assertIntact(..., scroll = true)`.
- Same check at 800 and 1000 wide, font scale 1 and 1.5, with the pinned font, in the guard class.
- 1280 x 800 and 1000 x 800 unchanged: `MigrationsEditStepsUiTest`, `MigrationsAppBarUiTest`, `WideFontLayoutGuardUiTest` stay green; the list/detail side by side layout is the same at those widths (a test asserts list left of detail at 1280).
- Mac and `ubuntu:24.04` with `xvfb-run -a ./gradlew :ui:jvmTest`.

## Design notes
`ui` only. The decision of "stacked or side by side" is a pure function of the available width in `core` (or a small private function with a unit test), not inline in the composable. The list in stacked mode gets a maximum height so the detail panel stays reachable; the page scrolls as a whole only if needed (see the Compose pitfall in `CLAUDE.md`: put the scroll where the content overflows).

## UX
`docs/ux/Migrations.dc.html` is designed at 1280. No narrow mockup exists; if the owner picks A, the developer attaches a screenshot at 800 for the owner's eye (stacked list height, spacing).

## MCP
None (UI-only layout). No new capability.

## Open points
- The threshold (760 dp is a starting guess) is measured with DejaVu Sans pinned at font scale 1.5.
- Does the Connections screen (`ConnectionsFrame.kt`, also a 340 dp list) have the same problem at 800? Not measured; check in passing, report, fix only under its own brief.

## Definition of done
Owner decision recorded here, tests first (the 800 case red in the guard before the change), no unrelated changes, README line and spec promise 2 wording kept in sync with the decision.

## Status
- Briefed (architect, 2026-11-23). Waiting for the owner's decision on A, B, C or D.
- Owner decision (2026-11-23): option A. Below a measured content width the Migrations page stacks (file list on top with a capped height, detail panel below at full width), local to `MigrationsScreen.kt`; nothing changes at 1000 or 1280. Start threshold 760 dp (see the open conflict below).
- Tests first (test developer, 2026-11-23, branch `M2-21b-migrations-narrow-detail-panel`): `@Disabled` removed from `MigrationsEditStepsNarrowExploratoryUiTest` test 2 (now behind a 304 dp drawer stand-in, `DrawerStandIn.kt`, `assertIntact` on both buttons); `WideFontLayoutGuardUiTest` +5 tests (800 wide: scale 1 and 1.5 pinned, scale 1.5 machine font; 1000 wide without drawer: scale 1 and 1.5); new `MigrationsStackedLayoutUiTest` (side by side at 1280 and at 1000, stacked at 800, capped list, last file reachable). Run: 18 tests, 7 red, all at 800 and for the right reason (detail panel zero width, buttons 0 px with labels wrapped to 8 to 13 lines, header not below the list); the 11 at 1000 and 1280 are green. Open for the architect: (1) the real window at 1000 gives the screen 696 dp, under 760, so "start at 760" stacks a 1000 window while the brief says 1000 is unchanged; the threshold must be below 696 (and above 496), for example 600. (2) At 1000 behind the drawer (side by side, detail 284 px) scrolling to Validate makes `waitForIdle` never return (a hang, not a failure), so the guard has no such case; the developer should check this case once the threshold is set. (3) The list cap is assumed: detail header starts at or above 600 of 800 with 30 files.

- Follow-ups of the review (senior developer, 2026-11-23): imports sorted; `PLAN_NOT_OFFERED_MIN_TEXT_WIDTH` named; `@Timeout(60)` on the exploratory class, the bounded worker in `MigrationsHangReproUiTest` documented as the real protection; stale 304/696 wording fixed, `WithDrawerStandIn` kept as a documented no-op (smaller than touching every call site); list scroll state hoisted above the layout switch (`listScroll`, the page scroll was already above it) with a UI test across 800 -> 952 -> 800; test at window 951/952; refusal side by side at 1000, scale 1.5 added as `@Disabled` because it is RED: the "Copy into a new plan" label wraps to 2 lines (276 x 76 px) in the 308 px detail panel. That needs an architect decision (shorter label, or stack at larger widths for big fonts). Mac `:ui:jvmTest`: 1366 tests, 0 failed, 1 skipped.

## Architect decisions (2026-11-23, settles the three open items of the test developer)

Facts: the screen is given window minus the 304 dp drawer; the page has 24 dp padding on both sides; the list is 340 dp, the gap 24 dp. Side by side the detail panel gets `content - 412` dp: 284 at a 1000 window (696), 84 at 800 (496; the tests saw about 100 px), 564 at 1280 (976). `ConnectionsContent` already stacks under 844 dp, so this page was the odd one out.

1. **Threshold: 672 dp** of width given to the screen (`340 + 24 + 48 + 260`, where 260 dp is the smallest detail panel we accept side by side). Under 672 it stacks, from 672 it is side by side. So 1280 (976) and 1000 (696) are side by side, 800 (496) stacks; 1000 keeps 24 dp of margin. Put it in one named constant plus a pure function `migrationsLayoutFor(width: Dp)` (sealed result `SideBySide` / `Stacked`) with a unit test at 671, 672, 696, 496, 976. Replace the 760 in this brief and in the tests' comments.
   - Is 284 dp at 1000 acceptable? The action row is a `FlowRow` and promise 2 allows wrapping, so yes if every button keeps its label width and is on screen (`assertIntact`, scroll allowed) at scale 1 and 1.5. This is to be proven, not assumed: the 1000 guard tests must now run **behind the drawer** (`drawer = true`, the real case; today they use `drawer = false` and prove nothing about the real window). The "1000 unchanged" decision of the owner stands. **Fallback, needs the owner's word:** if with the drawer at 1000 the pinned font at scale 1.5 cannot be made intact, the developer stops and reports; the only fix is to raise the constant to 720 so a 1000 window stacks too, a visible change at 1000 that I will not decide alone.
   - Tests that change: `MigrationsStackedLayoutUiTest` "at 1000 wide ..." becomes `show(drawer = true)` (side by side, list card ends left of detail header); the doc comment about 760 and the "architect to settle" text go. `WideFontLayoutGuardUiTest` 1000 tests (scale 1 and 1.5) use the drawer and their names drop "without a drawer". Add one test at 671/672 boundaries only as the pure unit test, not as UI.
2. **Stacked list height:** `heightIn(max = 240.dp)`, and never more than 35 percent of the page height, but at least 160 dp (about two cards), so a short window scrolls the page as a whole rather than hiding the list. The list scrolls inside that box (keeps selection and scroll state). The detail panel below takes the full content width and its natural height (minimum 360 dp), no `weight` or `fillMaxHeight` inside the scroll; the page `Column` gets `verticalScroll` plus a scrollbar only in stacked mode (CLAUDE.md pitfall). The assumed test limit stands: with 30 files at 800 x 800 the detail header top is at or below the list and at or above y = 600. Above the list there are about 250 px (app bar, advice, folder row), so 240 dp leaves about 490 px; the limit has margin.
3. **The hang:** treat it as a defect until shown otherwise. Step 1, before any layout change: reproduce with a bounded test (a 1000 x 800 window, drawer on, pinned font, scale 1, `performScrollTo` on `migrations:validateBtn`) inside a wrapper that fails after 60 s with a stack dump (for example a `Thread` + `join(60_000)` that fails the test, or JUnit `assertTimeoutPreemptively`); never an unbounded `waitForIdle`. Step 2, classify: if the same screen hangs with a real `Window` and the same constraints, or the loop is in measure/layout (jstack shows `LayoutNode`/`FlowRow`/`weight` measuring repeatedly), it is a **product layout loop** (suspects: `Spacer(weight(1f))` inside a `FlowRow` in a panel narrower than its buttons, `fillMaxHeight` plus `verticalScroll`) and the fix belongs in this task; add the bounded test as a regression. If it is only the test clock (`mainClock` with `autoAdvance` and an endless animation such as an indeterminate progress or scroll animation), it is a test artefact: bound it (`mainClock.autoAdvance = false` and explicit `advanceTimeBy`, or `waitUntil(timeoutMillis)`), say so in the test comment. Either way every guard and UI test of this task runs with a 60 s limit so a failure is red, not a 15 minute timeout. Report which class it was.
4. **Connections screen:** read from `ConnectionsFrame.kt` only. It does not have the problem: `ConnectionsContent` measures its width and stacks (list `fillMaxWidth().heightIn(max = 220.dp)`, edit panel full width) whenever it is under `340 + 24 + 480 = 844` dp, so at 800 (496 dp) and at 1000 (696 dp) it is stacked with the form at full width. No follow-up brief. Not run on a screen, so unverified by eye. Idea only: its 844 dp rule is the same pattern as decision 1; a later refactor could share one helper (not now).

## Final acceptance (replaces the Acceptance list where they differ)
- Bounded reproduction of the 1000 behind-the-drawer hang first (decision 3), class reported in Status.
- Pure unit test of `migrationsLayoutFor` (671 stacked, 672 side by side, 496, 696, 976).
- `MigrationsStackedLayoutUiTest`: 1280 and 1000 (both behind the drawer) side by side; 800 stacked, detail as wide as the content, detail header between the list card and y = 600, last of 30 files reachable by scrolling the list.
- `WideFontLayoutGuardUiTest`: 800 and 1000, scale 1 and 1.5, pinned font, all behind the drawer; 800 at 1.5 with the machine font; `MigrationsEditStepsNarrowExploratoryUiTest` test 2 enabled.
- `MigrationsEditStepsUiTest`, `MigrationsAppBarUiTest` and the rest of `:ui:jvmTest` stay green; Mac and `ubuntu:24.04` with `xvfb-run -a ./gradlew :ui:jvmTest`; an 800 screenshot attached for the owner's eye.
- What the user sees: only the Migrations page under 672 dp changes (stacked). Nothing at 1000 or 1280, no product choice left open, except the fallback in decision 1 that would stack 1000.

## Implementation (senior developer, 2026-11-23)
- **The hang, step 1 and 2.** Reproduced bounded in `MigrationsHangReproUiTest` (worker thread, 60 s, thread dump): 1000 x 800 behind the stand-in, `performScrollTo` on Validate; the worker sat in `waitForIdle` with the render loop sending frames, the root layout ran once (counted), no measure loop. The cause is the stand-in: `MigrationsScreen` already draws `AppFrameLayout` with its own **280 dp** drawer, so the screen alone is what the app shows; the stand-in added another 304 dp, the page was 416 dp, the four buttons were `Rect(0,0,0,0)` and `performScrollTo` on a zero-size node never settles. **Class: test artefact (wrong stand-in), not a product layout loop.** The screen alone at 1000 scrolls to Validate at once (buttons 684..941, panel 308 dp). The bounded test stays as a regression on the screen alone.
- **Fact that changes two numbers of the architect's decisions:** the drawer is 280 dp, not 304 (304 = 280 + 24 page padding, the x of the first card). The page content gets window minus 280: 1280 gives 1000 dp, 1000 gives 720 dp (detail 308 dp), 800 gives 520 dp. The constant 672 stands (it is measured on the content width inside the frame, `BoxWithConstraints`); it now means a window of 952 dp and up is side by side, so 1000 has 48 dp more room than the brief assumed and the fallback (raise to 720) is not needed. `DrawerStandIn.kt` is now a pass-through (the tests keep reading "behind the drawer") and `DRAWER_WIDTH_DP` is 280.
- **Code:** `MigrationsLayout.kt` (`migrationsLayoutFor`, `stackedListMaxHeight`, constants), `MigrationsScreen.kt` (stacked branch: list `heightIn(max)` scrolling inside, detail full width, min 360 dp, no weight or fillMaxHeight below the page scroll, page `verticalScroll` plus a scrollbar only when stacked; the refusal row is a `FlowRow` so the copy button drops under the text at scale 1.5; new tags `migrationDetail:panel`, `migrations:fileList`, `migrations:pageScrollbar`).
- **Test changes outside my brief (flagged):** `DrawerStandIn.kt` as above; `MigrationsStackedLayoutUiTest` read the detail width from the header text (a text of its own width), now from `migrationDetail:panel`; `@Timeout(60)` on the new classes; 1000 guard names lose "without a drawer"; new `MigrationsLayoutTest` (671, 672, 496, 696, 976 and the list cap).
- **Connections screen:** not touched (architect: stacks under 844 dp).


## Architect review (2026-11-23)

**Correction of facts (architect error, found by the senior developer, verified in `ui/.../appframe/AppFrame.kt:377`):** the app frame draws its own drawer of 280 dp, not 304 (304 = 280 + 24 dp page padding, the x of the first card). Every figure above that says "304", "696", "976" or "496" is superseded by this table. The detail panel side by side is content - 412 dp.

| Window | Page content (window - 280) | Layout | Detail panel |
| --- | --- | --- | --- |
| 800 | 520 | stacked (under 672) | 520 - 48 = 472 wide, natural height |
| 951 | 671 | stacked | |
| 952 | 672 | side by side | 260 |
| 1000 | 720 | side by side | 308 |
| 1280 | 1000 | side by side | 588 |

The threshold constant 672 stands; it now means "window 952 dp and wider is side by side". The fallback in decision 1 (raise to 720) is not needed and is void. The "hang" in the test developer's open point 2 was a test artefact (the stand-in doubled the drawer, leaving a 416 dp page with zero-size buttons); decision 3 is closed with class "test artefact", the bounded repro stays as a regression on the screen alone. `DrawerStandIn` is a pass-through; the tests keep their names.

## Architect decision on the red refusal test and final review (2026-11-23, after b6676745)

Finding: at window 1000, font scale 1.5, pinned font, the page is side by side with a detail panel of 308 dp, so the inner width is about 276 px. "Copy into a new plan" needs about 300 px at 1.5 (label grows with the font, icon 18 dp and paddings do not), so even alone on its row it wraps to 2 lines (276 x 76 px). The `FlowRow` cannot help: the button is wider than the whole row. The test is right and promise 2 is violated; this is not a defect of `assertIntact`.

Options weighed: (a) shorten the label: wording, owner's call, rejected as unnecessary. (b) relax `assertIntact` for this button: rejected, it contradicts promise 2 ("never wraps its label") and makes the promise depend on the button. (d) own row, smaller text: the row is already the button's own line; a smaller style is a visual change and does not fix a 24 px shortfall cleanly. (c) stack at larger widths for big fonts: chosen, in its font-scale-aware form.

**Decision: the stack threshold follows the font scale.** `migrationsLayoutFor(width: Dp, fontScale: Float)`; `minDetail(fontScale) = 260 dp + max(fontScale - 1, 0) x 180 dp` (starting value; measured 160 dp, see the Status); threshold = `340 + 24 + 48 + minDetail`. Scale 1: unchanged (672 dp). Scale 1.5: 762 dp, windows under 1042 stack. The 180 dp factor is a starting value: the developer measures the copy button at 1.5 with the pinned font (need about 300 px inner, so detail at least about 332 dp plus a margin) and sets the smallest round factor that leaves at least 8 px spare. Read `LocalDensity.current.fontScale` inside the `BoxWithConstraints`. Unit test: 671/672 at scale 1, and 761/762 at 1.5, plus 1.0 and 1.2. Enable the `@Disabled` test (change its window to 1000 stacked and keep it as "refusal keeps its buttons intact", and add the same case side by side at window 1100, scale 1.5, where the detail is 1100 - 280 - 412 = 408 dp). The spec promise 4 is amended (docs/spec.md, same line).

Owner: not needed for the decision itself. It changes nothing at font scale 1 (what the owner approved in decision A: 1000 and 1280 unchanged), no wording moves, and it is the only way to keep promise 2, which the owner has. FYI to tell the owner: at font scale 1.5 a window under 1042 px now stacks the Migrations page. If the owner would rather shorten the label, that is a one-line swap later and the threshold factor can then drop.

Review of the follow-up commit (items 1 to 7: sorted imports, named constant, @Timeout and bounded-worker note, 304/696 wording, no-op `WithDrawerStandIn`, hoisted `listScroll` with the 800 -> 952 -> 800 test, 951/952 boundary test): all fine. Findings: (minor) the new imports in `MigrationsStackedLayoutUiTest` are appended after `kotlin.test`, sort them; (idea) delete `WithDrawerStandIn` and `DrawerStandIn.kt` in a later tidy-up, a no-op wrapper is noise; the scroll test only checks the two stacked states, which is what the promise needs (the hoist is above the switch, correct). Not run by me: tests (no production code allowed, no Gradle run).

Verdict: **Changes required** (one blocker, small): implement the font-scale-aware threshold and enable the red test; then Accepted. The rest of the branch is accepted.

## Status: font-scale-aware threshold (senior developer, 2026-11-23)
- `migrationsLayoutFor(width, fontScale)` with `migrationsMinDetail(fontScale) = 260 dp + max(scale - 1, 0) x 160 dp` and `migrationsStackBelow(scale) = 340 + 24 + 48 + minDetail`; the screen reads `LocalDensity.current.fontScale`. Scale 1: 672 dp, unchanged.
- **Measured factor: 160 dp, not the 180 starting value.** Pinned font, scale 1.5, window 1200: `copyIntoPlanBtn` is 297 x 46 px (one line), the detail panel's inner width is detail - 32. The row needs 297 + 8 = 305 px, so the detail panel needs at least 337 dp: factor 150 gives 335 (6 px spare, not enough), 160 gives 340 (inner 308, 11 px spare). Threshold at 1.5 is therefore 752 dp (windows under 1032 stack), not 762 (1042). At 1.2 it is 704 dp.
- Tests: the `@Disabled` test is enabled as three cases through one helper: window 1000 stacked, window 1032 side by side at the threshold (detail 340 dp, the tightest fit), window 1100 side by side (detail 408 dp). `MigrationsLayoutTest`: 671/672 at scale 1 and 1.0, 751/752 at 1.5, 703/704 at 1.2, scale 0.8 does not lower the threshold. Imports of `MigrationsStackedLayoutUiTest` sorted.
- Nothing changed at scale 1: 1000 and 1280 side by side, 800 stacked (the existing tests for them are unchanged and green).
- Run: `:ui:jvmTest` 1373 tests, 0 failed, 0 skipped on the Mac and in `ubuntu:24.04` under `xvfb-run -a` (copy of the worktree, aarch64). Not run: `:desktopApp:test`, `:acceptance-tests:test`, broker tests, CI.
