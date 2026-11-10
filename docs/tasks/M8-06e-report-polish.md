# Migrate-from-3.x screen: report polish (follow-up of the M8-06a review)

Milestone: M8    Depends on: M8-06a    Size: S    Can run with or before M8-06b

## Goal
Five small things the M8-06a review found are fixed, none changes what the owner sees except item 2.

## Scope
1. `ReportViewBuilder.build` groups all findings on every chip or group toggle (a pass over up to 240,000 findings, plus the escaped queue-name list and its `copyText` string for the loss block). Group once per held analysis (cache in `HeldAnalysis` or a small `GroupIndex`) and filter and open on top of it; build `copyText` on demand. Test: toggling a chip on 240,000 findings does not regroup (a counting formatter or a counter on the grouping step), the view is equal to the old result.
2. The virtual host prompt: after `InvalidName` the field showed empty and the typed text is lost (the mockup state 4 shows the typed text next to the error). Keep the typed text in the field (the UI owns the field text; the state must not clear it on a new `NeedsVirtualHost`). Compose test: refuse, text still there, the error shows.
3. `"Report saved to ${path}"` is a literal in the view model: move it to `MigrateFromThreeTexts` and pass the path through `escapeForText` (a path from the dialog may hold a control character).
4. `reportText()` is synchronous (ruling 8 of M8-06a): make it `suspend` on the analysis dispatcher; the UI copies after the call and then calls `reportCopied()`; a cancelled call changes nothing.
5. Mockup consistency: the loading spinner icon, the loss names order (the analyzer's order, alphabetical) are documented in the mockup note; no behaviour change.
6. (Added by the M8-06b review) Every decision event (accept, skip, un-skip, replace, open or close the sheet) ends in `rebuild()`, which regroups all findings again through `ReportViewBuilder.build`: about 1 s per click at 240,000 findings. With the grouping cached (item 1) a decision event must only re-filter by the skipped set and recount; add a test that accepting one item on 240,000 findings does not run the grouping step again (counter) and that the Skipped group and pills still match. Raise the priority: with 06b merged this is felt on every click of a large file.
Out: anything about decisions (M8-06b).

## Acceptance
- BDD: the existing `@m8-06a` scenarios stay green and unedited; no new scenario (technical), except item 2 which adds one line to "A virtual host name that cannot be used is shown as an inline error": "And the field still holds what I typed".
- Unit and Compose tests as named in the scope.

## MCP
None (UI internals).

## Definition of done
Tests first and passing, `./gradlew test` green, no unrelated change.
