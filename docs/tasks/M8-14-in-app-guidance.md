# In-app guidance for the migration and forwarding screens: descriptions, tips, advice and a "How to"

Milestone: M8 (polish after the gate)    Depends on: M8-06, M8-09, M8-12 (all merged, main `66a6e8d`)    Size: M (one task; if it grows, cut by screen: `M8-14a` = `HelpTip`, the How-to dialog and the Migrate from 3.x screen, `M8-14b` = Migrations, forwarding and the drift test)

LEAN (owner, 2026-11-11): text and one small component. No behaviour change, no new dependency, no new MCP tool, no new screen.

## Goal
A user who has never read the docs can do a 3.x to 4.x migration from the app alone: each migration screen says in one line what it is for, small info icons explain the terms and choices, advice lines say what to do next or what to watch out for, and every screen has a **How to** link that opens a short numbered list (the same steps as `docs/MIGRATING_FROM_3X.md`, shorter).

## Why
The owner's request (2026-11-11): "descriptions, tooltips, advices" and "how tos" in the GUI too. The screens are dense (report, review, generate, apply, forward) and the safety-critical points (a Shovel MOVES messages, stores credentials in clear text, the access file is a secret) must be hard to miss.

## What exists (checked against main)
- Hints already on screen and kept as they are: `MigrateFromThreeTexts.INFO_LINE`, `FILE_EXPORT_HINT`, `analyseLiveHint`, `LIVE_LOADING_HINT`, the vhost prompt text, the message-loss block with its fixes, the generate result texts, the secret-file warning, `ForwardingTexts` (confirmation lines, blocks, warnings, progress, end states), `MigrationsWording` (disabled reasons, `HASH_FILE_NO_EDIT`), the permission pills and the disabled navigation note.
- Tooltips exist as one-off code per screen (`PlainTooltip`, `TooltipArea`, the private `ReasonTooltip` of the Migrate screen). There is NO shared help component in `ui/components` (it holds `CenteredMessage`, `Clipboard`, `CopyableText`, `LostConnectionBanner`, `MetricSparklineView`, `SearchField`).
- A modal `Dialog` is already used (`EmptyHistoryDialog`). The app opens no URL or file today (no `Desktop.browse` anywhere).

## Scope
In:
1. **One reusable composable** `HelpTip(id: String, text: String)` in `ui/components/HelpTip.kt`: an info icon (`AppIcon.Info`, small), a hover tooltip (`TooltipArea` with `PlainTooltip`), the text as the icon's `contentDescription` and semantics `onClick` label (screen readers), keyboard focusable, and a click or Enter toggles the same text as a visible `bodySmall` line under the icon (so a keyboard or touch user is not hover-bound). Test tag `help-tip-<id>`. No other component.
2. **A `HowTo` link and dialog**: `HowToLink(howTo: HowTo)` (a `TextButton` "How to", tag `howto-<id>`) that opens a modal `Dialog` with the title, "You need", the numbered steps and "If it goes wrong" (tag `howto-dialog-<id>`, close button `howto-close`). One composable `HowToDialog`, no new screen.
3. **Texts in core, pure and pinned** (extend the existing objects; no string is written in a composable): tips and advice in `MigrateFromThreeTexts`, `MigrationsWording`, `ForwardingTexts`; the three how-tos in one new small object `HowToTexts` (`core/help/HowToTexts.kt`, `data class HowTo(id, title, needs: List<String>, steps: List<String>, ifWrong: List<String>)`).
4. **Placement.** A "How to" link on the right of the screen's top line (D4). Tips next to the terms listed below. Advice lines are plain text in the place named in the table; the safety-critical ones are ALWAYS VISIBLE, never only a tooltip (D2).
5. **Docs and GUI cannot drift** (decided): the GUI how-tos are the short version of the docs, written once in `HowToTexts`; a core test `HowToDocsDriftTest` reads `docs/MIGRATING_FROM_3X.md` (path relative to the project root, as other tests read source files) and asserts, per how-to, that the doc section exists (by its `## ` heading text kept in `HowTo.docHeading`) and that every KEY PHRASE of the how-to appears in both the doc section and the GUI steps. Key phrases are the button and term labels the steps depend on (`Forward messages`, `Generate migration`, `Apply pending`, `Remove shovel`, `Full control`, `administrator`, `Definitions file`, `Live connection`, `Open Migrations`, `Export the files`, `hash hidden`), taken from the same constants the screens draw, so renaming a button without updating the doc fails the test. A single generated source (markdown from Kotlin) is NOT built: it would make the docs hard to edit for no gain.
Out: a Help screen or drawer item, a link to the docs from the app (D3), translations, onboarding tours, changes to any rule, behaviour or existing text, screenshots, tips on the stream, topology, publish and connection screens (they may follow if the owner likes this).

## The places and texts
Every text below is a proposal to be checked against the spec and the code in step 2 (the "check" column says against what); a text that cannot be verified is cut, not guessed. T = tip (icon, tooltip and accessible description), A = advice line (visible text), H = how-to.

### Migrate from 3.x (`migrate3x`)
| # | Element | Kind | Text | Check |
| --- | --- | --- | --- | --- |
| 1 | Screen top line | A | "Analyse a RabbitMQ 3.x broker, decide what to change for 4.x, and generate the migration files. Nothing is ever written to the 3.x broker." (next to the existing info line, which stays) | spec "3.x as a read-only source" |
| 2 | Top line | H | How to: "Analyse and generate" (below) | docs how-tos 1 to 3 |
| 3 | Source option "Definitions file" | T | "A definitions.json exported from the 3.x broker. Export the whole broker if you also want users and permissions; a one-virtual-host export has none." | spec P17, `FILE_EXPORT_HINT` |
| 4 | Source option "Live connection" | T | "Reads the connected 3.x broker with GET requests only. Its user needs the administrator tag to export definitions." | spec "3.x as a read-only source" |
| 5 | Vhost prompt | A | "The virtual host must already exist on the 4.x broker. Because this file has no users or permissions, no access migration can be generated from it." | existing prompt text, spec P17 |
| 6 | Severity chips | T | "Blocker: will not work on 4.x as it is. Warning: works, but behaves differently and you decide. Info: nothing to do, or a manual step. The numbers count the whole report, whatever the filter." | spec "Compatibility report" |
| 7 | Message-loss block title | T | "Classic queues with the old index (version 1) are silently emptied by an in-place upgrade to RabbitMQ 4.3 or later. Do not upgrade in place: build a new 4.x broker and move the messages with a Shovel." | spec P16 (the block already says it; the tip is the short form) |
| 8 | "N items need a decision" | T | "Open an item to accept the proposal, choose an alternative, skip it or replace it with your own actions. Generate migration unlocks when every item is decided." | spec "Review" |
| 9 | Review sheet, Accept | T | "Write the proposed 4.x declaration into the migration." | spec "Review" |
| 10 | Review sheet, Skip | T | "Leave this item out of the migration. It moves to Skipped by you and can be put back." | spec decision 2026-11-10 |
| 11 | Review sheet, Replace | T | "Write your own migration actions for this item. The app checks them and never writes a delete." | spec "Review" |
| 12 | Ordering tick | T | the existing tick label plus "Quorum queues order messages by priority differently from classic queues." | VERIFY against `ReportTexts` and spike S-G before use; cut if not exact |
| 13 | Generate migration | T | "Writes the files into your migrations folder. Nothing is sent to any broker." | spec "Review and the generated migration" |
| 14 | Generate result headline | A | "Next: connect the 4.x broker and apply these files from the Migrations screen. Apply the access file first." (the existing "Apply them on a 4.x connection ..." text stays) | M8-12b, spec P10 |
| 15 | Access file card | T | "Virtual hosts, users with their password hashes, and permissions. It is numbered first so it is applied first." | spec "Users, virtual hosts and permissions" |
| 16 | "Users not migrated" title | T | "guest is never migrated. For a user whose hash 4.x does not accept, set a new password on the broker; the app has no password entry." | spec P11 narrowed, P12 |
| 17 | "Permissions left out" title | T | "Their user is not migrated, so their permissions are not written." | M8-12b |
| 18 | Secret-file warning | A (already visible) | kept; add one line: "Delete the file when you are done." | spec "Secrets" |
| 19 | 3.x pill (connection chip and card) | T | "This broker is only read. To migrate, connect your 4.x broker too." | spec "3.x as a read-only source" |
| 20 | How to: "Analyse and generate" | H | needs: a definitions.json or a 3.x connection (administrator tag). Steps: 1 Choose the source. 2 Read the message-loss block and the report. 3 Open each item that needs a decision and accept, skip or replace it. 4 Click Generate migration. 5 Open Migrations and apply the files on a 4.x connection. If it goes wrong: "not a definitions file" means pick another file; "cannot export" means use another user or a file | docs how-tos 1 to 3 |

### Migrations (`migrations`)
| # | Element | Kind | Text | Check |
| --- | --- | --- | --- | --- |
| 21 | Screen top line | A | "Apply migration files to the connected broker, one action at a time. Applying twice is safe: what already exists is left as it is." | spec "Running migrations for real" |
| 22 | Top line | H | How to: "Apply a migration" | docs how-tos 4, 5 |
| 23 | Folder row | T | "The folder with your migration files. It is remembered for this machine and may be a git repository. The applied history is kept in the app's own data, not here." | spec "Where files live", "Where history lives" |
| 24 | Dry run | T | "Shows what each action would do and whether it is allowed. Nothing is sent." | spec |
| 25 | Apply pending | A (under the button, only on failure) | on a conflict: "Something with that name already exists with other settings. Nothing was overwritten. Change it on the broker or in the migration, then apply again." On a failed action: "The run stopped at this action. Fix the cause and apply again; the file is not marked applied." | spec "Failure rule", conflict rule |
| 26 | Status "modified" | T | "The file changed after it was applied here." | spec history/checksum |
| 27 | A file with a hash | A (already `HASH_FILE_NO_EDIT`) | kept; add the tip on "hash hidden" in the raw view: "The file holds the real hash; the app never shows it." | M8-12a |
| 28 | How to: "Apply a migration" | H | needs: a 4.x connection; topology files: the configure permission, level Declare and edit, matching virtual host; the access file: administrator tag and Full control. Steps: 1 Connect the 4.x broker. 2 Choose the folder. 3 Select a file and read the dry run. 4 Click Apply pending. 5 Check that each file shows applied. If it goes wrong: the run stops at the failing action; a conflict overwrites nothing | docs how-tos 4, 5 |

### Forwarding (`migrate3x`, forwarding view)
| # | Element | Kind | Text | Check |
| --- | --- | --- | --- | --- |
| 29 | View top line | A | "Move the messages of one queue into the same queue on the 4.x broker. The broker moves them; the app only sets it up." | spec "Forwarding" |
| 30 | View top line | H | How to: "Move the messages of a queue" | docs how-tos 6, 7 |
| 31 | Queue list | A | "One queue at a time. The destination queue must already exist on the 4.x broker: apply the migration first." | spec safeguards 2, 9 |
| 32 | Confirmation | A (already visible, bold) | the MOVED and CLEAR_TEXT lines stay; add above them: "Read this before you continue: this moves messages and cannot be undone by the app." | owner decision D2 of M8-09 |
| 33 | AMQP host and port | T | "How the 4.x broker reaches the 3.x broker. It can differ from the address you use here." | spec safeguard 8 |
| 34 | Use another user | T | "Typed for this run, kept in memory only, never saved. Use a read-only user if you made one." | spec P14 |
| 35 | Keep the shovel running | T | the existing line (already visible; stays) | spec P5 |
| 36 | "Not encrypted" line | T | "The 3.x profile has TLS off: the shovel's connection to the 3.x broker is not encrypted, and the password travels in clear on that connection." | spec D9a, safeguard 8; VERIFY wording in step 2 |
| 37 | Type the queue name | T | "Nothing is sent until the name matches exactly." | spec safeguard 1 |
| 38 | Progress | A (visible) | "Leaving this screen stops the progress display, not the shovel. Open the queue again to see it." | owner decision D6 of M8-09 |
| 39 | Failure ("not cleaned up") | A | "What to do: use a connection with the level Full control and click Remove shovel; the broker still holds the source password until then." | spec safeguard 3, P4, 9a |
| 40 | Failure ("cleaned up") | A | "Check the AMQP host, port and password of the 3.x broker, then forward again." | S-F (a wrong password gives terminated) |
| 41 | Remove shovel (disabled) | T | the existing reason plus "It deletes only the shovel, never a queue." | spec safeguard 4 |
| 42 | How to: "Move the messages of a queue" | H | needs: live analysis, 4.x connection, the destination queue, the Shovel plugins, tags and level. Steps: 1 Click Forward messages and pick the queue. 2 Read the plan; fix any block. 3 Read the confirmation, check the AMQP host and port. 4 Type the queue name. 5 Click Forward messages and watch the progress. If it goes wrong: read the reason; remove a failed shovel with Full control | docs how-tos 6, 7 |

42 rows in all (20 + 8 + 14 including 3 how-tos and the existing-text rows that only gain a tip).

## Acceptance
- Unit (core): `HelpTextsTest` pins every string of the table (the existing strings stay unedited); `HowToTextsTest` (every how-to has an id, a title, steps and key phrases; no secret word or sentinel); `HowToDocsDriftTest` (above).
- Compose (ui): `HelpTipUiTest` (the tip draws, its `contentDescription` equals the text, a click shows the text, the tag `help-tip-<id>` is stable); one test per screen that every table row's tag is drawn in its state (parametrised from the table's ids); `HowToDialogUiTest` (open, steps drawn in order, close).
- Existing tests stay green and unedited: every existing tag and text is kept (tips are added next to elements, nothing moves or is renamed). A guard test (like `MigrateFromThreeTextsGuardTest`) scans the three screens' composables for new string literals that are not in a `*Texts` object.
- No new BDD scenario: the existing suites are view-model level and a "screen explains X" scenario would need a Compose binding; the Compose tests above bind the same words (README says so).
- Opt-in screenshots by eye of the three screens with a tip open and the How-to dialog.
- Per step run only the affected modules: `:core:jvmTest --tests '*Help*' --tests '*HowTo*'`, `:ui:jvmTest --tests '*Help*' --tests '*HowTo*' --tests '*Migrate3x*' --tests '*Migrations*'`; full suite once at the end.

## Design notes
- `HelpTip` and `HowToDialog` are the only new composables; they take plain strings (no model knowledge), so they can be reused later.
- IDs: `help-tip-<screen>-<n>` using the table numbers (`help-tip-m3x-6`), so a test can look up each row and the tags stay stable.
- Where a tip sits next to an element inside a `Row` with `weight`, keep the Compose pitfall of CLAUDE.md in mind (no scroll container around the tip).
- No `java.*` in `core`; the doc path is only read by the test.

## UX
No mockup change is part of this task (owner, 2026-11-11: guidance is in the GUI, not the mockups). Step 2 may add a short "as shipped" note to `docs/ux/Migrate3x.dc.html` listing where tips sit, if the owner wants.

## MCP
None.

## Owner decisions (ask in one go; each with options and the architect's recommendation)
- **D1. Tone and wording.** The 42 texts above are proposals in plain, short, second-person-free sentences, matching the existing app voice. Options: (a) approve as written, (b) edit. Recommendation (a); wording changes are constants and cost nothing in step 3.
- **D2. Tooltip or always visible?** Options: (a) everything is a tooltip, (b) safety-critical points always visible, the rest tooltips, (c) everything visible. Recommendation (b): always visible are: the Shovel MOVES messages and cannot be undone (row 32, plus the bold lines that exist), the credentials are in clear text (exists), "Not encrypted" (exists, row 36 only explains it), "leaving the screen does not stop the shovel" (row 38), what to do after a failure (rows 39, 40), the secret-file warning and "delete the file" (row 18), and the destination-must-exist line (row 31). All other rows are tips.
- **D3. A "Help" link to `docs/MIGRATING_FROM_3X.md`.** The app opens no URL or file today, and the installed app does not ship the docs. Options: (a) no link; the in-app How to is the guide (recommended), (b) a link to the file on GitHub (needs a small new `Desktop.browse` code path and a hard-coded URL: new behaviour, not lean), (c) plain text "Full guide: docs/MIGRATING_FROM_3X.md in the project repository" at the bottom of each How-to dialog (cheap, not clickable). Recommendation (a), or (c) if the owner wants the pointer.
- **D4. Where the "How to" entry sits.** Options: (a) a "How to" text link on the right of each screen's top line (recommended: visible where the user is, one per screen, no navigation change), (b) an info icon in the app bar that opens the same dialog, (c) a global Help item in the drawer with all three how-tos on one screen. Recommendation (a).
- **D5. Dialog or side panel.** Options: (a) a modal dialog (recommended: simple, already used in the app; the user cannot work in the screen behind it while reading, which is fine for 5 steps), (b) a side panel that stays open while you work (more layout work, and the screens are already full). Recommendation (a).
Technical (decided by the architect, no question): `HowToDocsDriftTest` by key phrases rather than a generated source; `HelpTip` toggles a visible line on click so it works without a mouse; ids from the table numbers.

## Open points
Rows 12 and 36 name wording that must be verified against `ReportTexts` / spike S-G and the TLS rule before step 3; they are cut if they cannot be verified. Windows hover behaviour of `TooltipArea` is the same as on the existing screens (not separately verified).

## Definition of done
Tests first and passing, the 42 texts pinned and verified against the spec and code, screenshots looked at, docs how-tos and the in-app how-tos agree (drift test green), no behaviour change, no unrelated change.

## Status
- Step 1 (architect, 2026-11-11, branch `m8-14-in-app-guidance`, base main `66a6e8d`): briefed. Waits for the owner's answers on D1 to D5 (all have recommendations) before step 2.
- Owner decisions (2026-11-11, FINAL, recorded by the test developer from the coordinator): D1 the 42 texts approved as written (rows 12 and 36 to be verified against code and spec); D2 safety texts ALWAYS VISIBLE (a Shovel MOVES messages and cannot be undone, credentials in clear text, "Not encrypted", leaving the screen does not stop the shovel, what to do after a failure, the secret-file warning and "delete the file", the destination queue must already exist), everything else a tooltip; D3 NO link to the docs from the screens; D4 "How to" link on the right of each screen's top line; D5 modal dialog.
- Step 2 (test developer, 2026-11-11, merged main 2dd41a0 first): the work is larger than one normal task, so it is SPLIT as the brief proposes, and this round is the whole of M8-14a: `HelpTip`, `HowToLink` and `HowToDialog`, `HowToTexts` (with the first how-to "Analyse and generate"), and the Migrate from 3.x screen (rows 1 to 18, row 19 only as a pinned text). M8-14b: Migrations, forwarding (rows 21 to 42, rows 36 and 38 to 41), the other two how-tos, and the screens' own guard for their sources. Row 12 VERIFIED: the tick label is `ReportTexts.TICK_ORDERING` ("Ordering by priority may change", spec P15) and spike S-G proved that a quorum queue orders by priority always while a classic one without `x-max-priority` is FIFO; the added sentence is true. Row 36 is for 14b (not verified yet). Row 7 checked against `LOSS_DETECTION` (4.3 and later cannot read the old index).
  - Tests: core `MigrateHelpTextsTest` (5: the 13 tips of 14a in table order with exact text, the four advice lines, the 3.x pill text, agreement with `TICK_ORDERING` and `SKIPPED_GROUP_TITLE`, no secret or URL), `HowToTextsTest` (5: completeness, the five steps, key phrases are the screen labels, the three doc headings, no secret), `HowToDocsDriftTest` (2: the doc sections exist, every key phrase is in the doc sections and in the in-app how-to; reads `docs/MIGRATING_FROM_3X.md`); ui `HelpTipUiTest` (4: tag and content description, click shows a line `help-tip-<id>-line` and a second click hides it, Enter on the focused tip, hover tooltip with the test clock), `HowToDialogUiTest` (3: link and closed dialog, open with title, needs, ordered steps `howto-step-<n>` and failure advice, `howto-close`), `MigrateFromThreeHelpUiTest` (8: one test per state draws every row's tag: source choice, vhost prompt, report, review bar and sheet and generate, result with and without access data; advice lines are visible text), `MigrateFromThreeHelpGuardTest` (3: none of the approved words in the screen sources, no literal given to `HelpTip`, the sources use `HelpTip` and `HowToLink`).
  - Pinned names (the brief gave none): tags `help-tip-m3x-<n>`, `help-tip-<id>-line`, `migrate3x-advice-top|vhost|next-step|delete-file`, `howto-<id>` with id `analyse-generate`, `howto-dialog-<id>`, `howto-step-<n>`, `howto-close`; core `HelpText(id, text)`, `HowTo(id, title, docHeadings, needs, steps, ifWrong, keyPhrases)`, `MigrateFromThreeTexts.HELP_TIPS`, `ADVICE_TOP`, `ADVICE_VHOST`, `ADVICE_NEXT_STEP`, `DELETE_THE_FILE`, `HELP_TIP_3X_PILL`.
  - Red on the stubs (`M8-14 STUB`): core 11 of 12 and ui 14 of 17, all by assertion. Green on arrival: `HowToTextsTest` "no how-to holds a secret" (the stub has no text), `MigrateFromThreeHelpUiTest` "the existing words stay", `MigrateFromThreeHelpGuardTest` first two (nothing is written yet).
  - Open points: (1) one how-to covers three doc sections (docs 1 to 3), so `docHeading` became `docHeadings: List<String>`. (2) The how-to step wording is mine (the brief gives a summary): step 1 names "Definitions file" and "Live connection" so the key phrases can be checked. (3) Row 19 (the 3.x pill) sits on the Connections screen: only its text is pinned (`HELP_TIP_3X_PILL`); its tip is for the architect to place (14b or later). (4) The tag of the info icon is the tip's root node; the content description may sit on the node or its child (the test accepts both).
  - Not run: the other modules, the full suite, brokerTest, screenshots.
- Step 3 (senior developer, 2026-11-11): M8-14a built, no `M8-14 STUB` left. `HelpTip` and `HowToLink`/`HowToDialog` in `ui/components` (the tip reuses `ReasonTooltip`, a clickable icon box so it is focusable and Enter works; the visible line is `bodySmall` limited to 420 dp), `HowToTexts.ANALYSE_AND_GENERATE` (key phrases Definitions file, Live connection, message-loss, Generate migration, Open Migrations, administrator; all are already labels in `docs/MIGRATING_FROM_3X.md` sections 1 to 3, so the guide was NOT edited), the 13 tips, 4 advice lines and the 3.x pill text in `MigrateFromThreeTexts` (+ `tip(id)`), wiring in the Migrate from 3.x screen: How to link on the top line (all states), advice top (source choice), vhost advice (prompt), tips 3 and 4 beside the source options, 6 beside the chips, 7 in the message-loss title, 8 in the decision bar, 9 to 11 beside Accept, Skip, Replace, 12 beside the tick, 13 beside Generate migration, 15 on the access file, 16 and 17 on the two list titles, next-step and delete-the-file advice in the result. Composables only call `M3xTip(n)` and `Advice(text, tag)` with core texts.
  - Run: `:core:jvmTest --tests '*HowTo*' --tests '*MigrateHelp*'` green (incl. the drift test); `:ui:jvmTest --tests '*HelpTip*' --tests '*HowTo*' --tests '*MigrateFromThree*'` 161 run, 2 red (below); `:desktopApp:compileKotlin` green.
  - RED, existing tests that the tips change by design (not edited): (1) `MigrateFromThreeLiveExploratoryUiTest` "Tab walks from the file option to the live option to the button": each option now has a focusable tip, so Tab visits the tips between them (the brief requires keyboard focus on the tip). (2) `MigrateFromThreeLiveSourceUiTest` "the picker draws no connect action, only the options and the file button can be clicked": the tips are clickable by design (they toggle a line); the allowed set must add `help-tip-m3x-3` and `help-tip-m3x-4`. Both need the test developer's update.
  - Not run: the rest of the ui suite, the full suite, brokerTest, screenshots (the full fast set runs once at the end of 14b).
- Step 4 of M8-14a (test developer, 2026-11-12): verified at 0983e6a plus test updates (commit 87acff6). The two tip-affected tests follow the shipped order: Tab goes file option, its tip, live option, its tip, file button, How to link (the link is last because it is the last focusable item in the top line's layout order); the clickable set of the empty screen now also holds `help-tip-m3x-3`, `help-tip-m3x-4` and `howto-analyse-generate`. Run `:core:jvmTest *HowTo* *MigrateHelp*`, `:ui:jvmTest *HelpTip* *HowTo* *MigrateFromThree*` (161), `:desktopApp:test`: green after the update. Mutations (4, each caught, restored): the delete-the-file advice drawn as a tip only (`MigrateFromThreeHelpUiTest`), "Open Migrations" removed from the docs section (`HowToDocsDriftTest`), a literal advice text in a screen (`MigrateFromThreeHelpGuardTest`), the How to link removed (`MigrateFromThreeHelpUiTest`, 2 red). Screenshots (Migrate from 3.x, opt-in) viewed: source choice, report with the loss block and tips, review sheet, access result, narrow window: layout intact, tips do not overlap, advice lines readable. Not run: the full suite, brokerTest; no screenshot of the How to dialog exists (no test draws it).
