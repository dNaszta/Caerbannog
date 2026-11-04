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
  sealed type (`Checked(topologyUnlocked, publishingEnabled)`, `Unreadable(cause)`, plus `DeclaredByUser`), `PermissionChecker` interface
  (suspend, takes profile + secret), and a small use case / session gate `ConnectionSession.open(profile)` that runs the
  check first and only then reports the connection as usable; `ConnectionsViewModel` (or the save path) re-runs the
  check when an existing profile is saved. Pure decision function: patterns to locked/unlocked, enabled/disabled.
- `core`, declared permissions (decision D, `docs/spec.md` "Broker-read permission"): `DeclaredPermissions(configure,
  write)` and an optional `declaredPermissions` field on `ConnectionProfile` (default null), plus the rule in
  `ConnectionSession`: the real check runs first; only when it returns `Unreadable` (except cause `NO_PASSWORD`) and
  the profile has a declaration is the result `DeclaredByUser`. No persistence and no UI for the field here.
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
- Sidebar "Topology locked" pill, declared-permission wording and topology screen text (new task `M1-07`, scenarios tagged
  `@needs-M1-07`).
- Persisting `declaredPermissions` in the profile JSON, the `setDeclaredPermissions` setter and the edit panel fields
  (new task `M1-06`, scenarios tagged `@needs-M1-06`). Until M1-06 nothing in the app can set the field, so in the
  running app the safest mode still applies; tests set it with `profile.copy(declaredPermissions = ...)`.
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
  - "Permissions cannot be read for this user" (spike resolved, tag `@needs-spike` removed; safest mode, message starts
    "Permissions could not be read"). The step "streams can still be listed and browsed" asserts the
    capability flag only (no stream list exists yet); say so in the step name or comment.
  - "Declared permissions are used when permissions cannot be read" (all four examples)
  - "What the broker says wins over declared permissions"
  - "Declared permissions do not help when the password is missing"
- Deferred by tag, do not implement steps: "A locked connection says so in the sidebar..." and "Declared permissions are
  shown as the user's own claim" (`@needs-M1-07`), "Declared permissions are saved with the profile" and "A profile saved
  before declarations existed has none" (`@needs-M1-06`),
  "Browsing streams does not need the configure permission" (`@needs-M2`). The Cucumber tag filter excludes
  `@needs-M1-07` and `@needs-M1-06` too (test developer updates `RunPermissionCheckAcceptanceTest`).
- Unit (core): decision function for the four patterns plus a pattern that is not `.*` (for example `^orders\\..*`
  counts as configure present); non-empty means allowed, since the broker has the final say; `^$` also counts as
  present (documented consequence). Session unit tests: override applies only on `Unreadable`, never over `Checked`,
  never for `NO_PASSWORD`, and never when the profile has no declaration.
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
  global state. Each profile is its own environment, so keep results keyed by profile name (the existing identity: the secret store is keyed by name too) and drop them on edit or rename.
- Management client note for M2: this is the first management API call that needs JSON parsing; keep a small
  `ManagementApiClient` wrapper in `data-rabbit` so M2 reuses it.
- Fake broker for `@fake-broker`; real broker only in `@broker` tests and the spike.

## UX
None in this task (see `M1-07`). Source for later: `docs/ux/Main.dc.html` (sidebar pill, edit panel
"Permission check" banner), `docs/ux/Topology.dc.html` (locked state).

## Open points
- Decided (D, product owner): safest mode stays the default when permissions are unreadable, and the profile may carry a
  user declaration that applies only then (rules in `docs/spec.md` "Broker-read permission"). Options B (separate admin
  credential) and C (probing) are rejected.
- Field names of the permissions response are verified by the spike: `user`, `vhost`, `configure`, `write`, `read`.
- No new dependency or build tool: Ktor client and kotlinx-serialization already in `data-rabbit`.

## Definition of done
Tests written first and failing for the right reason; all scenarios above pass; spike result written into this
brief and `docs/spec.md` (open question moved); no unrelated changes; `docs/tasks/README.md` updated.

## Status
- Round: 1, architect decisions done (spike resolved, decision D, contract below). Next: step 2b, test developer adjusts
  the red tests to the final contract (list below); then step 3, senior developer.
- Branch: `m1-03-permission-check`
- Base branch: `main` (needs only M1-02, already in main; does not need M1-04 or M1-05)
- New siblings: `M1-06-profile-declared-permissions`, `M1-07-permission-state-ui` (not started; both start after this task).

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
- DECIDED (architect, product owner chose D): safest mode stays the default, plus a per-profile user declaration that
  applies only when permissions are unreadable (spec "Broker-read permission"). Not a `@needs-decision` any more.

### Step 2: tests written (all red: they do not compile, the production types are missing)
- `core/src/jvmTest/.../PermissionDecisionTest.kt`, `ConnectionSessionTest.kt`
- `acceptance-tests/src/test/.../permissions/` (`RunPermissionCheckAcceptanceTest` filter
  `not @needs-M2 and not @needs-M1-07 and not @broker`, `PermissionCheckSteps`, `PermissionFakes`)
- `data-rabbit/src/test/.../RealBrokerPermissionCheckTest.kt` (`@broker`), spike test as above.
- First proposal (superseded by the final contract below).

### Final contract (architect; the senior developer implements it, the tests match it)
All in `dev.caerbannog.core.connections` (core, common code, no JVM-only types):

```kotlin
data class BrokerPermissions(val configure: String, val write: String, val read: String)  // "" = none
data class DeclaredPermissions(val configure: Boolean, val write: Boolean)
// ConnectionProfile gains, last parameter, default keeps old callers compiling:
//   val declaredPermissions: DeclaredPermissions? = null

enum class UnreadableCause { NOT_AUTHORIZED, NOT_FOUND, TIMEOUT, UNREACHABLE, BAD_RESPONSE, NO_PASSWORD }

sealed interface PermissionCheckResult {
    val topologyUnlocked: Boolean
    val publishingEnabled: Boolean
    val canBrowseStreams: Boolean            // true in every case: reading never depends on the check
    data class Checked(override val topologyUnlocked: Boolean, override val publishingEnabled: Boolean) : PermissionCheckResult
    data class DeclaredByUser(override val topologyUnlocked: Boolean, override val publishingEnabled: Boolean,
                              val unreadable: Unreadable) : PermissionCheckResult
    data class Unreadable(val cause: UnreadableCause, val detail: String? = null) : PermissionCheckResult {
        val userMessage: String               // core-owned, starts with "Permissions could not be read"
    }                                         // topologyUnlocked = false, publishingEnabled = false
}

fun decidePermissions(permissions: BrokerPermissions): PermissionCheckResult.Checked
// configure.isNotEmpty() -> topologyUnlocked; write.isNotEmpty() -> publishingEnabled; read ignored.
// Exactly-empty string means none; "^$" counts as present (broker has the final say).
fun decideDeclared(declared: DeclaredPermissions, unreadable: PermissionCheckResult.Unreadable): PermissionCheckResult.DeclaredByUser

interface PermissionChecker { suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult }
// Returns Checked or Unreadable only. Never DeclaredByUser: the override is the session's job, not the adapter's.

class ConnectionSession(checker: PermissionChecker, secretStore: SecretStore) {
    suspend fun open(profile: ConnectionProfile): PermissionCheckResult
    suspend fun reopen(profile: ConnectionProfile): PermissionCheckResult
    fun isUsable(name: String): Boolean
    fun permissions(name: String): PermissionCheckResult?
}
```

Session rules:
- Results are keyed by profile name. `isUsable(name)` is false until a check has finished; `open` and `reopen` clear
  the stored result for that name before calling the checker, so the connection is never usable while the check runs.
  Usable means a result exists (including `Unreadable`: safest mode is a usable connection that browses only).
- Password missing in the secret store: result `Unreadable(NO_PASSWORD)`, checker not called, no declaration applied.
- Otherwise call the checker once. `Checked` is stored as is. `Unreadable` with a declaration on the profile becomes
  `decideDeclared(...)` (the `Unreadable` stays inside for the message). `Unreadable` without a declaration is stored
  as is. A `Checked` result is never replaced by the declaration, whatever it claims.
- `reopen(profile)` is the same as `open` (kept as a separate name for intent); it exists so a save can be asserted
  to trigger a second check.

`ConnectionsViewModel(repository, secretStore, reachabilityChecker, caValidator, session: ConnectionSession? = null)`:
`suspend fun saveProfileChanges(originalName: String, input: ConnectionProfileInput): SaveConnectionResult`. It saves with
the same rules as `updateConnection` (including rename and the password-unchanged rule), and only when the result is
`Saved` calls `session.reopen(savedProfile)` (for a rename also forgets the result under `originalName`). When the save
fails, no check runs and the earlier result stays. A null session means no check (existing M1-02 tests unchanged).
The existing `updateConnection` stays as it is; the UI will move to `saveProfileChanges` in M1-07.

`data-rabbit`: `ManagementApiPermissionChecker(connectTimeoutMillis: Long = 5_000)` implements `PermissionChecker`.
HTTPS, `GET /api/permissions/{urlencoded vhost}/{user}`, basic auth, TLS/CA code shared with the reachability checker.
Mapping: 200 with the object shape to `Checked(decidePermissions(...))`; 401 or 403 to `NOT_AUTHORIZED` (a 401 is also what
a wrong password gives and the two cannot be told apart, so `userMessage` words it that way); 404 to `NOT_FOUND`
(administrator with no permissions on the vhost: never unlocked); timeout to `TIMEOUT`; connection refused or TLS failure
to `UNREACHABLE`; malformed JSON or missing fields to `BAD_RESPONSE`. `detail` holds only safe text (status code,
exception class), never the password, URL credentials or response bodies that could echo them.

Answers to the test developer's questions: (a) confirmed as above; (b) yes, core owns the wording through
`Unreadable.userMessage`, the fake supplies only a cause; the `reason: String` field is dropped; (c) keyed by name, not
id; (d) confirmed, `^$` counts as present.

### Tests that must change (test developer, step 2b)
- `PermissionFakes.kt`, `PermissionCheckSteps.kt`: refusal becomes `Unreadable(UnreadableCause.NOT_AUTHORIZED)` (no
  free text); the "tells me" step asserts `userMessage.startsWith("Permissions could not be read")`; add steps for the
  new scenarios (`the profile {string} declares configure {string} and write {string}` sets
  `profile.copy(declaredPermissions = ...)` and updates the repository copy used by the session; `no password is stored for {string}`;
  `the app says these permissions are declared by me, not confirmed by the broker` asserts `DeclaredByUser`;
  `the app says these permissions were read from the broker` asserts `Checked`).
- `RunPermissionCheckAcceptanceTest`: filter `not @needs-M2 and not @needs-M1-07 and not @needs-M1-06 and not @broker`.
- `PermissionDecisionTest`: add `^$` present, add `decideDeclared` cases (four combinations, carries the `Unreadable`).
- `ConnectionSessionTest`: `Unreadable(reason)` constructions become `Unreadable(cause)`; add override rules (applies on
  `Unreadable`, not over `Checked`, not for `NO_PASSWORD`, not without a declaration, results keyed by name, not usable
  while the check runs, rename drops the old key).
- `RealBrokerPermissionCheckTest`: constructor and cause mapping as above (401 `NOT_AUTHORIZED`, refused `UNREACHABLE`).
- Spike test and its recorded findings stay as they are.


### Step 2b (test developer, after decision D)
Tests changed to the Final contract: `PermissionFakes`/`PermissionCheckSteps` (cause `NOT_AUTHORIZED`, userMessage prefix,
declared / no-password / DeclaredByUser / Checked steps), runner filter excludes `@needs-M1-06`, `PermissionDecisionTest`
(`^$`, `decideDeclared`, userMessage prefix for every cause), `ConnectionSessionTest` (override rules, usable-while-checking),
new `ConnectionsViewModelPermissionTest` (saveProfileChanges rules), `RealBrokerPermissionCheckTest` (cause mapping,
adapter never returns `DeclaredByUser`). Spike test unchanged. Red: missing `declaredPermissions`, `DeclaredPermissions`,
`UnreadableCause`, `decideDeclared`, `ConnectionSession`, `saveProfileChanges`, `ManagementApiPermissionChecker`.
