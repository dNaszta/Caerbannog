# Match preview by-eye check and optional holder

Milestone: M10    Depends on: M10-04e merged    Size: S

## Goal
The match form has been looked at in the real app against `docs/ux/Access.dc.html`, and the Permissions tab's state and events travel as one holder (optional).

## Why
Points 2 and 3 of `M10-04e`, left open by its review (2026-11-27). Dimmed frames, wrapping and the host dropdown cannot be proven by the Compose tests alone.

## Scope
In:
1. By eye (owner or developer with the real app, `task run`, dev broker): frames `perm-admin`, `perm-verdicts`, `perm-locked`, `perm-token`, `layout-narrow-perm`; wrap at narrow width and font scale 1.5; the host dropdown; Check with a long routing key; the new empty-pattern sentences. Record the result (and any defect as its own finding) in the Status of `M10-04`.
2. Optional: group `permissions`, `permissionsEvents`, `matchPreview`, `matchPreviewEvents` of `AccessScreen` in one small data class (`ui` only, no behaviour change, `testTag`s unchanged).
Out: any change to matching, sentences or MCP.

## Acceptance
- BDD: none new; `@m10-04` scenarios and `MatchPreviewUiTest`, `PermissionsTabUiTest` stay green.
- By-eye result written down.

## Design notes
`ui` only. No broker call, no dependency.

## UX
`docs/ux/Access.dc.html`; fix the code or the mockup, and say which.

## MCP
None (UI-only).

## Open points
None.

## Definition of done
By-eye result recorded; if point 2 of the scope is done, UI tests green and no unrelated change.

## Status
- Briefed 2026-11-27 (architect).
