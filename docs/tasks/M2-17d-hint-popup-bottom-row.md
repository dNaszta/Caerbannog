# Hint popup of a Behind row at the bottom of a long consumers list (probe first)

Milestone: M2    Depends on: none (M2-17b makes the popup one line taller, 4 lines instead of probably 3, but the question is the same with either text)    Size: S

## Goal
Either a test proves that the popup opened from the "i" of any Behind row is fully visible in a long consumers list, also for the bottom-most fully visible row and when every row is Behind, and nothing else changes; or the test is red and the popup is made to fit. Decide by the probe, not by guess.

## Why
M2-17 (`docs/tasks/M2-17-s-a-lag-steady-state.md`) showed that on a stream where any application stores offsets every consumer reads "N behind", so in the common case every row carries the "i" (`docs/spec.md`, tab paragraph "Corrected 2026-11-15"). The geometry guards were written for a mixed case:

- `StreamClientsHintPopupUiTest`: 8 rows in a 900 dp window; the row after the last Behind row is the footnote item.
- `StreamClientsLazyUiTest`, "the hint popup is visible for the first and the last fully visible Behind row ...": `rows()` (line 74) makes only every third row Behind, so the bottom-most visible row is Behind only by chance, and the popup of the last visible Behind row usually has one or two rows below it.

By reading the code (architect review of M2-17b, 2026-11-15; nothing was run for this): `StatusHintControl` (`ui/.../streams/StreamStatusHint.kt`) draws the popup left of the "i", top aligned with it, extending downwards, with zero layout size (`floatingBesideLeft`). The consumers tables are items of one `LazyColumn` (`StreamScreen.kt`, `ClientsSections`), and a lazy list clips its content at the viewport. The popup is about 80 dp tall (4 lines of `bodySmall` plus 2 x 8 dp padding) and starts at the top of the "i", which is near the top of its row. In `clients-02c-hint-last.png` (1280 x 900 at density 1) the popup runs from y 765 to y 855 and the last row ends at about y 808, so it reaches about 45 dp below the bottom edge of its row. For a Behind row that is the bottom-most fully visible row of a long list, the viewport has only the 16 dp content padding left below the row, so the last lines would be cut (by about 30 dp). The same holds, a little less, for the old 3-line text, so this is not a regression of M2-17b. If it is real it is a defect of the focus and click popup only: the hover tooltip (`TooltipArea` in `StatusCell`) is a window-level popup placed by Compose.

## Scope
In:
- Probe first (test developer): in `StreamClientsLazyUiTest` (or a sibling class) a long list (the file's 5000 rows) where EVERY row is Behind; at the top and in the middle, focus the "i" of the bottom-most fully visible row (`visibleBehindRows().last()` then is that row) and apply the existing `popupProblems(...)` rule (inside the window, inside the list viewport, really drawn, off the header). Keep the existing every-third-row test as it is.
- If the probe is red: change `StatusHintControl` so the popup fits, without a second root, without moving the row, and keeping the tags `statusHint:<key>` and `statusHintPopup:<key>`. Suggested rule (the builder may choose another that passes): below by default; when it does not fit below inside the viewport, place it above the "i" (bottom aligned); when it fits in neither, clamp to the viewport edge. Check `AppFrame.kt` (`BelowAnchorEnd`, `PopupPositionProvider`) and the F-G notes in `StreamClientsHintPopupUiTest` before moving to a window-level `Popup`: the inline popup was chosen on purpose.
Out: the wording (M2-17b, closed), the footnote, the popup's colours and size, the hover tooltip, any change when the probe is green except keeping the new test as a guard.

## Acceptance
- UI: the probe above. Red for the right reason first (the popup rect leaves the list viewport), then green. If it is green from the start, say so in the brief's Status and close the task with the test kept; no production change.
- Every existing hint test stays green (`StreamClientsHintPopupUiTest`, `StreamClientsLazyUiTest`, `StreamClientsStatusUiTest`, `StreamScreenUiTest`), and the screenshots `clients-02a`, `02b`, `02c` still show the popup beside the "i", whole.
- BDD: none. This is a layout guarantee pinned by Compose UI tests, in the style of M2-06 F-G; no rule or wording a scenario describes changes.

## Design notes
Module `ui` only. No `core` change, no new dependency. Keep composables thin: if the "does it fit" decision needs arithmetic (space below, popup height, flip), put it in a small pure function that is unit-tested, and keep the composable to measuring and placing.

## UX
`docs/ux/Clients.dc.html` does not draw the popup placement; no mockup change unless the popup flips above, in which case add one line to the screen notes.

## MCP
None. The MCP tools return the number, not the tooltip or its placement.

## Open points
The overflow is a reading of the code, not a measurement (the architect did not run Gradle for this). Stop condition: probe green means no defect, close the task. Not an owner question unless the fix changes how the popup looks (flipping above is the only visible change).

## Definition of done
Probe written first and seen red (or green with the reason recorded), fix if needed, existing hint tests and screenshots green, no unrelated change, `docs/tasks/README.md` row added by the coordinator.
