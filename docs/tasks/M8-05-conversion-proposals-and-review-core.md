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
Found in M8-04 step 1 (2026-11-09): there is NO real migration executor yet. The desktop app wires a no-op executor and in-memory file and history repositories (`desktopApp/.../Main.kt`, `MigrationsWindowContent`), `ManagementHttp` has no PUT or DELETE, and the scenarios "Applying a generated migration reads back every declared queue" and "Apply a migration to a real broker" need a real executor with read-back. The proposed task `M5-08-real-migration-execution` (see `M8-04`) must land before the apply half of this task and before the M8-11 gate; generation itself (this task) works on files and the fake executor.
Report size (M8-03 review, 2026-11-09): a definitions file with 60,000 classic queues gives about 240,000 findings (about 4 per classic queue). Keep review decisions in maps keyed by `Finding.id` (unique inside a report, duplicates get `#2`, `#3`), never one object per finding with its own state flows, and never build the whole report as one JSON string for the review.

P2, P3 and P10 are decided (2026-11-09). P17 (per-vhost export, owner 2026-11-09): the generator takes a `DefinitionsDocument` only (an `UnassignedDefinitions` draft from `M8-01` does not compile). For a document with `origin = SingleVirtualHost(name)` the access migration is not generated and the result says why ("This file is an export of one virtual host and holds no users, virtual hosts or permissions; the virtual host \"<name>\" must already exist on the 4.x broker"); the topology migration uses the assigned name as its virtual host. A scenario for this is added with `M8-05`. The review state and any log or `toString` never include a hash.

## Spike findings (M8-00, 2026-11-09)
Measured: a 4.x definitions import is NOT atomic (vhosts, users, permissions exist before a rejected policy) and silently skips transient and master-locator queues with 204, so the runner never feeds a generated file to the import endpoint; it applies action by action through the management API and reads each result back (exists, type and arguments equal) and a failed read-back stops the apply without recording the version. Review rule (P15, decided 2026-11-09): accepting a classic to quorum conversion requires the explicit tick "Ordering by priority may change"; for queues with `x-max-priority` the default proposal is keep classic (not replicated on 4.x), convert to quorum is the alternative; not a hard block. Optional generator choice from S-K: set the vhost `default_queue_type` to quorum instead of rewriting each queue.

## Spike S-N finding
S-N: the conversion proposal for a classic queue is a 4.x queue declaration on the NEW broker, which never reads the old index, so the version 1 blocker does not block the migration; the review shows it as informational for the conversion and as the reason to prefer the blue-green path. A v1 finding offers, besides the conversion, a "note: fix the source first if you upgrade it in place" with the three fixes. The generated migration never sets `x-queue-version`.

## Definition of done
Tests first and passing, existing migration suites unedited and green, spec updated.
