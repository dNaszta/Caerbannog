# Permissions tab follow-ups (review of M10-03)

Milestone: M10    Depends on: M10-03    Size: S

## Goal
Four small loose ends of the M10-03 review are closed: the "no user" edge case is tested and its text decided, each Access view model reads only while its own tab is selected, the Permissions labels live in `core`, and the gateway has no crash branch.

## Why
Review of M10-03 (2026-11-27), rulings 3, 5, 2 and findings 1 to 4 of the report. Spec "Access view (M10)", D3.

## Scope
In:
1. Test first: an administrator whose user list offers no selectable name (empty list, or every name hidden) gets the Failed frame with Retry and no permission request. Today `PermissionsViewModel` shows `NO_USER` ("The broker lists no user to choose.") as a "Broker text:" detail, which is not a broker text. Use the owner's decision; until then keep the sentence as the message and drop the "Broker text:" prefix for it.
2. `AccessScreen` reports `AccessEvents.setShown(tab == WhoAmI)` and `PermissionsEvents.setShown(tab == Permissions)`; the Who am I view model no longer polls while the Permissions tab is selected. The app bar age and Refresh follow the selected tab (already so for Refresh). Scenario or UI test: selecting the Permissions tab stops whoami and vhost requests.
3. Move the column, heading, filter-hint and note constants of `PermissionsTab.kt` into `PermissionsTexts` (core) so tests refer to them.
4. `ManagementAccessGateway.withoutValue()`: replace the `Loaded -> error(...)` branch with a shape that cannot throw (for example a generic `AccessResult<T>.failureOrNull()`), keeping the 404 rule.
Out: tooltip for the empty pattern (dropped, M10-03 ruling 1 of step 5), any new wording beyond item 1, the preview block (M10-04).

## Acceptance
- BDD: none new; a view model test for item 1, a UI or step test for item 2 (`@fake-broker`, `@m10-03`).
- All `@m10-03` and `@m10-02` scenarios stay green.

## Design notes
`core/access/PermissionsViewModel.kt`, `ui/access/AccessScreen.kt`, `ui/access/PermissionsTab.kt`, `data-rabbit/.../ManagementAccessGateway.kt`. No new module boundary.

## UX
`docs/ux/Access.dc.html`, frame `perm-error` for item 1 (no new frame).

## MCP
None (UI-only follow-up).

## Open points
Wording of item 1 awaits the owner (non-blocking; the review report lists the options).

## Definition of done
Tests first and passing, no unrelated changes, README line updated.
