# M10-03: Permissions tab (configure, write, read, topic permissions per virtual host)

Milestone: M10    Depends on: M10-02 (merged), M10-02b (merged), M10-00 (S-B)    Size: M

Branch: `M10-03-permissions-tab`

## Status
Step 1 (brief) done (architect, 2026-11-27, main ed92ac65; PD-1 decided by the owner the same day). Step 2 (tests first) next. Round 0. No open questions. Ruling 6 got the token-administrator exception (own name as default and extra chooser entry, owner 2026-11-27); scenario "A token administrator lands on its own name with the token note" added.

## Goal
For a user with the administrator tag the Permissions tab shows, for a chosen user of the broker, the configure, write and read patterns and the topic permissions per virtual host, as the broker returned them; for everyone else it says that only an administrator can read them and shows the profile's declaration labelled as declared. Nothing is sent that changes anything.

## Why
Spec "Access view (M10)", goal 2; "Broker-read permission" (only an administrator can read, measured M1-03, confirmed for tokens by S-B) and "Declared permissions" (a declared result never looks like a broker answer). AQ2 and AQ7 are answered by the owner (2026-11-25). Builds on "Effective permission"; it does not change it.

## Rulings by the architect (2026-11-27)
1. **The lock is decided by the tags, with no request.** `whoami.tags` without `administrator` means the locked state, and no permission endpoint is called ("nothing is guessed from other calls"; S-B measured 401 for `monitoring` and `management`, so the call would only fail). With the tag, a 401 or 403 on a permission call is the existing `AccessResult.NotAuthorised` (the refusal banner of M10-02).
2. **Calls (all GET).** `GET /api/users` (names only: the parser keeps `name` and drops every other field, `password_hash` and `tags` included), then for the chosen user `GET /api/users/{user}/permissions` and `GET /api/users/{user}/topic-permissions` (S-B measured the first; the second and the field names of `/api/users` and of the topic permission rows are NOT yet in a fixture: the first step 2 test is a live capture, see Open points). Not `/api/permissions` (it returns every user's rows). User and vhost names are URL-encoded as path segments (`MigrationRequests.encode`; the vhost `/` is `%2F`).
3. **One call, one result.** `permissionsOf(user)` does both GETs and returns `Loaded` only when both parse; no half table.
4. **A 404 for the chosen user is `AccessResult.NotFound`** (new case of the sealed result; the existing `when`s become exhaustive again). It is how a token user answers (S-B). The tab shows the token-user text of the perm-token frame; it is never an error.
5. **Hidden names are not selectable.** A user name that `SecretGuard.redactAll` hides (it looks like a token) is listed once as the existing hidden marker, disabled, keyed by its position (same lesson as M10-02b: `accessUserOption:hidden:<n>`, 0-based). The real name is never kept, so it can never be requested. Patterns, vhost and exchange names also pass `SecretGuard`; a hidden pattern shows the hidden marker and carries `hidden = true` so M10-04 treats it as "pattern could not be read", never as a regular expression.
6. **Default chosen user.** The connection user when it is in the list, otherwise the first name by alphabet; no new wording. Exception (owner, PD-1 (a), 2026-11-27): a token administrator (not an internal user, tag `administrator`) lands on its own name, which shows the token note (the broker answers 404), and the chooser offers its own name as an extra entry besides the internal users; choosing an internal user shows that user's permissions. The choice is per tab visit, not stored.
7. **Rows per virtual host.** One row per virtual host that `virtualHosts()` lists for this user, plus any permission row for a vhost not in that list (shown, never dropped); a vhost with no row is drawn with "no permission" in all three cells. Sorted alphabetically (the mockup order is illustrative). A user with no permission row and no topic row at all gets the empty frame ("has no permissions on any virtual host"), not an error.
8. **Reading of a pattern.** `PatternReading.NoPermission` for the empty string, `HasPattern(text)` otherwise (`^$` is a pattern, spec "Broker-read permission"). The model keeps the raw text; the empty pattern itself is the cell's tooltip, as drawn. Pattern text is never evaluated in this task.
9. **Declaration.** Read from the held profile's `DeclaredPermissions(configure, write)` (M1-06); the model has no read flag, so "Read not declared" is always drawn. Shown only in the locked frames and in the "broker could not be asked" case, with the label "Declared by you, not confirmed by the broker" and a style that differs from the "Read from the broker" label. Do not copy the declaration model.
10. **View model.** The tab state lives apart from `AccessState` (`PermissionsState`: user list, chosen user, vhost filter, rows, topic rows, status), but the banners (connection lost, refusal, read error, Not encrypted) and the refresh rule come from the same code as the Who am I tab, extracted, not copied. Refresh uses the app-wide interval (D3) only while the tab is shown, and re-reads the chosen user (and the user list on Retry); leaving the tab cancels.

## Decision PD-1 (owner, 2026-11-27)
Option (a). A token user with the `administrator` tag gets the chooser of internal users plus the token note for its own name; a token user without the tag gets the locked state; the token-only frame appears only when the chosen user answers 404. Ruling 1 stays as written (the tags decide the lock, no request). Mockup: `perm-token-admin` in `docs/ux/Access.dc.html` (proposed, no new wording).

## Scope
In:
- `AccessGateway` gets `users(): AccessResult<UserNames>` and `permissionsOf(user): AccessResult<UserPermissions>`; `UnavailableAccessGateway`, `FakeAccessGateway` and `ManagementAccessGateway` follow. Models in `core/access`: `PermissionRow(vhost, configure, write, read)` and `TopicPermissionRow(vhost, exchange, write, read)` over a `PatternCell(text, reading, hidden)`; `UserPermissions(user, rows, topicRows)`.
- Permissions tab in `ui/access`: user chooser with its filter box (no paging, no free typing), vhost filter, the table, the topic table, the states below.
- States: loading, empty, error (with Retry), locked (not administrator, with and without declaration), token user (404), stale (D2), Not encrypted. 
Out: any write of users, tags, permissions or vhosts (non-goal); the match preview and `AccessMatcher` (`M10-04`); the Sign-in tab (`M10-05`); the MCP tool (`M10-08`); a cap or paging for thousands of users (see idea in Open points).

## Acceptance
- BDD, `tests/bdd/features/22_access_who_am_i_and_permissions.feature`, tag `@m10-03`, all `@fake-broker`:
  - "An administrator sees configure, write and read patterns per virtual host" (@smoke)
  - "An empty pattern reads as no permission" (outline, four rows)
  - "Topic permissions are listed per exchange"
  - "A user who is not an administrator is told that permissions cannot be read" (also asserts no permission request was sent)
  - "A declared permission is never shown like a broker answer"
  - "An administrator may choose any user on the broker" (also: no password, no hash)
  - "A user with no permission on a virtual host is shown as having none"
  - added by this brief: "A user with no permission on any virtual host gets the empty message", "The Permissions tab says that the broker lists no permissions for a token user" (chosen user answers 404), "A user whose name looks like a token cannot be chosen", "An unreadable permission list keeps the last data and says so", "The Permissions tab sends only GET requests" (@security).
  - "A token administrator lands on its own name with the token note" and "A token administrator may choose an internal user" (PD-1, decided).
- Unit (written first): parser tests on the S-B fixtures (`s-b-i-admin-user-permissions-of-i-admin.json`, `s-b-t-admin-user-permissions-of-t-narrow.json`, `s-b-t-mon-denied.json`) plus the new live-captured fixtures; a test that `password_hash` and `tags` of a user are not retained (model, `toString`, logs); the gateway sends no request without the administrator tag; the gateway's recorded requests are all GET (no non-GET method exists on `ManagementHttp` use here); view model states and the default-user rule; hidden user and hidden pattern.
- UI test (`AccessScreenUiTest` style): tags on tab, chooser, chooser filter, rows, topic rows, banners, `accessPermissionsDeclared`, Retry.
- Gate hook: a case in `RealBrokerM10GateTest` is `M10-09`'s job; here only the live capture of fixtures.

## Design notes
- Modules: models, state, view model in `core/access` (no `java.*`); HTTP in `data-rabbit` `ManagementAccessGateway` and `AccessRequests` (new GET paths only); screen in `ui/access`, composables thin; wiring in `desktopApp` (`AccessEventsAdapter` neighbours).
- Everything that leaves the gateway has passed `SecretGuard` with the held secret (read per call, never stored). Errors carry a status text only, never a body.
- Fit for `M10-04`: the model keeps the raw pattern text next to its reading, with `hidden`, and `UserPermissions` is a plain value the matcher can take as input without touching the gateway; the topic rows already carry `exchange` and the default-exchange rule (`amq.default`) needs no change here. M10-04 adds the preview block below the tables and `AccessMatcher` in `core`, with no change to this task's types.
- Where the broker is faked: all scenarios and unit tests use `FakeAccessGateway`; no live broker in the fast suite.

## UX
`docs/ux/Access.dc.html`, frames `perm-admin`, `perm-chooser`, `perm-empty`, `perm-locked`, `perm-locked-nodecl`, `perm-token`, `perm-error`, `perm-stale`, `perm-plain`, `layout-narrow-perm`. Wording is approved (owner, 2026-11-25). The preview block that these frames also draw is NOT built in this task (M10-04); the tab ends after the topic table. Mockup gaps for step 3 to follow by ruling above: default user (6), hidden user option (5), row order (7). Owner review of the mockup as a whole is still pending (spec "Open", M10 line); this task does not change that.

## MCP
None in this task (UI-only slice). The tool decision is recorded: exposed in `M10-08` through `caerbannog_get_access_summary` with `includePermissions` (spec "Access view (M10)", "MCP evaluation", AQ9 answered); administrator only, "not readable" otherwise; same `SecretGuard`, bounded size. No new tool is created here.

## Open points
- PD-1 CLOSED (owner, 2026-11-27, option a): see "Decision PD-1".
- Unverified: field names of `GET /api/users` (assumed `name`, plus fields we drop), shape of `GET /api/users/{user}/topic-permissions` (assumed `user, vhost, exchange, write, read` from the management API documentation, not from a fixture), and the 401 body for a non-administrator with a management tag (not needed: ruling 1 sends no request). The first step 2 test is a live capture on the throwaway broker of the spike rig (`RealBrokerAccessSpikeTest` style, tags `broker` and `spike`) that stores the fixtures `s-b-users-*` and `s-b-topic-permissions-*` with `password_hash` removed before saving (`AccessFixtureGuardTest` must stay green). Until it runs, parser tests use hand-written bodies marked as such.
- Idea (not now): a broker with thousands of users makes `GET /api/users` large; names only are kept, so memory is small; a cap with a visible "more users not listed" count would need new wording (owner). Left open, not in scope.
- Idea: the vhost filter and the user filter share the box component of Clients (`M10-06`); extract when the second use arrives.

## Definition of done
Tests first and passing (scenarios above, unit, UI); no write call exists in the new code (test asserts it); no `password_hash` retained anywhere; no unrelated changes; `docs/tasks/README.md` and `tests/bdd/README.md` updated; spec unchanged (PD-1 was decided as the default).
