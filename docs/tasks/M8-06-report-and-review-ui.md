# Migrate-from-3.x screen (umbrella): report, review, generated migration

Milestone: M8    Depends on: M8-01, M8-03, M8-04, M8-05, M8-05b, M5-08 (all merged); later parts M8-02 and M8-12    Size: umbrella, cut into M8-06a (M), M8-06b (M), M8-06c (S), M8-06d (S)

## Goal
The user opens "Migrate from 3.x", picks a definitions file (later a live 3.x profile), reads the report, reviews each item, exports the report and generates the migration, which then shows up on the migrations screen. Cut (architect, 2026-11-10, round 0) because the original L task depended on two unbuilt tasks (M8-02 live source, M8-12 access migration) and mixed four vertical slices:

| Id | Slice | Size | Depends on | Scenarios |
| --- | --- | --- | --- | --- |
| M8-06a | File source, `NeedsVirtualHost` prompt, report (message-loss block first, severity chips, grouped lazy list, `hash hidden` rows), export report, report survives a connection change | M | merged core (M8-01, M8-03) | 13 `@m8-06a` in `16` |
| M8-06b | Review per item (accept, choose alternative, tick, skip, replace), "N items need a decision", generate, save into the migrations folder, result | M | M8-06a, M8-05, M8-05b, M5-08 | 13 `@m8-06b` in `16` |
| M8-06c | Live 3.x source: source picker second option, 3.x pill and "Migrate from 3.x" in the connection popup, source locked, no-export-permission offer of the file import, switching keeps the report (live twin) | S | M8-02, M8-06a | scenarios of `16` "Analyse a live RabbitMQ 3.x connection", "A user who may not export definitions...", "The analysis survives switching..." (tag `@m8-06c`, retag when M8-02 is briefed again) |
| M8-06d | Access migration on the screen: access rows become decidable, the secret-file warning on saving or exporting the access migration, the empty-access note for a single-vhost file turns into the real note | S | M8-12, M8-06b | scenarios of `18` (tag `@m8-06d`) |

M8-06a and M8-06b need neither M8-02 nor M8-12 and give the owner a working file-based workflow first (the earliest slice). M8-06a alone is already useful: the report with the message-loss block and an export. Order: a, b, then c and d in any order once their dependencies land. M8-09 (forwarding UI) depends on M8-06b only for the screen shell.

## Why
Spec decisions 1 and 2 and "Review and the generated migration" in `docs/spec.md`; the owner's workflow end to end.

## UX
`docs/ux/Migrate3x.dc.html` (written 2026-11-10, round 0; the owner reviews it before step 2 of M8-06a). All nine product decisions about it are decided (see "Owner decisions"); the owner still looks at the mockup itself.

## MCP
None (UI). The report is exposed by `M8-10`; a generated migration is an ordinary file that the read-only migration MCP tools list and dry-run (M8-05 decision).

## Owner decisions (decided 2026-11-10, owner, via the coordinator)
1. Entry point: A. A drawer item "Migrate from 3.x" plus a button on the Migrations screen, and later the 3.x connection popup (M8-06c). DECIDED.
2. Report grouping: A. Message-loss block first, then groups by rule labelled with severity, severity filter chips. DECIDED.
3. Groups closed by default except the message-loss block. DECIDED.
4. Held report: A. In memory, survives connecting to another broker, not saved to disk. DECIDED.
5. Fixed wording as in the mockup (including "larger than 64 MB", "hash hidden", "A replacement must not delete anything"). DECIDED.
6. Skipped items: B (not the architect's recommendation). A skipped item moves to a separate group "Skipped by you", with a way to un-skip or review it again. DECIDED. Consequences written into M8-06b: the group is last, closed by default, never filtered away by the severity chips, absent while nothing is skipped; a skipped item leaves its rule group and the "N items need a decision" count; chip counts and the summary keep counting the findings of the file (they describe the file, not the review); generate treats skipped as the core does (left out, listed in the result with their bindings).
7. Generating again after files were saved: A. New versions, old files untouched, the screen asks first ("Generate again"). DECIDED.
8. Replace editor: A. Migration-format JSON actions. DECIDED.
9. Cut: as proposed (06a to 06d). DECIDED.

## Pattern notes carried over from the old brief (they apply to M8-06a)
- Policy pattern finding `policy-pattern-unreadable` (Info, `about` = `invalid`, `unsupported` or `too-large`): the screen says the app could not evaluate a policy pattern and did not use it to decide which queues are mirrored, and asks the user to check by hand. G2: a lookahead such as `^(?!amq\.).*` is `unsupported`. G3: a literal of about 256 characters or more is `too-large`. G1: for names with non-ASCII characters the matcher counts UTF-16 units while the broker may count bytes (unverified). The UI never shows the pattern text as markup.
- Report size: up to about 240,000 findings for 60,000 queues: lazy list, groups with counts, never compose all findings.
- Names are plain text: no markup, control characters escaped (the text export already escapes them; the screen uses the same function).
- P17: `NeedsVirtualHost` prompt, empty field, no default `/`, trims before calling `assignVirtualHost`, `InvalidName` inline, nothing analysed until `Assigned`; the report says that the access migration is empty and why.
- P15 ordering tick, global warnings and the "unverified" marker belong to M8-06b (review sheet) and the report rows (marker).
- S-N / P16: the message-loss block is first, wording from `M8-00-spike-results.md` S-N; new broker plus Shovel recommended, the other two fixes secondary and by hand, "upgrade in place" never green. The block never promises that the Shovel exists yet: until M8-09 the text says "a Shovel" as a path, with no button.

## Status
- Step 1 (architect, confirm and re-cut the brief): DONE 2026-11-10, round 0, branch `m8-06-report-and-review-ui` (base 6491718). Changed: this umbrella, `M8-06a-...`, `M8-06b-...`, `docs/ux/Migrate3x.dc.html` (+ `canvas.json`), 25 scenarios in `16` (13 `@m8-06a`, 13 `@m8-06b`), `tests/bdd/README.md`, `docs/tasks/README.md`, spec "Screens and UX" row and the M8 status line. Contradictions found: (1) the old brief depended on M8-02 and M8-12, both unbuilt; (2) it asked for "source locked (3.x pill)" and "the access migration warning", both impossible without them; (3) it predated M8-05 and M8-05b (per-item decisions, `ReplaceInvalid`, `Generated`/`Incomplete`); (4) the mockup did not exist; (5) `Replace` needs a way to prefill and parse actions that the core does not offer yet (see M8-06b). Product decisions: the nine above, none decided.
- Owner decisions recorded (architect, 2026-11-10): all nine decided; item 6 (skipped items) is B, a separate "Skipped by you" group. Changed for it: mockup states 5 and 9, scenarios "Skip an item and change the decision later" (rewritten, with un-skip) and a new one for the group in `16`, the 06b design notes, the spec row. New open points: none for the owner; architect defaults in M8-06b open point 5.
