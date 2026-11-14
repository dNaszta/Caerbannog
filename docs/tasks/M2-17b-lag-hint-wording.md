# Lag tooltip wording: any consumer of a stream with stored offsets can read a few behind

Milestone: M2    Depends on: M2-17 (the facts), **the owner's confirmation of the wording**    Size: S

## Goal
The tooltip of the "N behind" status says what the broker's number means for every consumer, not only for one that stores its offset. Nothing else on the screen changes: the number, "Caught up only at 0", the neutral style and the accessible description keep the rules of the lag ruling.

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
Out: the rule (Caught up only at 0, the broker's number, neutral style), the status types, the `04` scenarios (the `@fake-broker` scenario "A consumer that stored its offsets can read a few behind without being behind" stays true and keeps its steps), any layout change.

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

Found on the re-check, NOT in scope until the owner answers: the footnote under the consumers table (`StreamClientsTexts.FOOTNOTE`, `core/.../StreamClientsState.kt` lines 131 and 132; mockup line 107) says the broker's number "can be higher than the real distance for a consumer that stores its offset (each stored offset counts)". That has the same flaw as the old tooltip and now sits next to a tooltip that says "any consumer of a stream where offsets are stored". No test pins it. Options and the architect's recommendation are with the coordinator; if the owner changes it, it becomes a second constant, a new pinning test and one mockup line, still one small task. Until then the footnote stays as it is.

## Definition of done
Owner's decision recorded here and in the spec, tests red then green, mockup and spec text in line, no unrelated change.

## Status
- Branch: `m2-17b-lag-hint-wording` (worktree `.claude/worktrees/m2-17b`, from main 286c44f). Round: 0.
- Step 1 (architect, 2026-11-15): DONE, commit 6042a8e. Brief confirmed ready (owner chose option A on 2026-11-15); spec tab paragraph and mockup (two pill titles and the note) carry the confirmed sentence. Two test literals (`StreamClientsTextsTest:21`, `StreamClientsTestKit.HINT_TEXT:102`) plus the constant; `ClientsSteps` needs no change.
- Step 2 (test developer, 2026-11-15): DONE, commit 537035a. Two literals changed; red for the right reason (assertion mismatch old vs new text): core `StreamClientsTextsTest` 1 failed, ui `StreamClientsStatusUiTest` 2 failed, `StreamScreenUiTest` 1 failed, `StreamClientsLazyUiTest` 2 failed. `StreamClientsHintPopupUiTest` stays green (geometry only). Not run: acceptance, brokerTest, screenshots.
- Step 3 (senior developer, 2026-11-15): DONE, commit 644f0ee. Only `StreamClientsTexts.STORED_OFFSETS_HINT` changed (one line). The 7 formerly red tests are green: core `StreamClientsTextsTest` 2, `StreamClientsViewModelRowsTest` 19; ui `StreamClientsStatusUiTest` 6, `StreamClientsHintPopupUiTest` 1, `StreamScreenUiTest` 35, `StreamClientsLazyUiTest` 14, all 0 failed. Not run by the builder: acceptance, screenshots, brokerTest.
- Step 4 (test developer, 2026-11-15): DONE, verdict PASS, no finding above note, no edit or commit. Ran: `:ui:screenshotTest --tests '*StreamClientsScreenshotTest*'` 9 tests 0 failed; `:core:jvmTest :ui:jvmTest :acceptance-tests:test --continue` core 2332 / ui 816 tests 0 failed, acceptance 25 runner classes 0 failed (feature 04: 21 tests, 0 failed, 3 `@broker` skipped); `verifyTestDiscovery` passed; no `tests/bdd` diff. Screenshots: the sentence wraps to four lines on word boundaries, not clipped, header not covered; the last-row popup overlays the footnote's first line (overlay by design, geometry pinned by `StreamClientsHintPopupUiTest`). Not run: brokerTest, data-rabbit/mcp-server/desktopApp/dev-seed tests, other screenshot classes, keychainTest, no old-wording comparison screenshot.
- Step 5 (architect review): next. Last verdict: none yet.
- Open findings: none.
- Open question for the owner (not in scope, not built): the footnote `StreamClientsTexts.FOOTNOTE` ("... for a consumer that stores its offset ...") has the same flaw as the old tooltip. F1 (recommended): change that fragment to "on a stream where offsets are stored" with one pinning test and mockup line 107; F2: leave it. Goes into the final report to the owner.
- Screenshot: `StreamClientsScreenshotTest` (`hintShot`) draws the hint popup; run it once after the build and look at the three `clients-02*-hint-*.png` files.
