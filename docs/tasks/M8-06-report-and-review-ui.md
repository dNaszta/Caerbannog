# Migrate-from-3.x screen: source, report, review, generated migration

Milestone: M8    Depends on: M8-02, M8-05 (M8-01 and M8-03 for the file path)    Size: L

## Goal
The user opens "Migrate from 3.x", picks a live 3.x profile or a definitions file, reads the report, reviews each item, exports the report and generates the migration, which then shows up on the migrations screen.

## Why
Spec decisions 1 and 2; the owner's workflow end to end.

## Scope
In: `MigrateFromThreeViewModel` (state in, events out) in `core`, Compose screen in `ui` with states empty, loading, error, source locked (3.x pill), report with severity filter chips and counts, per-item accept/change/skip with an edit sheet, "N items need a decision", export report (JSON, copy text), generate migration, navigation entry, composition-root wiring, `testTag`s. Source switching keeps the report (see `16` "The analysis survives switching to the 4.x connection").
Out: forwarding (`M8-09`).

## Acceptance
- BDD: all `16` scenarios of `M8-01` to `M8-05` run through the view model; "Export the report"; "Items that need no change are not listed as problems".
- Compose UI tests: states above, keyboard reachability, copyable values, long names do not break layout; a user row shows "hash hidden" and no test, screenshot or semantics node contains a hash; saving or exporting the access migration shows the secret-file warning.
- Opt-in screenshots for by-eye comparison.

## Design notes
UI free of broker code; severity and wording decided in `core`. Respect existing conventions (CLAUDE.md: thin composables, scrollbar for overflow). Shared components from M2-08f2.

## UX
No mockup exists. The architect produces `docs/ux/Migrate3x.dc.html` (source picker, report table, review sheet) and the owner reviews it BEFORE step 2 of this task. Blocked until then.

## MCP
None (UI). The report is exposed by `M8-10`.

## Open points
Mockup. Where the entry lives (recommendation: a button on the Migrations screen and on a 3.x connection's pill).

## Definition of done
Tests first and passing, `./gradlew test` green, screenshots looked at, spec screens table updated.
