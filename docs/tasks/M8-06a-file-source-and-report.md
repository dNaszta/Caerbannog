# Migrate-from-3.x screen, part a: file source and report

Milestone: M8    Depends on: M8-01, M8-03 (merged); owner review of `docs/ux/Migrate3x.dc.html`    Size: M

## Goal
From the new screen the user chooses a definitions file, answers the virtual host question if the file needs it, and reads the compatibility report (message-loss block first, severity chips with counts, groups, rows), exports it as JSON or copies it as text. The held report survives connecting to another broker. The screen sends nothing to any broker and works with no connection.

## Why
Spec decision 1 and "Migrating from RabbitMQ 3.x (M8)"; M8-01 and M8-03 built the model, parser and analyzer but nothing shows them.

## Scope
In (`core`, package `dev.caerbannog.core.migrate3x`, `commonMain`, no `java.*`):
1. `MigrateFromThreeViewModel(loader: DefinitionsFileLoader, fileChooser: DefinitionsFileChooser, reportSaver: ReportFileSaver, holder: AnalysisHolder, scope: CoroutineScope)`; state in, events out, one `StateFlow<MigrateFromThreeState>`.
   ```
   sealed interface MigrateFromThreeState {
       data object ChooseSource
       data class Loading(val fileLabel: String)
       data class Rejected(val fileLabel: String, val text: String)              // fixed texts, never file content
       data class NeedsVirtualHost(val fileLabel: String, val error: String?)    // error = InvalidName.reason as returned
       data class Report(val view: ReportView, val notice: ExportNotice?)
   }
   ```
   `ReportView`: source label and version, counts (`ReportSummary`, plus document counts), `lossBlock` (the message-loss findings: queue names, the three fixes, the recommendation; null when there is none), `chips` (selected severities and the three counts, which ignore the filter), `groups` (key = rule plus severity, title, count, open flag; order: message loss, then blocker, warning, info, rule name) and `rows(group): RowSource` (`size` and `at(index): RowView`, built on demand, so a closed group holds no rows). `RowView`: kind icon, plain-text name (escaped by the same function as the text export), message, why, proposal summary, `verified` flag, and for a user row the tags, algorithm and the fixed text `hash hidden`.
   Events: `chooseFile()`, `open(path)`, `giveVirtualHost(text)` (trims, then `assignVirtualHost`), `cancelPrompt()`, `toggleSeverity(severity)`, `toggleGroup(key)`, `exportReportJson()`, `reportText(): String` (the UI copies it with `rememberCopyToClipboard()`), `dismissNotice()`, `reset()`.
2. Ports in `core`: `fun interface DefinitionsFileChooser { suspend fun choose(): String? }`, `fun interface ReportFileSaver { suspend fun save(suggestedName: String, text: String): SaveOutcome }` (`Saved(path)`, `Cancelled`, `Failed(reason)` with fixed reasons), `AnalysisHolder` (in-memory, app lifetime, holds `DefinitionsDocument` and `CompatibilityReport` and the file label; the window rebuilds view models when inputs change, so the state cannot live in the view model).
3. Pure formatting in `core`: `ReportViewBuilder` (grouping, chips, rows, loss block), `FindingRowFormatter`; fixed texts in one object. Decision logic never in a composable.
4. `ui`: `MigrateFromThreeScreen` and components (source card, prompt, banner, chips, lazy grouped list with a visible scrollbar, export menu); `testTag`s: `migrate3x-choose-file`, `migrate3x-state-<name>`, `migrate3x-loss-banner`, `migrate3x-chip-<severity>`, `migrate3x-group-<key>`, `migrate3x-row-<index>`, `migrate3x-vhost-field`, `migrate3x-vhost-error`, `migrate3x-export`, `migrate3x-copy-text`.
5. `desktopApp`: drawer entry and screen wiring (`MigrateFromThreeWiring.kt`), native file chooser (read) and save dialog (write) adapters, holder in `AppComposition`, a button on the Migrations screen (owner decision 1).
6. `data-rabbit`: the file loader already exists (`DefinitionsFileLoader`; check that an adapter is wired in the app, else add the JVM one here with the 64 MB limit and no content in errors).
Out: review and generate (M8-06b); live source, 3.x pill and source locking (M8-06c); access migration (M8-06d); forwarding (M8-09); any decision; any write to the migrations folder.

## Acceptance
- BDD (all in `16_migrate_from_3x.feature`, tag `@m8-06a @fake-broker`, suite `RunMigrate3xScreenAcceptanceTest`, glue in `acceptance-tests/.../migrate3x`, steps drive the view model with fakes for chooser, loader and saver): "Open a definitions file and read the report on the screen" (@smoke); "The message-loss block is the first block and recommends a new broker with a Shovel"; "A file that cannot be read shows its reason and no report" (4 examples); "A per-virtual-host export asks for the name and analyses nothing until it gets one"; "A virtual host name that cannot be used is shown as an inline error"; "The report is filtered by severity and keeps the message-loss block first"; "A very large report is shown as groups with counts and loads rows only when a group is opened"; "A user row shows the hash as hidden and no hash reaches the screen"; "A policy pattern the app could not evaluate says so and asks the user to check by hand"; "Names from the file are shown as plain text"; "Export the report from the screen as JSON or copy it as text"; "A report that cannot be saved says so and keeps the report"; "The report of a file survives connecting to a 4.x broker"; "The screen with a file source sends no request to any broker". Also the existing "Items that need no change are not listed as problems" is bound again at screen level by the first scenario.
- Unit tests (`core/src/jvmTest/.../migrate3x/`): `ReportViewBuilderTest` (grouping order, loss block first whatever the filter, counts independent of the filter, a closed group builds no rows, `RowSource` on 240,000 findings within the default heap with no timing assertion), `MigrateFromThreeViewModelTest` (every state transition incl. cancel during Loading, a second file replaces the first, a prompt answered after `reset` is ignored), `FindingRowFormatterTest` (control characters, markup text stays text, user row has no hash and the algorithm), `SecretsOnScreenTest` (the M8-01 sentinels appear in no state `toString`, no row, no text export; source guard: new files never call `expose(` and never import `java.`).
- Compose UI tests (`ui/src/jvmTest/.../migrate3x/`): every state of the mockup (1 to 5 and 12), keyboard reachability (tab to the chips, groups, rows, the export menu; Enter opens a group), copyable values (names, loss list), long names and 60,000-queue stubs do not break layout (lazy list), no semantics node, description or screenshot contains a hash sentinel, the vhost field starts empty.
- Opt-in screenshots (`task test:screenshots`) for a by-eye comparison with the mockup.
- `verifyTestDiscovery` green; mutation checks to record by the test developer: (a) the loss block filtered away by a chip; (b) the vhost text not trimmed; (c) a row built for every finding of a closed group; (d) the hash text shown in a user row; (e) a request sent on open (fake gateway counts).

## Design notes
Module boundaries: view model and formatting in `core` (pure, no `java.*`), screen in `ui`, adapters in `desktopApp`. Broker: none, no gateway is injected, so a request cannot be made. Fakes: `FakeDefinitionsFileLoader` (exists in `core-testing`? check; else add), `FakeFileChooser`, `FakeReportSaver`. The held analysis is `AnalysisHolder`; `reset()` clears it. Analysis of a large file runs off the UI thread (`Dispatchers.Default` injected as a dispatcher param so tests control it) and is cancellable. Rows: keep one sorted index per group built once on open; the UI asks `rows(group).at(i)`. Text and JSON export use the existing `CompatibilityReportText` and `CompatibilityReportJson`; the screen adds no new format.

## UX
`docs/ux/Migrate3x.dc.html` states 1 (empty), 2 (loading), 3 (rejected), 4 (virtual host prompt and its inline error), 5 (report), 12 (export). States 6 to 11 belong to M8-06b. The source picker shows only "Definitions file"; the second option is drawn disabled in the mockup for context and is built in M8-06c.

## MCP
None (UI). Reason: the report is a human reading task; the read-only MCP report tool is M8-10 and reuses `CompatibilityReportJson`.

## Open points
1. Owner decisions 1 to 6 and 9 of the umbrella brief are DECIDED (owner, 2026-11-10): entry A, grouping A, closed groups A, held in memory A, wording as in the mockup, skipped items B (the Skipped group is built in M8-06b, but the report builder of 06a leaves room: the group list ends with an optional group), cut as proposed.
2. The document counts in the header ("482 queues") come from the held `DefinitionsDocument`, not from `ReportSummary` (which counts items needing decisions); the builder computes them. No new core API needed.
3. "Unverified" marker on a row comes from `Finding.verified`; the exact tooltip text is the one fixed text in `ReportTexts` if present, else one new constant.
4. Where `FakeDefinitionsFileLoader` lives: check `core-testing` first.

## Definition of done
Tests first and passing, `./gradlew test` green, screenshots looked at, spec "Screens and UX" table row for "Migrate from 3.x" accurate, no new dependency, no unrelated change.
