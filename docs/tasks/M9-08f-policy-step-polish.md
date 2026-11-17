# Policy step polish: four small findings of the M9-08 review

Milestone: M9 (follow-up)    Depends on: M9-08 (merged)    Size: XS

## Goal
Four minor findings of the step 5 review of `M9-08` are closed.

## Scope
1. `PlannerViewModel.readNamesAgain` stores `known = null` when the re-read fails, which also drops the broker part of the match line and the suggestions that the screen had a moment before. Keep the last good copy on a failed re-read (the check still goes on without the notes that need fresh names). Unit test in `PlannerViewModelPolicyCheckTest`: a failing second read leaves `known` as it was and the check still finishes.
2. `PolicyFormView`: `enabled = form.addSetting() != form` decides in a composable. Add `PolicyForm.canAddSetting()` in core (pure, unit-tested) and use it.
3. `PolicyFormView` draws its own words ("Policy", "Name", "Applies to names matching (pattern)", "Applies to", "Priority", "Settings", "Value") in the composable; move them to `PlannerTexts` as the other forms' words are, with no change of text or tag.
4. The overlap note with three or more binders names the first binder against each other one (decided in the M9-08 review, deviation 4); add one `PlanValidationPolicyTest` row that pins it (three binders, three notes naming the first with the second and the third) so a later change is deliberate.
Out: anything else.

## Acceptance
Unit and Compose tests as named; existing scenarios unchanged.

## MCP
None (internal).

## Definition of done
Tests first; no unrelated changes.
