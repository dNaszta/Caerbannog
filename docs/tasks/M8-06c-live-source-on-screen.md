# Live 3.x source on the Migrate-from-3.x screen

Milestone: M8    Depends on: M8-02 (02a and 02b built and verified), M8-06a (merged)    Size: S/M (one developer, 2 days)

## Goal
The user can analyse the connected RabbitMQ 3.x broker from the Migrate-from-3.x screen (source picker, locked source card, offer of the file import when the user may not export), starts it from the 3.x connection popup, and sees the navigation reason once, not under every disabled item (owner decision F4, option B).

## Why
Spec "Migrating from RabbitMQ 3.x (M8)", decision 1 (live source or file), `docs/tasks/M8-06-report-and-review-ui.md` cut table. M8-02 built the live read; nothing on screen uses `analyseLive` yet.

## Scope
In:
1. `core`, `MigrateFromThreeViewModel`: new dependency `liveSource: LiveSourceProvider` (`fun interface`, returns the held connection's `DefinitionsSource` and its name, or null when the held connection is not a 3.x one; wired by `desktopApp` from `ActiveConnection.definitionsSource` and the session). New events `analyseLive()` and `chooseSource(SourceKind)`; new state data: `SourcePicker(options: List<SourceOption>)` with `SourceOption.File` and `SourceOption.Live(connectionName)` or `SourceOption.LiveDisabled(reason)` (fixed text "Connect to a RabbitMQ 3.x broker to read it live"). `analyseLive` calls `analyseLive(source, dispatcher)` (F7: pass the same dispatcher as the file path) and maps `LiveAnalysisOutcome`: `Analysed` gives `Report` (and `AnalysisHolder` holds it), `OfferFileImport(text)` gives a new state `CannotExport(text)` that offers `chooseFile()`, `Unreachable` and `Failed` give `Rejected`-like state with the fixed text (never a body, URL or credential). The `ReportView` source card gets `locked: Boolean` (true for a live source) and the label "read only".
2. `ui`: source picker (segmented control as in `docs/ux/Migrate3x.dc.html` "Where does the 3.x topology come from?"), locked source card (lock icon, "read only"), the `CannotExport` state with the "Choose definitions file" button, the live loading state. Tags (new, stable afterwards): `migrate3x-source-file`, `migrate3x-source-live`, `migrate3x-source-live-reason`, `migrate3x-source-locked`, `migrate3x-state-cannotExport`, `migrate3x-analyse-live`.
3. `ui` + `desktopApp`, F4 (owner decision, option B): the left drawer shows ONE visible note `navReasonNote` under the list while the connection is a 3.x source; the tooltip and the accessible description stay on each disabled item and on the search. Remove the tags `navItemReason:<Destination>` and `searchReason`; update the tests that use them (`NavigationFeatures` Compose and desktopApp tests). One `ReasonText` instead of five.
4. Connection popup: for a 3.x connection the entry "Migrate from 3.x" opens the screen with the live source selected (mockup `docs/ux/Main.dc.html`, chip variant); tag `connection-popup-migrate3x`.
5. F6: fix the import order and the indentation of the `when` inside `CompositionLocalProvider` in `Main.kt` while touching it. F8: see Open points.
6. Live twin of "the analysis survives switching": the held live analysis (`AnalysisHolder`) is not cleared when the 4.x connection becomes the held one; the screen then shows the report with the source card text "from legacy-eu (disconnected)" and the picker offers the live option disabled.
Out: any HTTP or transport change (`ManagementThreeXReads` stays as it is), review and generate (M8-06b, built), access migration (M8-06d), forwarding (M8-09), any write to a 3.x broker, any new MCP tool.

## Acceptance
- BDD (`16_migrate_from_3x.feature`, tags `@fake-broker @m8-06c`, suite `RunMigrate3xScreenAcceptanceTest`, view model with `FakeThreeXReads`/fake sources): "The source picker offers the connected 3.x broker as a live source"; "The live source is not offered while no 3.x broker is connected"; "A live source is shown as locked and read only"; "A user who may not export is offered the file import as a button"; "The connection popup of a 3.x broker offers Migrate from 3.x"; "The analysis survives switching to the 4.x connection" (existing, now built). Already green and not repeated here: "Analyse a live RabbitMQ 3.x connection", "A user who may not export definitions is offered the file import" (`@m8-02`), "Every request the live source reading makes ... is a GET" (`@m8-02b`).
- Unit tests: view model transitions (`ChooseSource` to picker to live loading to `Report`, `CannotExport` then `chooseFile`, `Unreachable`, a second analysis replaces the first, `reset` clears, a late live result after `reset` is ignored), the dispatcher is the injected one (F7).
- Compose tests: picker states (live enabled, live disabled with reason), locked card, `CannotExport` with its button, the drawer note appears once for a 3.x connection and not for 4.x, the old `navItemReason:*` and `searchReason` tags are gone, descriptions still read the reason, keyboard reachability of the picker and the button.
- Safety assertions: the screen and its view model hold no `post`/`put`/`delete` capability (the live port is `DefinitionsSource`, read only); no password, URL or body in any state text or semantics node (sentinel test as in M8-06a).
- Opt-in screenshots for a by-eye comparison with the mockup.

## Design notes
State in, events out; decisions (which options, which reason, which state) in `core`, composables thin. The held connection is still one (D1): the picker never offers to connect, it only reads the held connection. The analysis lives in `AnalysisHolder`, not in the view model. The live fetch can take up to 5 s per vhost with a fresh queue (02a bound): the Loading state needs a cancel (`cancelLoading()` exists).

## UX
`docs/ux/Migrate3x.dc.html` (picker, locked card; the live variant is described under the source card) and `docs/ux/Main.dc.html` (3.x card, chip variant, drawer variant). Mockup note to add by the architect in step 1 of this task: the drawer variant shows ONE note under the list (option B), the picker live option disabled state, the `CannotExport` state. States to build: picker (live enabled, live disabled), live loading, locked report, `CannotExport`, unreachable, failed.

## MCP
None (UI-only). Reason: the live read is already reachable by an agent only through the app's own screens; an MCP tool that analyses or forwards would add a write-adjacent path to a 3.x broker, and the spec forbids a Shovel tool. An analysis tool is a separate decision (M8-06b brief already records no tool).

## Open points
- F8 (owner question, small): after Disconnect the profile card of a 3.x broker keeps the 3.x pill because the session remembers the status of the name. Option A: keep (true information, no work). Option B: show the pill only while Connected, as the mockup says (small change in the card, one test). Recommendation: B, one source of truth with the mockup; it is a wording-adjacent visible change, so the owner confirms before step 3.
- Where the live source reads its pause from: `liveSourcePause` exists; pass it, never `delay` directly.
- Follow-up idea from M8-02b (not for this task): response body size cap in `ManagementHttp`.

## Definition of done
Tests written first and passing (core, ui, desktopApp, acceptance), `verifyTestDiscovery` green, no transport change, mockup and README row updated, `Main.kt` F6 done, F4 tags replaced, spec untouched unless wording changes.
