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
