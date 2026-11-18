# Migrations action row keeps every button at narrow widths and large fonts

Milestone: M9 (follow-up of M9-12)    Depends on: M9-12 merged    Size: S

## Goal
At 1000 px wide with font scale 1.5 the detail panel of a selected valid migration file on `MigrationsScreen` still shows every button of its action row: "Edit steps", "Edit file", "Validate again" and "Apply". At 800 px wide with font scale 1.5 the refusal text (`migrations:planNotOffered`) and "Copy into a new plan" are not clipped.

## Why
M9-12 review finding F2 (test report): "Edit steps" was added to a `Row` that does not wrap, so at 1000 px / 1.5 Apply (and Validate with Edit steps) leaves the row and Edit steps is squeezed to 8 px high. Apply is the main action of the screen; losing it is a defect. Same kind of problem as M9-08c and M8-14c.

## Scope
In: the action row in `MigrationDetailPanel` and the `PlanNotOffered` row of `ui/migrations/MigrationsScreen.kt`. Keep every `testTag` (`migrations:editStepsBtn`, `migrations:editFileBtn`, `migrations:applyBtn` and the others, `migrations:planNotOffered`, `migrations:copyIntoPlanBtn`). No change to texts, order of buttons or enabled rules.
Also (nit m1 of the M9-12 review): in `core/planner/PlannerViewModel.kt` `refusalOf`, import `MigrationFile` instead of the fully qualified name.
Out: any other screen; the wizard.

## Acceptance
- Un-@Disable the two tests of `MigrationsEditStepsNarrowExploratoryUiTest` and the two of `PlannerEditFileNarrowExploratoryUiTest` that carry the F2 note, and make them pass (they may be renamed, not weakened).
- All existing `MigrationsEditStepsUiTest` and `MigrationsScreen*` UI tests stay green; the screenshot of the wide layout does not change (the buttons stay in one row when there is room).

## Design notes
Proposal (the architect recommends, see Open points): a `FlowRow` (or a Row that wraps) so the buttons go to a second line when they do not fit, the primary button of the case ("Apply", or "Edit steps" on a pending file) first in reading order, and the refusal `Row` becomes a `Column` (text above, button below) under 900 dp. Do not shrink the font or hide a button. Composables stay thin: no new logic in `core`.

## UX
`docs/ux/Planner.dc.html` section 13 and the Migrations mockup show the wide layout only; the wrap is not drawn. Owner layout choice: see Open points.

## MCP
None (UI layout only).

## Open points
Owner layout choice (non-blocking, as M8-14c): (1) wrap onto a second line (recommended: nothing hidden, nothing new to learn); (2) move "Validate again" and "Edit file" into an overflow menu below a width (fewer buttons, but a hidden action); (3) leave it, since 1000 px at 1.5 is an extreme setting (rejected: Apply is lost). Build on (1) unless the owner answers otherwise.

## Definition of done
Tests first (the four un-disabled tests are the red ones), no unrelated change, a person checks the screen at 1000 px / 1.5 by eye.
