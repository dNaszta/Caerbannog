# Permission check (domain, management API adapter, spike)

Milestone: M1    Depends on: M1-02-connection-profiles (done, in main)    Size: M

## Goal
For a saved connection, the app can run a permission check that reads the connection user's configure, write and
read permissions on the profile's virtual host through the management API over HTTPS, and returns a typed result:
topology locked or unlocked, test publishing enabled or disabled, or "could not be read" (safest mode). The check
runs before the first use of a connection and again after the profile is saved with changes. The open spike
question (can a non-administrator read their own permissions?) gets an answer recorded in the spec.

## Why
Spec "Topology migrations and permission guardrails", "Broker-read permission" (this task is exactly that half;
the self-constraint half exists since M1-02 as `TopologyControlLevel`). It is the last open M1 item and
blocks every M2+ screen that needs to know what to offer. Rule: no configure means read-only; no write disables
test publishing; unreadable means safest mode.

## Scope
In:
- `core`: `BrokerPermissions` (configure, write, read patterns; empty pattern = no permission), `PermissionCheckResult`
  sealed type (`Checked(topologyUnlocked, publishingEnabled)`, `Unreadable(reason)`), `PermissionChecker` interface
  (suspend, takes profile + secret), and a small use case / session gate `ConnectionSession.open(profile)` that runs the
  check first and only then reports the connection as usable; `ConnectionsViewModel` (or the save path) re-runs the
  check when an existing profile is saved. Pure decision function: patterns to locked/unlocked, enabled/disabled.
- `data-rabbit`: `ManagementApiPermissionChecker` using the existing Ktor Java client and TLS/CA setup from
  `StreamAndManagementReachabilityChecker` (reuse, do not duplicate the SSLContext code: extract if needed).
  HTTPS only, port 15671 default. Never logs the password.
- Spike (first, step 1): on a real broker (Testcontainers, `@broker`), create a user with no tags and one with
  `monitoring`, give each vhost permissions, and record which management endpoint(s) each can call to read its
  own permissions. Candidates to test, none assumed to work: `GET /api/permissions/{vhost}/{user}`,
  `GET /api/users/{user}/permissions`, `GET /api/whoami`. Write results into this brief's Status and into
  `docs/spec.md` (move the open question to Resolved, or record the fallback that stays).
- Fake `PermissionChecker` in `acceptance-tests` (existing `Fakes.kt` style).

Out:
- Sidebar "Topology locked" pill and topology screen text (new task `M1-07`, scenario tagged `@needs-M1-07`).
- Any real declare, publish or stream list (M2, M5). Combining with the self-constraint level into an effective
  permission (M5 enforcement; this task exposes only the broker half).
- The `@broker` scenario "The broker has the final say" (needs queue declare, M5; keep untouched).
- Changing the Connections screen layout beyond showing nothing new; the "Permission check" banner in
  `docs/ux/Main.dc.html` edit panel comes with `M1-07`.

## Acceptance
- BDD (`tests/bdd/features/02_permission_check.feature`), all `@fake-broker`, run in the default Cucumber run:
  - "What the app unlocks depends on the user's permissions" (all four examples) @smoke
  - "The check runs before the first connection is used"
  - "The check runs again when the profile is edited"
  - "Permissions cannot be read for this user" (@needs-spike; passes with the fallback: message "permissions could not
    be read", topology locked, publishing disabled). The step "streams can still be listed and browsed" asserts the
    capability flag only (no stream list exists yet); say so in the step name or comment.
- Deferred by tag, do not implement steps: "A locked connection says so in the sidebar..." (`@needs-M1-07`),
  "Browsing streams does not need the configure permission" (`@needs-M2`). Update the Cucumber tag filter to exclude
  `@needs-M1-07` too.
- Unit (core): decision function for the four patterns plus a pattern that is not `.*` (for example `^orders\\..*`
  counts as configure present); non-empty means allowed, since the broker has the final say.
- `@broker` (new, skipped without Docker like the existing ones): `ManagementApiPermissionChecker` against a real TLS
  broker for an admin user and for the non-admin user from the spike. Also a test with a wrong password and a
  refused connection producing `Unreadable`, not an exception.
- Rule checks (review): HTTPS only; no password in logs or exception messages; permission errors never default to
  unlocked.

## Design notes
- Interfaces in `core`, adapter in `data-rabbit`, fake in acceptance tests; `ui` untouched.
- Errors are typed results at the boundary: 401, 403, 404, timeout, bad JSON all map to `Unreadable` with a reason
  the UI can show later; do not catch broadly, map known failures.
- Unidirectional flow: view model state gets `permissions: PermissionCheckResult?` per connection; no hidden
  global state. Each profile is its own environment, so keep results keyed by profile id and drop them on edit.
- Management client note for M2: this is the first management API call that needs JSON parsing; keep a small
  `ManagementApiClient` wrapper in `data-rabbit` so M2 reuses it.
- Fake broker for `@fake-broker`; real broker only in `@broker` tests and the spike.

## UX
None in this task (see `M1-07`). Source for later: `docs/ux/Main.dc.html` (sidebar pill, edit panel
"Permission check" banner), `docs/ux/Topology.dc.html` (locked state).

## Open points
- Spike result decides the fallback. Options if a non-admin user cannot read their own permissions:
  (A) fall back to safest mode and tell the user (current spec and scenario; recommended, no extra privilege);
  (B) ask the user to supply a separate admin credential just for the check (more secrets, not recommended);
  (C) probe by attempting an operation (rejected: it would change broker state). Not a blocker for step 1; if the
  spike shows (A) is the only path for the typical read-only user, record that read-only users will always see
  locked topology and that is acceptable (they have no configure anyway) but publishing would also be disabled
  even if they have write: raise as `@needs-decision` then.
- Field names of the permissions response are unverified until the spike; do not hard-code beyond what it shows.
- No new dependency or build tool: Ktor client and kotlinx-serialization already in `data-rabbit`.

## Definition of done
Tests written first and failing for the right reason; all scenarios above pass; spike result written into this
brief and `docs/spec.md` (open question moved); no unrelated changes; `docs/tasks/README.md` updated.

## Status
- Round: 1, step 2 done (tests written first, red: missing types). Next: step 3, senior developer.
- Branch: `m1-03-permission-check`
- Base branch: `main` (needs only M1-02, already in main; does not need M1-04 or M1-05)

### Spike result (step 1, real RabbitMQ 4 broker, Testcontainers, HTTPS 15671, throwaway TLS fixture)
Recorded by `data-rabbit/src/test/.../PermissionsEndpointSpikeTest.kt` (`@Tag("broker")`, asserts the findings).
Users had permissions on `/`: untagged `^orders\..*`/""/`.*`; `monitoring` and `management` as listed.

| Endpoint | admin | `monitoring` | `management` | no tag | wrong password |
| --- | --- | --- | --- | --- | --- |
| `GET /api/permissions/%2F/{user}` | 200 | 401 "Not administrator user" | 401 "Not administrator user" | 401 "Not management user" | 401 `not_authorized` |
| `GET /api/users/{user}/permissions` | 200 | 401 | 401 | 401 | - |
| `GET /api/whoami` | 200 | 200 (`name`, `tags`, `is_internal_user`; no permissions) | 200 (same) | 401 "Not management user" | - |

- Permissions body (admin): `GET /api/permissions/{vhost}/{user}` returns an object
  `{"user","vhost","configure","write","read"}`; `GET /api/users/{user}/permissions` returns an array of the same.
  An empty pattern is the empty string.
- Conclusion: only an administrator can read permissions through the management API, whatever the other tags.
  A normal stream user (no tag) cannot even call `/api/whoami`; it gets 401 on every management call.
  So for every non-administrator the answer to the open spike question is NO, and option A (safest mode,
  tell the user) is the only path that exists without extra privilege. In practice the check will return
  `Unreadable` for almost every real user of this app (stream users are rarely administrators), so
  topology is always locked and publishing disabled for them, even when they do have write.
- Not tested: the 404 case for an administrator with no permissions on the vhost is covered by a `@broker`
  test (asserts "not unlocked", not the exact status); the management endpoint was not tried with a user
  that has the `policymaker` tag or other tags.
- DECISION NEEDED from the architect (not decided by test developer): the consequence above (publishing
  disabled for a write-capable non-admin user) is the `@needs-decision` case named in Open points. Options A/B/C
  as above; A is what the tests assume.

### Step 2: tests written (all red: they do not compile, the production types are missing)
- `core/src/jvmTest/.../PermissionDecisionTest.kt`, `ConnectionSessionTest.kt`
- `acceptance-tests/src/test/.../permissions/` (`RunPermissionCheckAcceptanceTest` filter
  `not @needs-M2 and not @needs-M1-07 and not @broker`, `PermissionCheckSteps`, `PermissionFakes`)
- `data-rabbit/src/test/.../RealBrokerPermissionCheckTest.kt` (`@broker`), spike test as above.
- Contract the tests assume (proposed, senior developer may adjust names but must tell the test developer):
  in `dev.caerbannog.core.connections`: `BrokerPermissions(configure, write, read)`;
  `sealed interface PermissionCheckResult { val topologyUnlocked; val publishingEnabled; val canBrowseStreams }`
  with `Checked(topologyUnlocked, publishingEnabled)` and `Unreadable(reason)` (both flags false, browse true);
  `fun decidePermissions(BrokerPermissions): PermissionCheckResult.Checked`;
  `interface PermissionChecker { suspend fun check(profile, password: String): PermissionCheckResult }`;
  `ConnectionSession(checker, secretStore)` with `suspend open(profile)`, `suspend reopen(profile)`,
  `isUsable(name)`, `permissions(name)`; a missing stored password gives `Unreadable` without calling the broker;
  `ConnectionsViewModel(..., session: ConnectionSession? = null)` plus
  `suspend fun saveProfileChanges(originalName, input): SaveConnectionResult` (saves, then `session.reopen`);
  `data-rabbit`: `ManagementApiPermissionChecker(connectTimeoutMillis = 5_000)`.
