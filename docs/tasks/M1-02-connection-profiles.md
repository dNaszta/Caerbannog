# Connection profiles

Milestone: M1    Depends on: M1-01-skeleton-and-spike    Size: L (see Open points about splitting if the real-TLS proof proves hard)

## Goal

A user can add, edit, save, delete and select RabbitMQ connection profiles from a real Connections screen
matching `docs/ux/Main.dc.html`; passwords live in the OS keychain, never in the profile file; a "Check
connection" action proves both the stream endpoint (5551) and the management API (15671) are reachable over
TLS, naming whichever one fails. A real (non-fake) check against a real, TLS-enabled broker is demonstrated,
so this task is the one that actually closes M1's own gate in `docs/spec.md`'s Milestones table: "Connects
to a local broker."

## Why

`docs/spec.md`, Milestones table, M1 gate: "Connects to a local broker" — not yet met by M1-01 or
M1-TASKFILE-01 (both tooling-only; see their own Status sections, which say so explicitly and name this task
as the next candidate). M1-01's brief tentatively named this task `M1-02` and scoped it to
"real connection-profile CRUD ... `tests/bdd/features/01_connections.feature`", deliberately leaving
`data-rabbit` as an empty shell and the `ConnectionProfile` domain model unbuilt ("do not reuse or invent the
real `ConnectionProfile` domain model for this test — that model belongs to M1-02"). Spec sections this task
builds: Screens and UX (Connections row), Architecture (TLS only, 5551/15671), Module layout and tech-stack
table (`core` domain + repository interfaces, `data-rabbit` stream/management clients and connection-profile
storage, Settings and secrets row: JSON file + `java-keyring`).

## Scope

**In:**

- `ConnectionProfile` domain model in `core` (commonMain): name, host, virtual host, username, stream port
  (default 5551), management port (default 15671), optional CA certificate file reference. No TLS toggle
  field exists at all — TLS is not a setting, it is the only mode the model can express (per
  `01_connections.feature`'s "TLS is always on" scenario: there must be no option to turn it off, not merely
  an option defaulted to on).
- `topologyControlLevel: TopologyControlLevel` on `ConnectionProfile`, an enum with values `READ_ONLY`
  (default for every new profile), `DECLARE_AND_EDIT` and `FULL_CONTROL` — the self-constraint level per
  `docs/spec.md`'s "Topology migrations and permission guardrails" / Self-constraint level. This task only
  stores and edits the field; it adds no enforcement logic anywhere (no migrations, no topology screens, no
  effective-permission combination with the broker-read half — that starts at M1-03 and matures through M5's
  topology screens).
- `ConnectionProfileRepository` interface in `core` (list, save with name-uniqueness check, delete) and a
  JSON-file-backed implementation in `data-rabbit` (jvmMain). File location: `~/.caerbannog/connections.json`
  (engineering call, see Open points).
- `SecretStore` interface in `core` (store/retrieve/delete a password keyed by profile name) and a
  `java-keyring`-backed implementation in `data-rabbit`, replacing M1-01's throwaway spike key with real
  `ConnectionProfile` wiring. The JSON file never contains the password field at all (not even encrypted).
- `BrokerReachabilityChecker` interface in `core` (one method, profile in, a sealed `ConnectionCheckResult`
  out: both reachable, one endpoint unreachable — naming which — TLS refused, or management plugin missing)
  and a real implementation in `data-rabbit`: a TLS connection attempt on the stream port (using
  `com.rabbitmq:stream-client`'s connection setup, or a raw `SSLSocket` handshake if that is simpler — the
  developer's call) and an HTTPS call to the management API (Ktor client) checking it answers at all (any
  HTTP response, including 401, counts as reachable — this check proves reachability, not that the
  credentials are valid or what the user may do; that is permission-check logic, out of scope here, see
  below).
- A real, non-fake proof that the above actually connects to a real broker: a `@Tag("broker")` JUnit
  integration test in `data-rabbit`'s test source set (same pattern as M1-01's Testcontainers container-start
  proof, not a new Cucumber scenario — see Design notes) that starts a RabbitMQ 4.x Testcontainers instance
  with TLS enabled on both the stream and management listeners, and shows the real
  `BrokerReachabilityChecker` reports both reachable. This is the concrete artifact that closes M1's gate.
- A `ConnectionsViewModel` in `core` (state in, events out — add, edit, select, delete, check) backing the
  screen.
- The Compose Connections screen in `ui` (commonMain), matching `docs/ux/Main.dc.html`: sidebar list of saved
  profiles with a status label (Connected / Editing / Not connected), an edit panel with the fields above,
  New connection / Save / Delete (with a confirm step, not name-typed confirmation — see UX section) and
  "Check connection" wired to the reachability checker above.
- CA certificate handling: optional file picker, rejected up front if the file is not a valid certificate
  (`01_connections.feature`, "An unreadable CA certificate file is rejected"); stored as a file-path reference
  in the profile JSON (it is public key material, not a secret — it does not go through `SecretStore`).

**Out (and where each lands instead):**

- **The permission check itself** (reading configure/write/read permissions from the management API,
  locking topology/publish, the sidebar "Topology locked" / "Can change topology" pill, and the "Permission
  check" banner in `Main.dc.html`'s edit panel). This is `02_permission_check.feature` in full, tagged `@M1`
  in its own file but scoped by the spec to a different section ("Topology migrations and permission
  guardrails") than Connections' CRUD. M1-01's brief already tentatively called this `M1-03`; I'm confirming
  that split rather than folding it in here. Reason: it needs a real, authenticated management API client
  call the non-admin-permission-read spike question (`docs/spec.md` Open questions, `02_permission_check.feature`
  `@needs-spike`) has not yet resolved, and none of `01_connections.feature`'s own scenarios need permission
  parsing — they only need endpoint reachability. Folding both into one task would make it too large for a
  1–3 day vertical slice and would mean guessing at unresolved spike behavior. See UX section for how the
  screen handles the now-empty permission panel in the meantime.
- Stream list, stats, publishers/consumers (M2) — the "Switch between connections" scenario in
  `01_connections.feature` asserts "the stream list shows the streams of 'prod-eu'"; that half cannot be
  built or tested until M2's stream list exists. See Acceptance for how this one scenario is handled.
- Any authenticated management API call beyond a bare reachability probe; no stream list, stats, or topology
  client code.
- Publishing, topology, migrations.
- CI wiring (none exists; out of scope project-wide for now per `M1-TASKFILE-01`'s Why section).

## Acceptance

- BDD: `tests/bdd/features/01_connections.feature` — all scenarios except the one named below:
  - `Save a new connection profile` (`@smoke`)
  - `A new connection defaults to read-only topology control`
  - `Raise a connection's topology control level`
  - `Connection names must be unique`
  - `Check connection succeeds when both endpoints are reachable` (`@smoke @fake-broker`)
  - `Check connection names the endpoint that failed` (Scenario Outline, `@negative @fake-broker`)
  - `The management plugin is not enabled` (`@negative @fake-broker`)
  - `TLS is always on` (`@negative @security`)
  - `The app refuses a broker that does not offer TLS` (`@negative @security @fake-broker`)
  - `Use a custom CA certificate` (`@fake-broker`)
  - `An unreadable CA certificate file is rejected` (`@negative`)
  - `Passwords are not stored in the profile file` (`@security` — tag corrected as part of this brief;
    see Design notes)
  - `Delete a connection profile`
  - `Deleting a profile can be cancelled`
- **Deliberately deferred, not silently skipped:** `Switch between connections` needs M2's stream list to be
  meaningfully green. Mark its step definitions `@Disabled`/pending with a comment pointing at this brief and
  at M2's stream-list task, rather than implementing a hollow assertion against a fake list just to turn it
  green.
- The profile editor renders and persists the `topologyControlLevel`, defaulting to read-only — covered by
  the two new scenarios listed above.
- **Extra check, no Gherkin scenario (see Design notes for why):** a `@Tag("broker")` JUnit test in
  `data-rabbit` proves a real `BrokerReachabilityChecker` reports both endpoints reachable against a real,
  TLS-enabled Testcontainers RabbitMQ 4.x broker. This is the proof that satisfies M1's "Connects to a local
  broker" gate.
- Not targeted by this task: anything in `02_permission_check.feature` (see Scope/Out).

## Design notes

- Module boundaries (unchanged from M1-01, now populated): `core` holds `ConnectionProfile`,
  `ConnectionProfileRepository`, `SecretStore`, `BrokerReachabilityChecker`, `ConnectionCheckResult` (sealed),
  and `ConnectionsViewModel` — zero RabbitMQ or network imports, so its own tests run against fakes with no
  broker and no filesystem/keychain. `data-rabbit` is the only module holding the JSON file I/O, the
  `java-keyring` calls, and anything touching `com.rabbitmq:stream-client` or an HTTP client. `ui` only calls
  the `core` interfaces/view model; no broker or network imports there either.
- Fakes for `@fake-broker` scenarios: following M1-01's own design note ("propose a dedicated test module ...
  that can depend on both `core` ... and `data-rabbit` ... later"), put the fake `BrokerReachabilityChecker`
  and an in-memory `ConnectionProfileRepository`/`SecretStore` in `acceptance-tests`, used by the Cucumber
  step definitions for `01_connections.feature`.
- `ConnectionCheckResult` as a sealed type (not booleans/strings) gives the four outcomes the scenarios need
  distinct shapes: `BothReachable`, `EndpointUnreachable(endpoint: Endpoint, reason: String)`,
  `ManagementPluginMissing(pluginName: String)`, `TlsRefused`. This is a concrete fit for a sealed result type
  per the review checklist (errors/states as typed results, not a boolean flag that switches behavior).
- Why the real-broker proof is a plain JUnit `@Tag("broker")` test and not a new Cucumber `@broker` scenario:
  it mirrors M1-01's own container-start proof (also a plain tagged JUnit test in `data-rabbit`, not a
  feature file), and setting up TLS certs for Testcontainers RabbitMQ (custom `rabbitmq.conf` with
  `ssl_options`/`listeners.ssl.default`/`management.ssl.port`, plus a throwaway CA and server cert as test
  fixtures) is itself the highest-unknown part of this task. Keep it as an isolated, narrowly-scoped
  integration test (reachability only, no Gherkin step-def plumbing) so a TLS-container surprise (in the
  spirit of M1-01's Docker API version finding) does not also block the `@fake-broker` scenarios above, which
  do not need Docker at all. If this proves materially harder than expected, stop and flag it back to the
  architect rather than quietly descoping the gate — see Open points.
- `01_connections.feature`'s `@needs-spike` tag on "Passwords are not stored in the profile file" has been
  removed as part of this brief (already done, see the diff to that file): M1-01 already demonstrated the
  `java-keyring` round trip and `docs/spec.md`'s Settings-and-secrets row already records the library choice,
  so the scenario no longer depends on an unresolved spike. Windows keychain support is still unverified (a
  separate, already-tracked Risks-section item), not a reason to keep the tag.
- No real HTTP/stream-client call should ever attempt to read or interpret permissions in this task — keep
  the reachability check to "did the TLS handshake and the HTTP call succeed at all," nothing more, so there
  is no temptation to half-build M1-03's logic here.

### Decisions pinned at handover from step 2 (architect, 2026-11-03)

The test developer flagged five design questions while writing the fakes/step-defs/red tests (round 1,
commit `242eb6b`). All five are resolved below; build against this shape, not against a verbal summary.

1. **`ConnectionProfileRepository` write API — the `add()`/`update()` split is correct, keep it.**
   Everything in this task is keyed by `name` (no separate `id` concept anywhere in the brief or in
   `01_connections.feature`), so a single `save()` comparing by reference/identity would force the
   `ConnectionsViewModel` to track "the exact object instance I started editing" across a session of
   immutable-data-class copies — fragile, and not what any scenario needs. Final shape:
   ```kotlin
   interface ConnectionProfileRepository {
       fun list(): List<ConnectionProfile>
       fun add(profile: ConnectionProfile): SaveConnectionResult     // fails if the name exists
       fun update(profile: ConnectionProfile): SaveConnectionResult  // profile.name must already exist
       fun delete(name: String)
   }
   sealed interface SaveConnectionResult {
       data class Saved(val profile: ConnectionProfile) : SaveConnectionResult
       data class NameAlreadyExists(val name: String) : SaveConnectionResult
   }
   ```
   `update()` on a name that does not exist is a programmer error (the fake throws via `check()`; the real
   `data-rabbit` implementation may do the same) — it is never reachable from the UI, since the UI only
   calls `update()` for a profile it already loaded from `list()`.
   **New edge case this split surfaces, not covered by any Gherkin scenario but real once the "Editing"
   panel's Save button is wired (see UX section) — renaming an existing profile.** Resolve it in
   `ConnectionsViewModel`, not in the repository: keep track of the name the edit session started from
   (`originalName`); on Save, if the (possibly edited) name is unchanged, call `repository.update(...)`; if
   it changed, call `repository.add(newProfile)` first and only on `Saved` follow with
   `repository.delete(originalName)` and move the secret (`secretStore.store(newName, password)` then
   `secretStore.delete(originalName)`) — on `NameAlreadyExists`, return that result unchanged and leave the
   original profile under `originalName` untouched. This keeps a failed rename non-destructive. No new
   Gherkin scenario is required for this task (open point, not blocking — see Open points), but the senior
   developer must not skip it: it is reachable the first time anyone edits a saved profile's name field.

2. **`CaCertificateValidator` — confirmed, added to the component list (it was missing from the original
   Design notes, that was this brief's gap, not the test developer's).** This is the correct way to let
   `core` express "a certificate file can be validated" without `core`'s commonMain ever importing
   `java.security.cert` (a JVM-only type `core` must stay free of per Module boundaries, since `core` is a
   Kotlin Multiplatform module declared for more than the `jvm()` target even though only `jvm()` exists
   today). Final component list addition:
   - `core`: `CaCertificateValidator` (`fun validate(filePath: String): CaCertificateValidationResult`) and
     `CaCertificateValidationResult` sealed type (`Valid`, `Invalid(reason: String)`) — both pure data/
     contract, no cert-library import.
   - `data-rabbit`: `X509CaCertificateValidator`, the real implementation using
     `java.security.cert.CertificateFactory`.
   - `acceptance-tests`: `FakeCaCertificateValidator` performs the same real X.509 parsing independently
     (acceptable: `acceptance-tests` is a plain `kotlin("jvm")` module per its own `build.gradle.kts`, not
     KMP, so it may import `java.security.cert` without creating a `core`/`ui` boundary problem; it exists
     so `01_connections.feature`'s step definitions don't need a dependency on `data-rabbit`'s concrete
     class — see Fakes vs. real JSON file below for the same reasoning applied elsewhere).

3. **Fakes vs. the real JSON file scenario — confirmed, split stands as implemented, no change.**
   `01_connections.feature`'s Cucumber layer (in-memory fakes, per Design notes above) proves the
   structural guarantee — `ConnectionProfile` has no password field, `SecretStore.store()` was actually
   called — and `data-rabbit`'s `JsonConnectionProfileRepositoryTest` (`the password never appears in the
   JSON file on disk`) proves the real file written by `JsonFileConnectionProfileRepository` never contains
   it, by reading the real bytes back. Both together satisfy "Passwords are not stored in the profile file"
   (Acceptance); do not change the Cucumber step to spin up a real temp-file repository — that would
   contradict this same Design notes section's instruction to keep `01_connections.feature`'s fakes
   filesystem-free, and would duplicate, not replace, what `JsonConnectionProfileRepositoryTest` already
   proves honestly.

4. **`ConnectionsViewModel` method surface — confirmed, with one required addition and two notes.**
   The five pinned methods (`addConnection`, `setTopologyControlLevel`, `setCaCertificate`,
   `validateCaCertificateFile`, `checkConnection` (suspend), `requestDelete`/`confirmDelete`/`cancelDelete`)
   in `dev.caerbannog.core.connections` are sound — build against them as-is. One gap: no pinned method
   lets the UI persist edits to a saved profile's host/virtual-host/username/password (only the narrower
   `setTopologyControlLevel`/`setCaCertificate` setters are pinned), yet the UX section's "Editing" state
   needs exactly that for its Save button. Add:
   ```kotlin
   fun updateConnection(originalName: String, input: ConnectionProfileInput): SaveConnectionResult
   ```
   implementing the rename handling from decision 1 above. Two more notes, not changes:
   - Model pending-delete as a nullable name (e.g. `pendingDeleteName: String?` on `ConnectionsState`), not
     a `Boolean` plus a separately-tracked name — `requestDelete(name)` already carries the name, so there
     is no reason to introduce a boolean flag that switches behavior (review checklist).
   - The pinned methods are necessarily incomplete for full UI wiring: pure UI-selection state (which
     profile is selected, "New connection" vs "Editing" panel mode, cancel-edit) is not exercised by any
     Gherkin scenario (Cucumber drives domain behavior, not Compose selection state) and is not pinned by
     the test developer's red tests. The senior developer adds it following the same shape (state in
     `ConnectionsState`, events as `ConnectionsViewModel` methods) — this is normal build-time detail, not
     an open architecture question requiring another round here.

5. **Extra tests (`KeyringSecretStoreTest`, `CaCertificateValidatorTest`) — confirmed in scope, not scope
   creep.** The brief's own Scope lines name a real `java-keyring`-backed `SecretStore` and real CA
   certificate validation as production code this task builds; the Definition of done's "No profile
   password ever appears in the JSON file or in logs" and the CA-certificate scope bullet are not honestly
   provable by the `@fake-broker`/fake-repository Cucumber layer alone (a fake cannot prove anything about
   a real OS keychain or real X.509 parsing — "a fake that is too forgiving hides bugs", the test
   developer's own house rule, applies here). These two tests are ordinary TDD coverage for in-scope
   production classes, distinct from the one brief-named `@Tag("broker")` test (which proves
   `BrokerReachabilityChecker` end-to-end against Testcontainers, not keyring or cert parsing). Keep both;
   do not cut.

### Caveat carried forward to the senior developer (not a question, do not re-litigate)

`RealBrokerReachabilityTest`'s TLS config (`data-rabbit/src/test/resources/tls/rabbitmq.conf`, plus the
`RabbitMQContainer` wiring in that test) is the test developer's own words "a solid first attempt, not a
proven one" — it has not been run against a live Docker daemon (no production
`StreamAndManagementReachabilityChecker` exists yet to make it pass), and the `testcontainers-rabbitmq`
API calls were verified by `javap`/bytecode inspection against the cached jar, not by a sources jar or a
real run. Treat this the same way M1-01 treated its own Docker-API-version finding: expect a possible
RabbitMQ-4.x config-key surprise (e.g. a renamed `stream.listeners.ssl.*`/`management.ssl.*` key) the
first time this test actually runs against a container, budget time for it, and if it turns out to be a
genuinely hard TLS/Testcontainers problem, stop and flag it back here rather than quietly reshaping the
gate — this is explicitly called out, not a silent risk.

## UX

Screen: `docs/ux/Main.dc.html` ("1 Connections" per `docs/ux/canvas.json`). Build these states:

- **Empty:** no saved profiles — sidebar shows only "New connection".
- **Loaded, nothing selected / New connection:** empty edit panel, defaults pre-filled (stream port 5551,
  management port 15671).
- **Loaded, profile selected ("Editing"):** fields populated, Save/Delete enabled.
- **Check connection — success:** the green "Connection test passed" banner from the mockup, both endpoints
  named as reachable.
- **Check connection — error:** a banner naming which endpoint failed, or that TLS was refused, or which
  plugin is missing — no generic "something went wrong."
- **Delete confirm / cancel:** a simple confirm step (Yes/Cancel). Note: the spec's UX rule "Destructive
  actions (delete stream) need the stream name typed to confirm" names streams specifically, and neither the
  mockup nor `01_connections.feature`'s delete scenarios ask for typed confirmation on connection profiles —
  do not invent that requirement here.
- **Topology control level (new, not in the `Main.dc.html` mockup — the mockup predates the self-constraint
  design):** a real, working 3-option control (for example segmented buttons or a radio group) labeled
  "Topology control", options Read-only / Declare and edit / Full control, defaulting to Read-only for a new
  profile, pre-selected to the stored value when editing an existing one, and persisted on Save. Place it in
  the same panel area as the mockup's permission box, above or below it, with the same visual weight (a
  `t-ts` heading plus the control), since it is the self-constraint half of the same concept.
- **Permission check panel (the box under the fields in the mockup, "svc-explorer can change topology...")**:
  render it, since the mockup's layout is otherwise what we're matching, but in a neutral, inert state — for
  example "Permission check has not run yet" with no pill/lock icon — since this is the broker-read half, and
  the real check is M1-03's job. Do not fabricate a locked/unlocked verdict here. This is now a known,
  accepted interim state (the panel sits next to a real, working control, it is not the whole panel that is
  inert) — not an open point.

## Open points

**Engineering calls made here (no product input needed):**

- JSON file path: `~/.caerbannog/connections.json`.
- CA certificate kept as a file-path reference in the profile JSON, not copied or secreted — if the file
  later moves or is deleted, the next check simply reports it unreadable (same user-facing shape as the
  "unreadable CA certificate" scenario already covers).
- Real TLS reachability proof via a plain `@Tag("broker")` JUnit test, not a new Gherkin scenario (see Design
  notes).
- `@needs-spike` tag dropped from the password-storage scenario (already applied to
  `tests/bdd/features/01_connections.feature`).
- "Switch between connections" scenario's step defs deliberately left pending until M2 (see Acceptance).
- The self-constraint level's product design (overall approach, the 3-level staircase, topology-only scope)
  was confirmed as-is by the product owner; `docs/spec.md`'s "Topology migrations and permission guardrails"
  section reflects the confirmed design. Nothing about it is still open for this task.
- Renaming an existing profile's name during an edit has no Gherkin scenario (see Design notes, decision 1)
  — behavior is pinned there (non-destructive add-then-delete) so the senior developer has a concrete rule
  to build against; filing a scenario for it is optional follow-up, not a blocker for this task.

**Unrelated to this task, carried forward unresolved (do not attempt to resolve here):**

- `docs/spec.md` Open questions: can a non-administrator read their own permissions via the management API
  (blocks M1-03, not this task).
- Windows keychain round trip still unverified (`docs/spec.md` Risks section) — not a blocker per that same
  section's wording.

## Definition of done

- Every scenario listed under Acceptance passes (tests written first), except the one explicitly deferred
  (left pending with a comment, not deleted or silently ignored).
- The `@Tag("broker")` real-TLS-reachability JUnit test in `data-rabbit` passes locally with Docker available.
- `core` and `ui` remain free of RabbitMQ/network imports; only `data-rabbit` depends on
  `com.rabbitmq:stream-client`, the HTTP client and `java-keyring`.
- No profile password ever appears in the JSON file or in logs.
- `docs/tasks/README.md` status updated for this task.
- No unrelated changes (no permission-check logic, no stream-list/topology code).

## Status

- Step: 5 (review) — **accepted**
- Round: 2
- Round 2 verdict (architect, 2026-11-04): **Accepted.** Round 1's single blocker (`ConnectionsViewModel.
  updateConnection`/`renameTo` had zero automated test coverage) is closed. The test developer added
  `core/src/jvmTest/kotlin/dev/caerbannog/core/connections/ConnectionsViewModelTest.kt` (commit `0f3ec08`,
  the only file touched since round 1's handover — no production code changed), 5 tests covering: plain
  field edit via `update()` (no rename dance); plain edit with a password change; rename with secret
  migration (old name gone from both repository and secret store, new name present in both); rename with
  a new password overriding the carried-over secret; and a rename collision (`NameAlreadyExists`) leaving
  the original profile *and* its secret completely untouched, including proving a half-applied field edit
  (a changed host alongside the colliding name) does not leak through. Read the test file myself: the
  fakes are minimal and local to `core`'s own test source set (no `acceptance-tests`/`ui` dependency,
  matching `ConnectionsScreenUiTest`'s own pattern), the reachability checker and CA validator fakes
  `error()` if ever called (correctly proving `updateConnection` never reaches them), and every assertion
  checks a real outcome (repository contents, secret-store contents, exact field values) — none is a bare
  "doesn't throw." Independently re-read `ConnectionsViewModel.updateConnection`/`renameTo` myself (not
  just trusting the test developer's confirmation): it branches on `input.name == originalName` for a
  plain `repository.update(...)` vs. `renameTo`'s add-then-delete, `renameTo` calls `repository.add(updated)`
  first and only on `Saved` migrates the secret and deletes the original name (profile and secret) — on
  `NameAlreadyExists` it returns immediately, touching neither the repository nor the secret store. This
  matches decision 1 exactly; no bug found, confirming this was purely a coverage gap, not a logic defect.
  Independently re-ran (not just re-reading the test developer's report): `ConnectionsViewModelTest` (5/5
  green), `core:jvmTest` and `ui:jvmTest` in full (`SpikeSanityTest`, `AppUiTest`,
  `ConnectionsScreenUiTest` all still green), `acceptance-tests`' `01_connections.feature` run (14
  scenarios: 13 passed, 1 skipped — "Switch between connections", correctly still deferred — 0 failures),
  and `data-rabbit`'s full suite including `RealBrokerReachabilityTest` against live Docker (all green).
  No regressions anywhere. Nothing else outstanding from round 1: the two minor UI findings and the
  desktopApp-wiring question were already spun out as `M1-04-connections-screen-feedback-gaps.md` and
  `M1-05-wire-desktopapp-connections.md` last round, confirmed still present as separate task briefs, not
  reopened here.
  **M1-gate statement:** M1-02 is accepted, and with it `RealBrokerReachabilityTest`'s real, non-fake,
  TLS-verified reachability proof against a live Testcontainers RabbitMQ 4.x broker stands as the concrete
  artifact that satisfies M1's own gate in `docs/spec.md`'s Milestones table, "Connects to a local broker."
  That specific gate is met. M1 as a whole is not yet closed: `M1-03` (the permission-check feature,
  `02_permission_check.feature`, still blocked on the non-admin-permission-read spike question) has not
  been started, and `M1-04`/`M1-05` remain open follow-ups. Same position as stated at round 1.
- Round 1 (superseded by the above, kept for history):
- Last verdict (architect, 2026-11-04): **Changes required.** One blocker: `ConnectionsViewModel.
  updateConnection` (the rename/edit orchestration pinned at step 2's decision 1 and decision 4) has zero
  automated test coverage anywhere in the codebase — no Gherkin scenario exercises it, and no unit test
  exists in `core` (`JsonConnectionProfileRepositoryTest` covers the repository's own `update()`, but not
  the view-model-level decision between a plain update and an add-then-delete rename, the secret
  migration, or the non-destructive rollback on a colliding new name). This is in-scope, Goal-relevant,
  non-trivial logic (decision 1: "the senior developer must not skip it") built without a driving test,
  which is a TDD-requirement violation (`docs/spec.md`'s Quality requirement), not a style nit. Everything
  else reviewed (domain model, repository, keychain store, CA validator, reachability checker, the real
  `@Tag("broker")` proof, the Compose screen, and all named Gherkin scenarios) is accepted as-is; see the
  architect's findings table for the two non-blocking notes and the desktopApp-wiring answer, both
  resolved via follow-up briefs (`M1-04-connections-screen-feedback-gaps.md`,
  `M1-05-wire-desktopapp-connections.md`) rather than reopening this task's scope.
  **To close round 2:** add a `ConnectionsViewModelTest` (or equivalent) in `core`'s test source set,
  using small local fakes (same pattern as `ui`'s `ConnectionsScreenUiTest`, since `core` cannot and
  should not depend on `acceptance-tests`), covering at minimum: editing a saved profile's fields without
  renaming; renaming a saved profile (old entry gone, new entry present, secret moved); and the
  non-destructive rollback when the new name collides with another saved profile.
- Verification (test developer, 2026-11-03): pass with findings, no blockers. All 13 in-scope scenarios
  green (including both Scenario Outline rows after the fake fix below), "Switch between connections"
  correctly deferred/pending. `data-rabbit` unit tests, `@Tag("broker")` `RealBrokerReachabilityTest`
  (independently re-run against live Docker, confirmed genuine via throwaway-CA trust restriction, not
  vacuous), and `ui`/`core` `jvmTest` suites all pass. Fixed `FakeBrokerReachabilityChecker`'s
  short-circuit bug (own file) so both Outline rows pass. Two minor exploratory findings for the
  architect to judge (not scenario/spec violations, no action required to accept): (1) `ConnectionsScreen`
  never surfaces `state.lastSaveResult`, so saving over a duplicate name is a silent no-op in the UI; (2)
  `state.lastCheckResult` isn't cleared on `selectConnection`, so switching profiles can show a stale
  check-connection banner from the previously selected profile.
- Open findings (senior developer, build handover, 2026-11-03):
  - **For the test developer:** `acceptance-tests`' `FakeBrokerReachabilityChecker.check()` (`Fakes.kt`)
    short-circuits on a stream-endpoint failure before adding `Endpoint.MANAGEMENT` to `checkedEndpoints`,
    so `ConnectionsSteps.theOtherEndpointIsStillReportedAsReachable()`'s assertion
    (`checkedEndpoints.toSet() == setOf(STREAM, MANAGEMENT)`) fails for the "stream endpoint" row of "Check
    connection names the endpoint that failed" (the "management API" row passes, since that code path adds
    both `Endpoint` entries to `checkedEndpoints` before returning). This is a bug in the fake, not in
    `ConnectionsViewModel` or any other production code: the fake's own step-definition comment states the
    intent ("proves the app actually probed BOTH endpoints rather than stopping at the first failure"), but
    the fake's `check()` body does stop at the first failure for this one row. Not fixed here — `Fakes.kt`
    is test-developer-owned, outside this handover's "write only app code" remit — flagging rather than
    silently patching. Suggested fix (test developer's call): record both endpoints as checked
    unconditionally before branching on which one (if any) failed, e.g. add both `Endpoint.STREAM` and
    `Endpoint.MANAGEMENT` to `checkedEndpoints` up front, then evaluate `streamReachable` /
    `managementReachable` to decide the result. All 13 other in-scope scenarios pass; only this one
    Scenario Outline example is affected.
  - Everything else: all other scenarios/tests green, see handover report for full command/result list.
- Open questions:
  - Product decision made (2026-11-03): added a per-connection "self-constraint" topology control level
    (Read-only default / Declare and edit / Full control), sitting alongside the automatic broker-permission
    read as an additional ceiling (effective permission = the stricter of the two). Confirmed as proposed,
    topology-only (not publishing). `docs/spec.md`'s "Topology migrations and permission guardrails" section
    rewritten accordingly; `topologyControlLevel` added to this task's `ConnectionProfile` scope
    (storage/editor only, no enforcement — enforcement is M1-03/M5 work).
  - Permission check itself (`02_permission_check.feature`) stays deferred to `M1-03`, confirmed by this
    brief's own Scope/Out section.
  - "Switch between connections" scenario deliberately deferred to M2 (stream list doesn't exist yet).
  - Architect decisions made (2026-11-03), see Design notes "Decisions pinned at handover from step 2":
    `ConnectionProfileRepository.add()`/`update()` split confirmed; `CaCertificateValidator` added to the
    component list (`core` interface + `data-rabbit` `X509CaCertificateValidator` impl); fakes-vs-real-file
    split for the password-storage scenario confirmed as implemented; `ConnectionsViewModel` surface
    confirmed with one addition (`updateConnection`, covering profile renames); `KeyringSecretStoreTest` and
    `CaCertificateValidatorTest` confirmed in scope.
  - New, pinned but not yet a Gherkin scenario: renaming an existing profile's name on Save (non-destructive
    add-then-delete rule, see Design notes decision 1 and Open points) — optional follow-up to add a
    scenario, not a blocker.
  - Carried forward, not this task's concern: non-admin permission read (blocks M1-03), Windows keychain
    round-trip unverified.
  - Carried forward caveat for the senior developer (not a question): `RealBrokerReachabilityTest`'s TLS
    `rabbitmq.conf` is an unproven first attempt (never run against a live container) — see Design notes
    "Caveat carried forward to the senior developer". Expect a possible config-key surprise, budget time,
    flag back here if it's genuinely hard rather than reshaping the gate.
    **Resolved, no surprise found:** ran clean on the first attempt against a live Docker daemon (Docker
    Desktop 29.8.2) once the real `StreamAndManagementReachabilityChecker` existed to make it pass — no
    RabbitMQ 4.x config-key rename needed. See handover report for the exact command and timing.
  - Build-time engineering calls made by the senior developer (none escalated — all within "the developer's
    call" per Design notes, or ordinary build-time detail per decision 4's UI-selection-state note):
    - Stream-port reachability: a raw `SSLSocket` handshake (Design notes explicitly leaves this choice
      open), not `com.rabbitmq:stream-client` — the real stream protocol is not exercised until M2, so no
      new dependency was added for a bare TLS-handshake probe.
    - Management-API reachability: Ktor client with the Java engine (`io.ktor:ktor-client-java`, wrapping
      `java.net.http.HttpClient`), chosen because it is the simplest way to plug in a custom `SSLContext`
      built from an optional per-profile CA certificate; version `3.0.3` (no Ktor version was pinned
      anywhere before this task).
    - `ManagementPluginMissing` detection against a real broker probes `GET /api/stream/connections`
      unauthenticated and treats HTTP 404 as "plugin not registered" — this specific endpoint path is this
      developer's best-effort guess from RabbitMQ's naming convention, **not verified** against a real
      broker with `rabbitmq_stream_management` disabled (`RealBrokerReachabilityTest` only proves the
      plugin-enabled path); flagged with a `TODO(spike)` comment in
      `StreamAndManagementReachabilityChecker.kt`. Same category of risk as the already-tracked "management
      API field names for publishers/consumers" spike item — worth a follow-up to confirm for real, not a
      blocker for this task (no scenario requires a real-broker proof of this specific branch, only the
      `@fake-broker` scenario does, and that already passes).
    - Connection-profile JSON file storage uses kotlinx-serialization (already the project's chosen JSON
      library for the management API per `docs/spec.md`'s tech-stack table) via a private DTO in
      `data-rabbit`, not `core`'s `ConnectionProfile` directly, so `core` never imports the serialization
      library.
    - `desktopApp`'s placeholder `App()`/`main()` was deliberately left untouched: wiring the real
      Connections screen into the actual running desktop app would need `desktopApp` to depend on
      `data-rabbit` for the real `JsonFileConnectionProfileRepository`/`JavaKeyringSecretStore`/
      `StreamAndManagementReachabilityChecker`/`X509CaCertificateValidator` wiring, which is not named in
      this task's Scope or Definition of done (only "the Compose Connections screen in `ui`" is) — flagged
      as a natural follow-up, not done here to avoid unrequested, unrelated changes.
- Branch: `m1-02-connection-profiles`
