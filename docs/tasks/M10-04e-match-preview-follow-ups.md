# Match preview follow-ups (empty-pattern sentence, by-eye check, small clean-ups)

Milestone: M10    Depends on: M10-04 (parts A and B)    Size: S

## Goal
The verdict of an empty pattern reads as a sentence a person understands, the form has been looked at in the real app, and two small clean-ups are done.

## Why
Review of M10-04 part B (2026-11-27). Today an empty (no permission) pattern gives "The write pattern no permission does not match ex.x on /orders." because `MatchPreviewTexts.hitSentence` puts `DisplayPattern.text` ("no permission") where a pattern goes. It is accurate and safe, but not good English.

## Scope
In:
1. APPROVED by the owner 2026-11-27 ("use recommended sentences"). Sentences:
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
CLOSED by the owner 2026-11-27 ("use recommended sentences"): (a) the two empty-pattern sentences of point 1 as proposed; (b) the R2 sentences as written ("The topic write pattern ^sensors\..* of amq.topic does not match the routing key alarms.fire.", read variant, "... does not match the empty routing key.", "... matches the empty routing key.", NotLoaded "The patterns of this user have not been read from the broker, so there is nothing to check against."); (c) R3: editing the form clears the answer.

## Definition of done
Tests first and passing, no unrelated changes, mockup, brief Status and `tests/bdd/README.md` updated.

## Status
- 2026-11-27, step 2 (test developer), points 1 and 4 only; points 2 (by-eye) and 3 (optional holder) are not in this run.
- Contract for the senior developer: `DisplayPattern(text, noPermission: Boolean = false)` (compile-only stub already in `AccessAnswer.kt`). `AccessMatcher` must set `noPermission = true` for the empty cell (also the no-row hit, your choice, `AccessMatcherTest` pins the empty cell and the empty topic cell) and `MatchPreviewTexts.hitSentence` must build the approved sentences from the flag, never from `text == NO_PERMISSION`. The no-row-on-vhost sentence ("<user> has no permission on <vhost>.") is unchanged. Point 4 (the one-line comment next to `name` in `MatchPreviewViewModel`/`MatchPreviewState`) is yours.
- Red: `AccessMatcherTest` 3, `MatchPreviewViewModelTest` 3 (core), `@m10-04` 4 runs (empty write, read, configure, empty topic write). Already green by design (guards): the literal "no permission" pattern in `MatchPreviewViewModelTest` (2) and in `AccessMatcherTest`, and the `@m10-04` scenario `A pattern that is spelled "no permission" is shown as a pattern`.
- Mockup: `docs/ux/Access.dc.html`, frame `perm-verdicts`, new block "Would not match: an empty pattern".
- 2026-11-27, step 3 (build, senior developer), points 1 and 4 done; points 2 and 3 still open. `AccessMatcher` sets `noPermission = true` for the empty cell and for the no-row hit (the no-row sentence is still built from `noRowForVhost`, so it is unchanged and the flag is only for consistency); `MatchPreviewTexts.hitSentence` builds the approved sentences from the flag. Comment next to `name` in `MatchPreviewViewModel`. Green: `:core:jvmTest`, `:ui:jvmTest`, `:mcp-server:test`, `:acceptance-tests:test` (access feature 53 runs, 0 failed). Not run: `brokerTest`.
- 2026-11-27, step 4 (verify, test developer, HEAD 93b35998): PASSED, no finding. Fast suites: core 3988, ui 1450, mcp-server 45, data-rabbit 1108, desktopApp 219, 0 failed. `RunAccessViewAcceptanceTest` as its own command: 53 runs in the access feature, 0 failed, 1 skipped (the `@broker` gate). Full `:acceptance-tests:test` 2160 runs, 0 failed, 1178 skipped (not selected by the runners, as before). `RealBrokerAccessMatchTest` (live, Docker) green. Mutation check: reading the empty cell from `text == NO_PERMISSION` instead of the flag turns the two literal-"no permission" tests red, so they exercise the flag. `git diff main..HEAD` touches only access core code, its tests, the feature, steps, mockup and docs. Not run: `brokerTest` in full, keychain and screenshot tasks, packaging; points 2 and 3 stay open.
- 2026-11-27, step 5 (architect review, HEAD b7fa8faa): ACCEPTED WITH FOLLOW-UPS (points 1 and 4 accepted, no blocker, no major). Sentences match the owner-approved wording exactly (write, read, configure, topic write, topic read; no-row sentence unchanged); the flag, not the text, decides; mockup frame `perm-verdicts` carries the same words; `tests/bdd/README.md` counts (29 scenarios, 51 runs, 50 fake-broker) consistent. Findings: (1) idea, `DisplayPattern.noPermission` has a default `false`, so a new construction site cannot forget it at compile time; the only two sites are in `AccessMatcher` and set it explicitly, tests rely on the default for real patterns; keep. (2) idea, the no-row hit sets the flag only for consistency (its sentence comes from `noRowForVhost`); fine, and the test pins it. (3) minor, the flag and `text` can disagree (`DisplayPattern("x", true)`); acceptable, `text` is kept only so the pattern is copyable/displayed as before. (4) MCP: the flag fits `M10-08`; `caerbannog_check_access` must build its sentence or its `deciding` field from `noPermission`, never from `text == NO_PERMISSION` (line added to the `M10-08` brief). Follow-up: points 2 (by-eye in the real app) and 3 (optional holder) are SPLIT into the new brief `M10-04f`; `M10-04e` is closed after merge. Not run by the architect: any test (relied on the step 4 report), the app, `brokerTest`; not read line by line: the generated mockup HTML beyond the verdict frame sentences and `AccessMatchCases`.
