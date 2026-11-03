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

- Step: 2 (tests first) — in progress
- Round: 1
- Last verdict: none yet
- Open findings: none yet
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
  - Carried forward, not this task's concern: non-admin permission read (blocks M1-03), Windows keychain
    round-trip unverified.
- Branch: `m1-02-connection-profiles`
