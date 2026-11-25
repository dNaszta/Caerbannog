# M10-02: Secret guard, access gateway and the Who am I tab

Milestone: M10    Depends on: M10-00 (S-A, S-D, S-N, S-O), M10-01 for the screen    Size: M

## Goal
The Access screen opens from the connection that is held and shows the connection user, its tags and the virtual hosts the broker lists, read only, with all states; and one core component, `SecretGuard`, exists that every later Access slice, the logs and the MCP server use to keep secrets out.

## Why
Spec "Access view (M10)", goal 1 and "Redaction rules"; "Screens and UX" (Topology read-only rule, owner decision Q15); 3.x rule (M8). The first slice that works end to end; the guard comes first because every other slice depends on it.

## Scope
In, in three commits, each green:
1. **`SecretGuard` in `core`** (pure, no `java.*`): the allow-list of connection and channel fields, the shape scan (JWT, `Bearer `, `scheme://user:password@`, `password=`, `secret=`, `token=`), the held-password scan (the holder passes the secret in for the scan; the guard never stores it), the "hidden" replacement with a counter, the mapped error reasons. Unit tests first, with the redaction fixtures of spike S-N. Property test: for random strings built from a secret plus noise, no output contains the secret or its segments longer than 6 characters.
2. **`AccessGateway` interface in `core`** (`whoAmI()`, `virtualHosts()`; later slices add their calls) with a fake in `core-testing`, and the `data-rabbit` implementation over the existing management client (GET only, the profile's TLS setting, no new HTTP stack). Typed results: `Loaded`, `NoManagementTag`, `NotAuthorised`, `Unreachable`, `Unexpected` (reason only, never a body). 3.x connections: the gateway refuses and sends nothing (AQ6 default).
3. **`AccessViewModel` in `core` and the screen shell in `ui`**: state in, events out; the tab Who am I; the other tabs are present but empty ("not built yet" is not shown to users: tabs appear as their slices land). Navigation entry per AQ1. Refresh through the shared refresh driver (D3), only while shown; stale state and the "Connection lost" banner as D2; "Not encrypted" banner on a plain profile; no credential is sent before the connection is held (reuse the held-connection rule).
Out: permissions, sign-in tab, clients, MCP.

## Acceptance
- BDD (`tests/bdd/features/22_access_who_am_i_and_permissions.feature`, tag `@m10-02`): "Who am I shows the user, the tags and the visible virtual hosts" (@smoke), "A user without a management tag gets a message and no data", "The Access view has no control that changes anything", "Nothing is read before the user has chosen the connection", "A profile without TLS shows Not encrypted on the Access view", "A lost connection keeps the last data and says so", "The virtual host list cannot be read", "The view is not offered for a RabbitMQ 3.x connection" (`@needs-decision`, AQ6).
- Unit tests: `SecretGuardTest` (shapes, allow-list, held password, counter, never logs), `AccessViewModelTest` (every state), gateway parser tests on the fixtures of `M10-00`.
- Compose UI test with `testTag`s for every state; tags stable.

## Design notes
`core/access` (new package), `data-rabbit` gateway next to the management client, `ui/access`, wiring in `desktopApp` composition root. No broker or Ktor type in `core` or `ui`. The guard is called at the gateway boundary (data leaves `data-rabbit` already redacted), not only in the UI, so a later screen or tool cannot forget it. Fakes behind the same interface. Unit tests with fakes; one live test of the gateway against the broker of `RealBrokerAccessSpikeTest`'s fixture style is left to `M10-09`.

## UX
`docs/ux/Access.dc.html` (`M10-01`): Who am I tab, states empty, loading, error, locked (no tag), stale, plain profile.

## MCP
Exposed later in `M10-08` (`caerbannog_get_access_summary`). This task makes the guard shareable with `:mcp-server` (it lives in `core`).

## Open points
AQ1 and AQ6 are decided: a drawer item "Access", absent on a 3.x connection, with a return to Connections and a one-line notice if the connection becomes 3.x while Access is open (scenarios 22, `@m10-02`). Who am I has no Sign-in line (review of the mockup, Q1). Measured by M10-00 (changed 2026-11-25): `GET /api/whoami` returns `{name, tags, is_internal_user}`; a user without a tag gets 401 (the locked state); `is_internal_user = false` marks a token user and is the only signal for it. `GET /api/vhosts` lists for a token user only the virtual hosts its scopes reach. Basic auth with the token as password and Bearer both work; the user name sent is ignored. **`SecretGuard` allow-list: `user_provided_name` is scanned and replaced exactly like `connection_name`** (it echoes client text verbatim, also in `client_properties`; S-N). A client property `password` also comes back verbatim, so everything outside the allow-list is dropped. No token text was found in any response (S-D, S-M), and error bodies are the generic `Not_Authorized`. Facts: `docs/tasks/M10-00-spike-results.md`, spec "Measured facts of M10-00".
Which `/api/vhosts` fields exist for a non-admin is answered (S-A). Until the spike, field names are not asserted.

## Definition of done
Tests written first and passing, no unrelated changes, README rows and scenario tags updated, `task test` green (report what was not run).
