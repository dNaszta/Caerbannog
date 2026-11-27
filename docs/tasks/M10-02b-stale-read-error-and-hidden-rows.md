# Access view: say why the data is stale after an unreadable whoami, and key hidden rows

Milestone: M10    Depends on: M10-02    Size: S

## Status
Step 1 brief done. Step 2 tests done (commit 672e07bb, red for the right reasons, compile-only stub `AccessState.readErrorBanner: String? = null`). Step 3 built (HEAD f6fa4d8a), step 4 test run PASS. Step 5 architect review round 0: Accepted with follow-ups (2026-11-27), see the findings in the review answer; the proposed frame "Who am I: stale (the user cannot be read, D2)" is added to `docs/ux/Access.dc.html` (marked proposed). Ready to merge on the owner's word.

## Rulings (architect, 2026-11-27, after step 2)
1. Field `AccessState.readErrorBanner` holds `"${AccessTexts.USER_ERROR} ${brokerText(reason)}."` (same shape as the refusal banner). `AccessScreen` appends `AccessTexts.CONNECTION_LOST_DETAIL` when drawing. The full sentence in Scope 1 is the drawn text, not the field.
2. `accessVhostRow:hidden:<n>` and its note tag: `<n>` is the 0-based index of the row in the shown vhost list (not 1-based, not counting only hidden rows).
3. While `readErrorBanner` shows, the vhost list error must not draw its own Retry (`accessVhostsRetry`): the banner's Retry is the only one. The list error text may stay.
4. `readErrorBanner` is never set together with the lost-connection or refusal banner. Precedence: refusal, then lost connection, then read error; `succeeded()`, `clearData()`, `lock()` and a lost connection or refusal clear it.
5. The proposed frame in `docs/ux/Access.dc.html` is the architect's job (docs only, no code, not blocking step 3); the developer does not touch `docs/ux/`.

## Goal
When the Who am I data is on screen and a later `whoami` answers `Unexpected` (for example HTTP 500), the screen says so with a banner and a Retry button, the same way as the lost-connection and refusal cases; and two virtual host names that were both hidden by the guard can be told apart by the code that tests them.

## Why
Review of M10-02 (2026-11-27), findings 1 and 2. Today `AccessViewModel.unreadable` sets `stale = true` and nothing else: the content is dimmed (alpha 0.6) with no text, and `AccessState.action` is "Retry" but `AccessScreen` draws no Retry for that case (the banners carry their own, the vhost list error carries its own). Spec D2: stale data is marked and says why.

## Scope
In:
1. `AccessState`: one new nullable field for the read-error banner, set only while data is shown and the last read was `Unexpected`; text "The user could not be read. Broker text: <mapped reason>. The data below is the last that was read." (`AccessTexts.USER_ERROR` plus `brokerText(reason)` plus `CONNECTION_LOST_DETAIL`; no new wording, all three strings are approved). Cleared by `succeeded()`, `clearData()` and `lock()`.
2. `AccessScreen`: the banner uses `LostConnectionBanner` like `accessRefusalBanner`, tag `accessReadErrorBanner`; the refresh icon is disabled while it shows (same rule as the other banners).
3. Hidden rows: the row tag and the note tag use the position when the name is `SecretGuard.HIDDEN` (`accessVhostRow:hidden:<n>`), so two hidden names do not share a tag. Other tags unchanged.
Out: the connection's own virtual host when its name is hidden by the guard (accepted: a virtual host named like a JWT is not a real case; the first position and the note are lost and nothing leaks). Wording changes. The other tabs.

## Acceptance
- Unit (`AccessViewModelTest`): "whoami ok, then whoami answers HTTP 500: last data stale with the read-error banner and Retry, user and tags kept"; "the next good read clears it". Written first.
- Compose UI test (`AccessScreenUiTest`): banner text and tag, Retry calls `retry`, refresh disabled, content dimmed; two hidden vhost rows both present.
- BDD: add one scenario to `22_access_who_am_i_and_permissions.feature`, tag `@m10-02b`: "An unreadable user while data is shown keeps the data and says so". @fake-broker style of the existing 22 scenarios.

## Design notes
Same pattern as `refusalBanner`; do not reuse the field (a refusal and a read error have different texts and later different chips in M10-10). No gateway change.

## UX
`docs/ux/Access.dc.html`: no frame for this case. Add "Who am I: stale (the user cannot be read)" next to the refusal frame, marked "proposed", same shape as the refusal banner. No owner decision needed: no new wording.

## MCP
None (UI-only): a screen state; the MCP summary tool is M10-08.

## Open points
Whether the refresh timer keeps ticking after an `Unexpected` on a shown screen was not verified in review; the test above should assert it (the next tick reads again).

## Definition of done
Tests first and passing, no unrelated changes, README row updated.
