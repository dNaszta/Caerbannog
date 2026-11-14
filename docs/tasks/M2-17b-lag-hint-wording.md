# Lag tooltip wording: any consumer of a stream with stored offsets can read a few behind

Milestone: M2    Depends on: M2-17 (the facts), **the owner's confirmation of the wording**    Size: S

## Goal
The tooltip of the "N behind" status says what the broker's number means for every consumer, not only for one that stores its offset. Nothing else on the screen changes: the number, "Caught up only at 0", the neutral style and the accessible description keep the rules of the lag ruling. (Addendum, owner 2026-11-15, F1: the footnote under the consumers table gets the same correction; see "Addendum" below. The tooltip scope in this brief is unchanged.)

## Why
The M2-17 finding (`docs/tasks/M2-17-s-a-lag-steady-state.md`, table, and the corrected Consumers row in `docs/spec.md`): once the broker's second statistics refresh has run, every consumer that has read to the end of a stream shows the same lag, whether it stores offsets or not (0 where nobody stored, 2 or 4 in the measured cases). The text built in M2-06, "Stored offsets count as messages, so a consumer that stores its offset can read a few behind when it has read everything.", is true but suggests that a consumer that never stores is exempt. A user who reads "2 behind" on their own non-storing consumer and then the tooltip would look for a store that is not there.

## Owner decision (2026-11-15): option A confirmed. Status: ready to build.
The owner confirmed option A: "Stored offsets count as messages, so any consumer of a stream where offsets are stored can read a few behind when it has read everything." The text below is kept as the record of the question and the options.

## Needs the owner (answered above; was: blocked until answered)
This is wording on a screen, which the lag ruling kept open for the owner ("the owner may veto the wording at the review"), and the finding also shows a consequence the owner may want to weigh:

- **What changes for the user:** only the hover text and the screen-reader description of "N behind" (`ConsumerStatus.Behind.hint`).
- **What the owner should know:** on a stream where any application stores offsets (named consumers do, that is what a name is for) every consumer shows "N behind" with a small N, also when all have read everything. "Caught up" appears on streams without stored offsets. The number cannot be corrected on our side (the management `messages` count includes the tracking entries, so data messages and tracking offsets cannot be told apart).

| Option | Text or change | Architect view |
| --- | --- | --- |
| **A (recommended)** | Keep the rule, change one sentence: "Stored offsets count as messages, so any consumer of a stream where offsets are stored can read a few behind when it has read everything." | Smallest change that makes the tooltip true for every consumer; still begins with the words the existing step and test look for. |
| B | Keep the text as it is | Cheapest; leaves a tooltip that points the user at the wrong cause for non-storing consumers. |
| C | A, plus a line under the table that says "Behind counts stored offsets of every application on this stream." | More explaining on the screen for a small effect; the footnote already says the numbers are approximate. |
| D | Compute our own lag without tracking entries | Not possible from the API (see above); rejected. |
| E | A tolerance ("Caught up" for 0 to N) | Rejected by the ruling: arbitrary, and it would claim exactness the broker does not give. |

Question for the owner (one): confirm option A, or give the sentence you want (or pick B or C).

## Scope (after the owner's answer; shown for option A)
In:
- Change the constant `StreamClientsTexts.STORED_OFFSETS_HINT` (`core/src/commonMain/kotlin/dev/caerbannog/core/streams/StreamClientsState.kt`, line 103) to the confirmed sentence.
- Tests first (red on the old text): the literals in `core/src/jvmTest/.../StreamClientsTextsTest.kt` (line 21) and `ui/src/jvmTest/.../StreamClientsTestKit.kt` (`HINT_TEXT`, line 102). `ClientsSteps.kt` (acceptance, lines 271 to 272) compares with the constant and with the fragment "Stored offsets count", which option A keeps (checked 2026-11-15: both lines pass unchanged, no other sentence in the repo starts with the fragment). Every other test that shows the text reads `HINT_TEXT` or the constant (`StreamClientsLazyUiTest`, `StreamClientsStatusUiTest`, `StreamScreenUiTest`, `StreamClientsViewModelRowsTest`, `StreamClientsTextsTest` line 31), so they go red and green with the two literals.
- The mockup `docs/ux/Clients.dc.html`: the two pill titles (lines 94 and 101) and the note (line 119).
- `docs/spec.md`: the tab paragraph says "Wording open (M2-17b ...)": replace it with the confirmed text and the owner's decision (date).
Out: the rule (Caught up only at 0, the broker's number, neutral style), the status types, the `04` scenarios (the `@fake-broker` scenario "A consumer that stored its offsets can read a few behind without being behind" stays true and keeps its steps), any layout change. (The footnote was out of this scope; the owner added it on 2026-11-15, see "Addendum".)

## Acceptance
- BDD: `04_publishers_consumers.feature` / "A consumer that stored its offsets can read a few behind without being behind" and the lag outline stay green and unedited (`@fake-broker`).
- Unit and UI: the three literals above, red first, then green; every other test unedited.
- Screenshots: checked 2026-11-15, one does show it. `StreamClientsScreenshotTest` (`hintShot`, files `clients-02a-hint-first.png`, `clients-02b-hint-middle.png`, `clients-02c-hint-last.png`) opens the keyboard hint popup of a real `ConsumerStatus.Behind`, so it draws the new sentence (137 characters instead of 121, popup width capped at 320 dp, so it may take one more line). After the build run `./gradlew :ui:screenshotTest --tests '*StreamClientsScreenshotTest*'` once and look at the three files, the last row first. The geometry (inside the window and the table, off the header) is already pinned by `StreamClientsHintPopupUiTest` in the normal `ui:jvmTest`.

## Design notes
Core text constant only; no new type, no new state. The text travels in `ConsumerStatus.Behind.hint` and is shown as a tooltip and as an accessible description (M2-06b), so no `ui` change.

## UX
`docs/ux/Clients.dc.html`, the "N behind" pill. No new state.

## MCP
None. The MCP tools return the number, not the tooltip.

## Open points
Answered 2026-11-15 (option A, sentence in "Owner decision"); the spec and the mockup carry the confirmed text, the code and tests follow in steps 2 to 4.

Footnote question: ANSWERED 2026-11-15 (owner, "F1"), now in scope as the "Addendum" below. Record of the finding, kept as history: the footnote under the consumers table (`StreamClientsTexts.FOOTNOTE`, `core/.../StreamClientsState.kt` lines 131 and 132; mockup line 107) said the broker's number "can be higher than the real distance for a consumer that stores its offset (each stored offset counts)". That had the same flaw as the old tooltip and sat next to a tooltip that says "any consumer of a stream where offsets are stored". (The original note here said "no test pins it"; the re-check for the addendum found that `StreamScreenUiTest` line 206 pins the start and the end of the footnote, not the middle with the changed fragment; see the occurrence list in the Addendum.)

Nothing else is open in this task.

## Addendum (owner decision 2026-11-15, F1)

**Decision.** The owner answered "F1" (relayed by the coordinator, 2026-11-15): in the footnote, the fragment "for a consumer that stores its offset" becomes "on a stream where offsets are stored". Nothing else in the footnote changes. The exact final text of `StreamClientsTexts.FOOTNOTE` (one paragraph, one string):

> Read from the management API (rabbitmq_stream_management). Messages behind comes from the broker and can be higher than the real distance on a stream where offsets are stored (each stored offset counts). Caught up only at 0.

The first sentence ("Read from the management API (rabbitmq_stream_management).") is the footnote's existing first sentence; it is not part of the change, and `StreamScreenUiTest` (line 214) pins it. The owner's relayed sentence began at "Messages behind"; it was read as the part that carries the change, not as a request to drop the first sentence.

**Why.** Same finding as the tooltip (M2-17): once the broker's second statistics refresh has run, every consumer of a stream where any application stored offsets reads the same small lag. "For a consumer that stores its offset" points the reader at the wrong cause for a consumer that never stores, and it now stands under a tooltip that says "any consumer of a stream where offsets are stored".

**Scope.**
In:
- The constant `StreamClientsTexts.FOOTNOTE` (`core/src/commonMain/kotlin/dev/caerbannog/core/streams/StreamClientsState.kt`, lines 131 and 132): replace only the fragment; keep the two-part string concatenation and every other word and mark.
- One new pinning test, written first and seen red: a new test method in `core/src/jvmTest/kotlin/dev/caerbannog/core/streams/StreamClientsTextsTest.kt` that asserts the whole `StreamClientsTexts.FOOTNOTE` against the literal above. A new method, not a line added to "the texts are the words of the brief", so that no existing test is edited. It fails on the old constant with an assertion mismatch (old text against new text), which is the right reason. Why core: the class already pins the other texts of this tab as literals, it is the fast module, and the screen reads the same constant (`StreamScreen.kt` line 595), so one literal in core covers what the user sees.
- Mockup `docs/ux/Clients.dc.html`, line 107, the footnote (done by the architect, same commit as this addendum).
- `docs/spec.md`: one sentence in the "Publishers and consumers tab" paragraph (done by the architect, same commit).
Out: the tooltip sentence (`STORED_OFFSETS_HINT`), the lag rule, the status types, the `04` scenarios (no feature file or step mentions the footnote), the layout, any other text of the tab, `RealBrokerClientsTest`, `ManagementHttp`, `TlsSupport`, the M2-17d brief.

**Acceptance.**
- The new test is red on the old constant and green on the new one; it is the only test added.
- Every other test is unedited and green. Tests that read the footnote and must stay green without an edit: `StreamScreenUiTest` line 206 (starts with "Read from the management API (rabbitmq_stream_management)." and ends with "Caught up only at 0.", both still true) and `StreamClientsLazyUiTest` line 134 (the footnote exists at the end of the list). Do not expect them to go red.
- The tooltip change is untouched: the diff of the build commit shows no change to `STORED_OFFSETS_HINT` and no change to the tooltip literals in `StreamClientsTextsTest` (line 21) and `StreamClientsTestKit` (`HINT_TEXT`).
- Screenshots: run `./gradlew :ui:screenshotTest --tests '*StreamClientsScreenshotTest*'` once after the build and look at two files. `clients-01-rows.png` draws the footnote itself (today on two lines, the first wraps after "for a"; the new fragment is one character shorter, 36 against 37, so the wrap barely moves): the text must still fit on its lines and not be clipped. `clients-02c-hint-last.png` (popup of the last row): it must still cover only what was accepted in the M2-17b review (its own row's status text and part of the first footnote line, nothing beyond). The pictures in `ui/build/screenshots` from the M2-17b step 4 run show the old text; do not use them as the result.
- Fast set once before the merge, as in step 4 of the tooltip change: `:core:jvmTest :ui:jvmTest :acceptance-tests:test` and `verifyTestDiscovery`. No `tests/bdd` change.

**Steps.** Step 2a (test developer): the new test only, seen red. Step 3a (senior developer): the constant only, the new test green. Step 4a (test developer): screenshot class once and the two pictures, fast set once. Step 5a (architect): review. One commit per step on the branch.

**MCP.** None: the MCP tools return the numbers, not the screen's footnote (no occurrence of `FOOTNOTE` or the text in `:mcp-server`, checked 2026-11-15).

## Definition of done
Owner's decision recorded here and in the spec, tests red then green, mockup and spec text in line, no unrelated change. Addendum: the new footnote test red then green, every other test unedited, the screenshot class run once and the two pictures looked at.

## Status
- Branch: `m2-17b-lag-hint-wording` (worktree `.claude/worktrees/m2-17b`, from main 286c44f). Round: 0.
- Step 1 (architect, 2026-11-15): DONE, commit 6042a8e. Brief confirmed ready (owner chose option A on 2026-11-15); spec tab paragraph and mockup (two pill titles and the note) carry the confirmed sentence. Two test literals (`StreamClientsTextsTest:21`, `StreamClientsTestKit.HINT_TEXT:102`) plus the constant; `ClientsSteps` needs no change.
- Step 2 (test developer, 2026-11-15): DONE, commit 537035a. Two literals changed; red for the right reason (assertion mismatch old vs new text): core `StreamClientsTextsTest` 1 failed, ui `StreamClientsStatusUiTest` 2 failed, `StreamScreenUiTest` 1 failed, `StreamClientsLazyUiTest` 2 failed. `StreamClientsHintPopupUiTest` stays green (geometry only). Not run: acceptance, brokerTest, screenshots.
- Step 3 (senior developer, 2026-11-15): DONE, commit 644f0ee. Only `StreamClientsTexts.STORED_OFFSETS_HINT` changed (one line). The 7 formerly red tests are green: core `StreamClientsTextsTest` 2, `StreamClientsViewModelRowsTest` 19; ui `StreamClientsStatusUiTest` 6, `StreamClientsHintPopupUiTest` 1, `StreamScreenUiTest` 35, `StreamClientsLazyUiTest` 14, all 0 failed. Not run by the builder: acceptance, screenshots, brokerTest.
- Step 4 (test developer, 2026-11-15): DONE, verdict PASS, no finding above note, no edit or commit. Ran: `:ui:screenshotTest --tests '*StreamClientsScreenshotTest*'` 9 tests 0 failed; `:core:jvmTest :ui:jvmTest :acceptance-tests:test --continue` core 2332 / ui 816 tests 0 failed, acceptance 25 runner classes 0 failed (feature 04: 21 tests, 0 failed, 3 `@broker` skipped); `verifyTestDiscovery` passed; no `tests/bdd` diff. Screenshots: the sentence wraps to four lines on word boundaries, not clipped, header not covered; the last-row popup overlays the footnote's first line (overlay by design, geometry pinned by `StreamClientsHintPopupUiTest`). Not run: brokerTest, data-rabbit/mcp-server/desktopApp/dev-seed tests, other screenshot classes, keychainTest, no old-wording comparison screenshot.
- Step 5 (architect, 2026-11-15): DONE, verdict ACCEPTED WITH FOLLOW-UPS (round 0). Reviewed 4231ad0; no blocker, no major. Findings: (1) minor: this brief's Acceptance and the README row said "three literals" while the work is the constant plus two test literals (README row corrected; the brief's Acceptance bullet left as history); (2) note: the last-row popup now covers the first footnote line (overlay by design, geometry pinned by `StreamClientsHintPopupUiTest`, accepted); (3) note: the footnote question below; (4) idea, pre-existing: probe whether the popup of a bottom row is cut in a long list, new brief M2-17d (`docs/tasks/M2-17d-hint-popup-bottom-row.md`, commit 0ca1eb7).
- Tooltip change: DONE on the branch (nothing pushed or merged). Round: 0 of 3. Last verdict: Accepted with follow-ups.
- Open findings: none for this task.
- Addendum, owner decision F1 (2026-11-15, relayed by the coordinator's caller): the footnote `StreamClientsTexts.FOOTNOTE` fragment "for a consumer that stores its offset" becomes "on a stream where offsets are stored"; folded into this task on the same branch. The first sentence of the footnote ("Read from the management API (rabbitmq_stream_management).") stays (the architect read F1 as a fragment swap; `StreamScreenUiTest:206` pins it). Round: 0 (addendum, not a fix round).
- Step 1a (architect, 2026-11-15): DONE, commit e3c4bce. Brief addendum, spec line 152 and mockup line 107 updated. New pinning test goes into `core/.../StreamClientsTextsTest.kt` as a new method (full `FOOTNOTE` literal). Existing readers (`StreamScreenUiTest:206-216` start/end, `StreamClientsLazyUiTest:134`) stay green unedited.
- Step 2a (test developer): next. Then 3a senior developer, 4a verify (core, ui, acceptance, `verifyTestDiscovery`, Clients screenshot class with `clients-01-rows.png` and `clients-02c-hint-last.png` looked at), 5a architect review. Last verdict (addendum): none yet. The step 5 verdict above covers the tooltip change.
- Screenshot: `StreamClientsScreenshotTest` (`hintShot`) was run once after the build (9 tests, 0 failed) and the three `clients-02*-hint-*.png` files were looked at: four lines, word-boundary wraps, nothing clipped, header not covered.
