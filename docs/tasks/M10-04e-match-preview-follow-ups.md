# Match preview follow-ups (empty-pattern sentence, by-eye check, small clean-ups)

Milestone: M10    Depends on: M10-04 (parts A and B)    Size: S

## Goal
The verdict of an empty pattern reads as a sentence a person understands, the form has been looked at in the real app, and two small clean-ups are done.

## Why
Review of M10-04 part B (2026-11-27). Today an empty (no permission) pattern gives "The write pattern no permission does not match ex.x on /orders." because `MatchPreviewTexts.hitSentence` puts `DisplayPattern.text` ("no permission") where a pattern goes. It is accurate and safe, but not good English.

## Scope
In:
1. NEEDS OWNER (does not block the merge; ships as is until answered). Proposed sentences, recommendation: approve.
   - Non-topic empty cell: "svc-orders has no write permission on /orders, so nothing is allowed." (read: "no read permission", configure: "no configure permission").
   - Topic empty cell: "svc-orders has no topic write permission on amq.topic, so nothing is allowed." (read: "topic read").
   Build it in `MatchPreviewTexts.hitSentence` when `hit.pattern.text == PermissionsTexts.NO_PERMISSION` (better: carry a flag or a sealed `DisplayPattern` so a real pattern spelled "no permission" is not mistaken; today the broker could return that text as a pattern, so the check by text is a smell). Update the tests that pin the old text (search "no permission" in `MatchPreviewViewModelTest`) and the `@m10-04` scenarios that name an empty pattern.
2. By-eye check in the real app against `docs/ux/Access.dc.html` (`perm-admin`, `perm-verdicts`, `perm-locked`, `perm-token`, `layout-narrow-perm`): dimmed frames, wrap at narrow width and font scale 1.5, the host dropdown, Check with a long routing key. Record the result in the Status of the M10-04 brief.
3. `AccessScreen` now threads four parameters (`permissions`, `permissionsEvents`, `matchPreview`, `matchPreviewEvents`) through five composables. Idea, optional: group the Permissions tab's state and events in one holder (a small data class) so the next tab does not add more.
4. `MatchPreviewState.name` keeps what the user typed, also a typed password, in the box. This is the user's own text in their own box and never leaves the form, so it is accepted; record it as a one-line comment next to `name` in `MatchPreviewViewModel` so nobody later copies the state into a log.
Out: any change to matching, to the matcher, to MCP.

## Acceptance
- BDD: `22_access_who_am_i_and_permissions.feature` / the `@m10-04` scenarios that check an empty pattern (text updated once the owner answers); all other `@m10-04` scenarios stay green (`RunAccessViewAcceptanceTest`).
- Unit: `MatchPreviewViewModelTest` pins the new sentences; a pattern the broker returns as the literal text "no permission" is shown as a pattern (point 1).

## Design notes
`core` only for point 1 and 4; `ui` only for point 3. No broker call, no new dependency.

## UX
`docs/ux/Access.dc.html`: the sentence is not drawn for the empty-pattern case; update the mockup verdict frame with the approved wording.

## MCP
None (UI-only). The matcher is reused by `M10-08`; its wording is built there from the same `AccessAnswer`.

## Open points
Owner: approve or change the two sentences of point 1 (also still open from the R2 ruling: "does not match the routing key", "empty routing key", the not-loaded sentence; recommendation: approve as written).

## Definition of done
Tests first and passing, no unrelated changes, mockup, brief Status and `tests/bdd/README.md` updated.
