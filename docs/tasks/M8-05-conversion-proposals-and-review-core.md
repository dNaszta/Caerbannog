# Conversion proposals, review state and migration generation

Milestone: M8    Depends on: M8-03, M8-04, M8-12    Size: L

## Goal
A `ConversionReview` takes a report, lets each finding be accepted, changed or skipped, and generates one ordinary topology migration per source virtual host (P3) and one access migration (vhosts, users, permissions; P10) once everything is decided.

## Why
Spec decision 2 and "Review and the generated migration".

## Scope
In: `core` `ConversionReview` state machine (decision per finding: Undecided, Accepted, Changed(action), Skipped), `MigrationBuilder` from decisions to `MigrationFile` (next free version from the migrations folder, description, one file per vhost, action order: exchanges, queues, policies, bindings; access file: vhosts, users, permissions, topic permissions, with the lower version), validation through the existing `MigrationParser`, `core-testing` fakes, report keeps skipped items. Findings without a proposal can only be skipped or replaced with a user-written action.
Out: UI (`M8-06`), applying (existing runner).

## Acceptance
- BDD: `16` / "Accept a proposal and generate a migration" (@smoke), "Change a proposal before accepting it", "A skipped item stays out of the migration and in the report", "The migration cannot be generated while an item is undecided", "A finding without a proposal needs a manual decision", "The generated migration is an ordinary migration file", "One migration is generated per source virtual host", "Applying a generated migration follows the usual guardrails", "Applying a generated migration twice changes nothing the second time", "A migration file cannot contain a shovel action".
- Unit: generated file parses and its dry run on the fake lists every action; ordering rules; deterministic output.

## Design notes
Reuse `MigrationsViewModel` and the runner; do not copy apply code. State and events, no I/O. The builder never emits a delete of anything on the 4.x side (conversion only declares).

## UX
Review states in `M8-06`.

## MCP
No tool: the point is a human decision per item (spec MCP evaluation). A generated migration is an ordinary file, usable by the migration tools.

## Open points
P2, P3 and P10 are decided (2026-11-09). The review state and any log or `toString` never include a hash.

## Definition of done
Tests first and passing, existing migration suites unedited and green, spec updated.
