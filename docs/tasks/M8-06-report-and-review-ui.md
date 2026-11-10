# Migrate-from-3.x screen (umbrella): report, review, generated migration

Milestone: M8    Depends on: M8-01, M8-03, M8-04, M8-05, M8-05b, M5-08 (all merged); later parts M8-02 and M8-12    Size: umbrella, cut into M8-06a (M), M8-06b (M), M8-06c (S), M8-06d (S)

## Goal
The user opens "Migrate from 3.x", picks a definitions file (later a live 3.x profile), reads the report, reviews each item, exports the report and generates the migration, which then shows up on the migrations screen. Cut (architect, 2026-11-10, round 0) because the original L task depended on two unbuilt tasks (M8-02 live source, M8-12 access migration) and mixed four vertical slices:

| Id | Slice | Size | Depends on | Scenarios |
| --- | --- | --- | --- | --- |
| M8-06a | File source, `NeedsVirtualHost` prompt, report (message-loss block first, severity chips, grouped lazy list, `hash hidden` rows), export report, report survives a connection change | M | merged core (M8-01, M8-03) | 13 `@m8-06a` in `16` |
| M8-06b | Review per item (accept, choose alternative, tick, skip, replace), "N items need a decision", generate, save into the migrations folder, result | M | M8-06a, M8-05, M8-05b, M5-08 | 12 `@m8-06b` in `16` |
| M8-06c | Live 3.x source: source picker second option, 3.x pill and "Migrate from 3.x" in the connection popup, source locked, no-export-permission offer of the file import, switching keeps the report (live twin) | S | M8-02, M8-06a | scenarios of `16` "Analyse a live RabbitMQ 3.x connection", "A user who may not export definitions...", "The analysis survives switching..." (tag `@m8-06c`, retag when M8-02 is briefed again) |
| M8-06d | Access migration on the screen: access rows become decidable, the secret-file warning on saving or exporting the access migration, the empty-access note for a single-vhost file turns into the real note | S | M8-12, M8-06b | scenarios of `18` (tag `@m8-06d`) |

M8-06a and M8-06b need neither M8-02 nor M8-12 and give the owner a working file-based workflow first (the earliest slice). M8-06a alone is already useful: the report with the message-loss block and an export. Order: a, b, then c and d in any order once their dependencies land. M8-09 (forwarding UI) depends on M8-06b only for the screen shell.

## Why
Spec decisions 1 and 2 and "Review and the generated migration" in `docs/spec.md`; the owner's workflow end to end.

## UX
`docs/ux/Migrate3x.dc.html` (written 2026-11-10, round 0; the owner reviews it before step 2 of M8-06a). Open product decisions about it are in "Owner decisions" below.

## MCP
None (UI). The report is exposed by `M8-10`; a generated migration is an ordinary file that the read-only migration MCP tools list and dry-run (M8-05 decision).

## Owner decisions needed before step 2 of M8-06a (architect recommendation first)
1. Entry point: (A, recommended) a drawer item "Migrate from 3.x" plus a button on the Migrations screen, and in the connection popup of a 3.x connection (M8-06c); (B) button on the Migrations screen only; (C) only the 3.x connection popup. Reason for A: the file source needs no connection, and the Migrations screen is not offered on a 3.x connection.
2. Grouping of the report: (A, recommended) message-loss block first, then groups by rule, each group labelled with its severity, filter chips by severity; (B) groups by severity, rules inside; (C) flat list. Reason for A: a user decides per kind of problem; 240,000 findings need groups.
3. Groups closed by default except the message-loss block (A, recommended, keeps 60,000 queues fast), or open the first group (B).
4. Where the held report lives: (A, recommended) in memory until the app closes or the user chooses another file, survives connecting to another broker; (B) also saved to disk (it holds names of the user's topology; the hash is never in the model; a saved copy needs a retention rule, so not recommended now).
5. The wording of the fixed texts in the mockup (message-loss banner, "hash hidden", "Show next undecided item", the left-out notes): confirm or change.
6. Skipped items: shown in the list with a "Skipped by you" pill that can be changed (A, recommended), or hidden in a separate "Skipped" group.
7. Re-generating after the files were already saved: (A, recommended) new versions, old files untouched, the screen asks first ("Generate again"); (B) refuse a second generation from the same report; (C) delete and replace the old files (NOT recommended: the app never deletes migration files).
8. The Replace sheet edits migration-format JSON actions (A, recommended: the format already exists, parser reasons are shown as they are); (B) a form per action type (more UI, more tests; later idea).
9. Scope of M8-06a: confirm that the live source, the 3.x pill and source locking move to M8-06c, and the access migration parts to M8-06d (this cut).

## Pattern notes carried over from the old brief (they apply to M8-06a)
- Policy pattern finding `policy-pattern-unreadable` (Info, `about` = `invalid`, `unsupported` or `too-large`): the screen says the app could not evaluate a policy pattern and did not use it to decide which queues are mirrored, and asks the user to check by hand. G2: a lookahead such as `^(?!amq\.).*` is `unsupported`. G3: a literal of about 256 characters or more is `too-large`. G1: for names with non-ASCII characters the matcher counts UTF-16 units while the broker may count bytes (unverified). The UI never shows the pattern text as markup.
- Report size: up to about 240,000 findings for 60,000 queues: lazy list, groups with counts, never compose all findings.
- Names are plain text: no markup, control characters escaped (the text export already escapes them; the screen uses the same function).
- P17: `NeedsVirtualHost` prompt, empty field, no default `/`, trims before calling `assignVirtualHost`, `InvalidName` inline, nothing analysed until `Assigned`; the report says that the access migration is empty and why.
- P15 ordering tick, global warnings and the "unverified" marker belong to M8-06b (review sheet) and the report rows (marker).
- S-N / P16: the message-loss block is first, wording from `M8-00-spike-results.md` S-N; new broker plus Shovel recommended, the other two fixes secondary and by hand, "upgrade in place" never green. The block never promises that the Shovel exists yet: until M8-09 the text says "a Shovel" as a path, with no button.

## Status
- Step 1 (architect, confirm and re-cut the brief): DONE 2026-11-10, round 0, branch `m8-06-report-and-review-ui` (base 6491718). Changed: this umbrella, `M8-06a-...`, `M8-06b-...`, `docs/ux/Migrate3x.dc.html` (+ `canvas.json`), 25 scenarios in `16` (13 `@m8-06a`, 12 `@m8-06b`), `tests/bdd/README.md`, `docs/tasks/README.md`, spec "Screens and UX" row and the M8 status line. Contradictions found: (1) the old brief depended on M8-02 and M8-12, both unbuilt; (2) it asked for "source locked (3.x pill)" and "the access migration warning", both impossible without them; (3) it predated M8-05 and M8-05b (per-item decisions, `ReplaceInvalid`, `Generated`/`Incomplete`); (4) the mockup did not exist; (5) `Replace` needs a way to prefill and parse actions that the core does not offer yet (see M8-06b). Product decisions: the nine above, none decided.
