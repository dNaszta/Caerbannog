# M9-14b: Export polish (follow-ups of the M9-14 review)

Milestone: M9 (follow-up)    Depends on: M9-14 merged; the owner's answers to items 3 to 6 of its Owner review list    Size: S

## Goal
The export note and the export failure text say the right thing in the right amount, and the export checks only what it writes.

## Why
Findings F2 to F6 of the architect's review of M9-14 round 2 (`docs/tasks/M9-14-export-keeps-arguments-and-policies.md`, "Review").

## Scope
In (each only after the owner's wording where it has one):
- F2: one note entry per super stream instead of one per partition, and a cap of about ten entries ("and N more"); the leader locator named for what it is (owner wording, items 4 and 6).
- F3: a different plain sentence when the read worked but a queue argument or policy value cannot be written (owner wording, item 5); needs a typed failure from `TopologyExport` instead of null.
- F4: the completeness check applies only to the exchanges and bindings that are written; the round 2 test "also of a left-out queue" is changed with that reason.
- F5 (idea, only if wanted): a big-number `ArgumentValue` so a number above Long is kept exactly.
- F6 (idea): `isTransient` helper in `TopologyExport`, sort the new imports.
Out: any new read or write path.

## Acceptance
- BDD: extend `10_topology_migrations.feature` / "An export says what it leaves out" (@fake-broker) with a super stream of three partitions (one entry) and a long list (cap); one scenario for the new failure sentence. Tags `@m9-14`.
- Unit tests first in `MigrationsViewModelExportTest`.

## Design notes
`core` only (`TopologyExport`, `MigrationsViewModel`, `MigrationsScreenState`); a super stream is recognised by the exchange with `superStream = true` and its bindings, which the export already reads.

## UX
Same plain note line (`migrations-export-note`).

## MCP
None (UI-only; the MCP server has no export tool).

## Open points
Wording: owner items 3 to 6.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
