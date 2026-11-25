# M10-03: Permissions tab (configure, write, read, topic permissions per virtual host)

Milestone: M10    Depends on: M10-02, M10-00 (S-B)    Size: M

## Goal
For an administrator profile the Permissions tab shows, for a chosen user, the configure, write and read patterns and the topic permissions per virtual host, as the broker returned them; for everyone else it says that only an administrator can read them and shows the profile's declaration labelled as declared.

## Why
Spec "Access view (M10)", goal 2; "Broker-read permission" (only an administrator can read, measured M1-03) and "Declared permissions" (a declared result never looks like a broker answer). Builds on "Effective permission"; it does not change it.

## Scope
In: `AccessGateway` calls for users, a user's permissions and topic permissions (GET only: `/api/users`, `/api/users/{user}/permissions`, `/api/topic-permissions` as S-B confirms); the model `PermissionRow(vhost, configure, write, read)` and `TopicPermissionRow(vhost, exchange, write, read)` with a `PatternReading` (`NoPermission` for the empty pattern, `HasPattern(text)` for any other; `^$` counts as a pattern, as in "Broker-read permission"); user chooser (AQ7); a vhost with no row shown as no permission; the declared-permission display with its own wording and style; state "cannot be read" for a non-administrator and for a token user when S-B says nothing is returned (then the text of `M10-05` applies); stale and loading as D2/D3. The user list never shows a password or a hash (`GET /api/users` carries `password_hash`: the parser must not even keep the field; test it).
Out: any change of permissions (non-goal), the preview (`M10-04`).

## Acceptance
- BDD (`22`, tag `@m10-03`): "An administrator sees configure, write and read patterns per virtual host" (@smoke), "An empty pattern reads as no permission" (outline), "Topic permissions are listed per exchange", "A user who is not an administrator is told that permissions cannot be read", "A declared permission is never shown like a broker answer", "An administrator may choose any user on the broker", "A user with no permission on a virtual host is shown as having none".
- Unit: parser tests on the fixtures of `M10-00` (S-B), a test that `password_hash` is not retained anywhere (not in the model, not in `toString`, not in logs), view model states.
- UI test: tags on rows and the chooser.

## Design notes
`core/access`, `data-rabbit`, `ui/access`. The patterns are shown verbatim (they are not secrets). Pattern text is never evaluated here. The profile's declaration comes from the existing permission model (`M1-06`); do not duplicate it.

## UX
Permissions tab of `Access.dc.html`: table per virtual host, topic permission table, user chooser, locked state with the reason, declared block.

## MCP
Exposed in `M10-08` through `caerbannog_get_access_summary` with `includePermissions`; administrator only, "not readable" otherwise.

## Open points
AQ2 (what a non-administrator sees), AQ7 (any user or only the connection user). S-B decides whether a token user can be shown at all.

## Definition of done
Tests first and passing; no write call exists in the new code (a test asserts the gateway interface has no non-GET method); README and tags updated.
