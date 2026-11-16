# M9-14b: Export polish (follow-ups of the M9-14 review)

Milestone: M9 (follow-up)    Depends on: M9-14 merged (on main since 342b0a7)    Size: S (core, plus the two strict parsers in `data-rabbit`; no `ui` line changes)

## Goal
The export note says the right thing in the right amount, the export failure text says the right reason, and the export checks only what it writes.

## Why
Findings F2 to F6 of the architect's review of M9-14 round 2 (`docs/tasks/M9-14-export-keeps-arguments-and-policies.md`, "Review"), and the owner's answers to its Owner review list.

## Owner decisions (2026-11-16, message to the M9-14 Owner review list, items 1 to 7)
- Items 1 and 2: "items 1 to 5 yes". Streams and operator policies are in the export (already built in M9-14).
- Item 3: yes, kept for now: "Left out: retention of stream audit; exclusive queue session-1; transient queue scratch (cannot be applied)".
- Item 4: yes: one entry per super stream, for example "retention of super stream orders (3 partitions)", and a cap of about ten entries ("and N more").
- Item 5: yes: a second plain failure sentence, "The topology was read, but a setting in it cannot be written to a migration file.", for a setting the model refuses.
- Item 6: "naming only what is present". The leader locator is NOT called retention: the note names only the settings actually present on the stream.
- Item 7: "yes": the note stays a plain small line, no icon, no alert styling. No `ui` change.

## Scope
In:
- F2 (items 4 and 6): one entry per stream, naming the present settings; one entry per super stream with its partition count; at most 10 entries, then "and N more". Wording: "Decided wording" below.
- F3 (item 5): `TopologyExport.actions` returns a typed outcome, not null: `Written(result)`, or `NotWritable` (the broker was read, a setting cannot be written), and the view model shows the new sentence. A read that fails stays "The topology could not be read" (`TOPOLOGY_NOT_READ`); the new text is a second constant next to it. No file in either case.
- F4: the completeness check applies only to what is written: exchanges and bindings that the export writes (not `amq.*` exchanges, not bindings of a left-out queue, not the nameless default exchange), queues that are exported, and all policies and operator policies. Round 2 test to change: `MigrationsViewModelExportExploratoryTest` "an incomplete binding of a left-out queue still fails, nothing about the broker is guessed, and a later export of a complete topology works". Reason: round 2 pinned the over-strict behaviour (finding F4); it now says that an incomplete binding of a left-out queue exports fine (binding and queue both left out, the queue in the note) and that an incomplete binding that IS written still fails loud. The test developer renames it and keeps the "later export works" half.
- F6 (trivial, in): an `isTransient` helper in `TopologyExport` (the note repeats the transient rule of `isConnectionQueue`), imports of `StreamSource.kt` and `ManagementJson.kt` sorted.
Out:
- F5, the big-number `ArgumentValue`: NOT in scope. It is a model change for every migration (`ArgumentValue`, JSON reader and writer, executor), not trivial; a queue or policy limit is a Long in practice. Stays a known limit; a separate task only if a real broker shows one.
- Any new read or write path. The export stays GET only.

### Where F3 touches `data-rabbit` (the brief of round 0 said "core only"; the real code needs one more place)
`parseQueueDeclarations` and `parsePolicies` (`ManagementJson.kt`) are strict: a queue argument or policy value the model refuses makes the whole page `CatalogResult.Unexpected`, which `readTopologyExport` cannot tell from a real read failure. Decision: make them tolerant the way `parseExchanges` and `parseBindingList` are since M9-14 round 2 (finding F1): a refused value is left out of the typed map and the row says `typedArgumentsComplete = false` (`QueueDeclaration`) or `definitionComplete = false` (`PolicyRow`, default true). A refused JSON structure (not an object, a missing required field) stays `Unexpected`. Only `TopologyExport` reads the flags. Rejected: a new `CatalogResult` variant (every exhaustive `when` over the sealed result changes). The strict-parser tests in `ManagementExportReadsTest` that pin the old behaviour change with this reason; the test developer names them.

## Decided wording (constants in `TopologyExport`, one place, one-line edits)
Items 3 to 6 fix the style; the exact words per case are the architect's proposal under the owner's rule ("only what is present"). Entries are joined with "; ", the line starts with "Left out: " (all as today).

| Case | Entry |
| --- | --- |
| stream with retention (`x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`) | `retention of stream audit` |
| stream with only the leader locator (`x-queue-leader-locator`) | `leader locator of stream audit` |
| stream with both | `retention and leader locator of stream audit` |
| stream with any other argument | the word `other settings`; words are joined "a, b and c" (for example `retention, leader locator and other settings of stream audit`) |
| super stream (the exchange with `superStream = true`, its partitions are the streams its bindings point at) | `retention of super stream orders (3 partitions)`; the words are the union over the partitions; one partition says `(1 partition)`; the count is all partitions, also those without settings |
| exclusive queue | `exclusive queue session-1` (as today) |
| transient queue | `transient queue scratch (cannot be applied)` (as today) |
| more than 10 entries | the first 10, then `and 2 more` as the last entry (`...; exclusive queue conn-10; and 2 more`) |
| a super stream without any partition setting | no entry |

Measured fact to know: the RabbitMQ CLI gives every partition of a super stream `x-queue-leader-locator=balanced` (spike results, `docs/tasks/M9-14-spike-results.md`), so every real super stream export says `leader locator of super stream X (N partitions)`. That is true (the file cannot carry it) and is the consequence of the owner's rule; item 3 below asks whether the owner wants it.

Constants: `NOTE_PREFIX`, `NOTE_SEPARATOR`, `MAX_NOTE_ENTRIES = 10`, `RETENTION_WORD`, `LEADER_LOCATOR_WORD`, `OTHER_SETTINGS_WORD`, functions `streamNote(words, name)`, `superStreamNote(words, name, partitions)`, `moreNote(count)`, plus the existing `exclusiveQueueNote`, `transientQueueNote`. The failure sentence is `MigrationsViewModel.TOPOLOGY_NOT_WRITABLE = "The topology was read, but a setting in it cannot be written to a migration file."`.

## Acceptance
- BDD (`tests/bdd/features/10_topology_migrations.feature`, tags `@m9-14 @m9-14b @fake-broker`, written in step 1; glue is the test developer's):
  - "An export says what it leaves out" (existing, `@m9-14`): unchanged and still valid ("retention of audit and the exclusive queue session-1").
  - "A stream with only a leader locator is not called retention".
  - "A stream with retention and a leader locator names both".
  - "A super stream is one entry in the note, not one per partition".
  - "A long note stops after ten entries and says how many more there are".
  - "An export that reads the topology but cannot write a setting of it says so and writes no file" (`@negative`).
  - "An export checks only what it writes".
  - Existing "An export that cannot read the policies writes no file": unchanged, must still say "could not be read".
  - New steps needed: `the broker has the stream {string} with the leader locator {string}`; `... with retention {string} as the text {string} and the leader locator {string}`; `the export result reads {string}`; `the export result does not name {string}, {string} or {string}`; `the broker has {int} exclusive queues named {string} to {string}`; `the export result names {int} entries and ends with {string}`; `the broker has the quorum queue {string} with an argument that cannot be written to a migration file`; `the broker has the quorum queue {string} and an exclusive queue {string}`; `the broker has a binding from {string} to {string} with an argument that cannot be written to a migration file`; `I am told the topology was read but a setting in it cannot be written to a migration file, and no migration file was written`; `the export result says that the exclusive queue {string} was left out`.
- Unit tests first, `MigrationsViewModelExportTest` and a new pure `TopologyExportNoteTest`: each row of the wording table; the cap at exactly 10 (no "and 0 more") and 11; a super stream whose partitions differ (union of words); an incomplete queue argument and an incomplete policy fail with the new sentence; a failed read keeps the old sentence; an incomplete argument on a left-out queue, a built-in exchange or a binding of a left-out queue does not fail.
- Data: `ManagementExportReadsTest` for the tolerant queue and policy parsers (flag false, display kept, neighbours unmarked; a missing required field still `Unexpected`).
- `MigrationsExportNoteUiTest` stays green unchanged (the screen shows `exportNote` as before).
- Live gates untouched and must stay green: `RealBrokerM9ExportSpikeTest` (E7 round trip), `RealBrokerM5MigrationTest`.

## Design notes
`core` (`TopologyExport`, `TopologyModel` flags, `MigrationsViewModel`) and `data-rabbit` (`ManagementJson` two parsers). No `ui` change: `MigrationsScreen` already shows `exportNote` and `errorMessage` as text. No new dependency, no new request, nothing broker-side; `core` stays free of RabbitMQ and `java.*`. A super stream is recognised by the exchange with `superStream = true` and its bindings (already read); a partition belongs to the first super stream (by exchange name) whose binding points at it.

## UX
Same plain note line (`migrations-export-note`), no icon, no alert styling (owner item 7). The error line shows the new sentence in the existing error place.

## MCP
None (UI-only; the MCP server has no export tool, and no new capability is added).

## Owner review list (recommendations, none blocks the task; the constants are one-line edits)
1. "Other settings": a stream argument that is neither retention nor leader locator is named `other settings`. Recommendation: keep (otherwise it would be dropped silently, which the owner's rule "only what is present" does not allow).
2. Both settings: `retention and leader locator of stream audit`; three words: `retention, leader locator and other settings of stream audit`. Recommendation: keep.
3. Every real super stream says `leader locator of super stream X (N partitions)` because the CLI sets `balanced` on each partition. Options: (a) say it, as decided (recommended: it is true and the file cannot carry it); (b) hide a leader locator that every partition shares. Needs the owner only if the line is too noisy.
4. Cap text: `and 2 more` as the last entry after "; ". Recommendation: keep.
5. Super stream partitions that differ: the words are the union, partitions are not compared and no word like "differ" is added. Recommendation: keep.
6. Which keys count as retention: `x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`. Recommendation: keep (segment size is a stream size limit the file cannot carry).

## Open points
None blocking. Wording above is final for the build unless the owner edits the list.

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-14b-export-polish`, base main `342b0a7`): brief CHANGED against the real code (`TopologyExport.kt`, `MigrationsViewModel.readTopologyExport`, `ManagementJson` parsers, `typedArgumentsComplete`, `MigrationsExportNoteUiTest`, feature `10` @m9-14): owner answers recorded; wording constants decided; F3 needs the two strict `data-rabbit` parsers to become tolerant (so it is not core only); F4 test named; F5 out, F6 in; 7 scenarios written in feature `10` (tag `@m9-14b`, glue missing, so they fail until step 2). Next: step 2 (test developer: glue, unit tests, parser tests, all red first).

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed (no spec change needed: the export's note wording is not in `docs/spec.md`).
