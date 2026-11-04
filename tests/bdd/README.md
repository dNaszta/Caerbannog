# Caerbannog BDD tests

Plain-language scenarios that describe what Caerbannog must do, written as a product owner and test engineer.
They follow `docs/spec.md` and the screens in `docs/ux/`. Each `.feature` file covers one area of the spec.

Status: the scenarios are written first (TDD/BDD). Step definitions do not exist yet.
The runner (Cucumber-JVM with Kotlin steps) is a proposal to confirm in the M1 spike; the files are plain
Gherkin and will work with any Gherkin runner.

## Layout

```
tests/bdd/
  README.md
  features/
    01_connections.feature
    02_permission_check.feature
    03_stream_list.feature
    04_publishers_consumers.feature
    05_message_browser_paging.feature
    06_super_streams.feature
    07_payload_viewer_filter.feature
    08_publish_test_message.feature
    09_topology_queues.feature
    10_topology_migrations.feature
    11_global_search.feature
    12_stream_management.feature
    13_connection_lifecycle.feature
```

## Tags

| Tag | Meaning |
| --- | --- |
| `@M1` to `@M5` | Milestone that must make the scenario pass (see the milestones table in `docs/spec.md`) |
| `@gate-M2`, `@gate-M4`, `@gate-M5` | Scenario that is the gate check for that milestone |
| `@smoke` | Smallest set that proves the feature works; run on every commit |
| `@fake-broker` | Runs against in-memory fakes of the stream and management clients; no broker needed |
| `@broker` | Needs a real RabbitMQ 4.x broker with TLS (Testcontainers, needs Docker) |
| `@negative` | Checks a refusal, an error or a safety rule |
| `@security`, `@safety` | Protects a security or data-flow rule from the spec (TLS only, never reading queue messages, browsing never changes offsets) |
| `@needs-spike` | Depends on something the M1 spike must confirm (API fields, keychain, search by name for streams) |
| `@needs-decision` | The product behavior is not decided yet; the scenario states the proposed behavior |
| `@needs-M2` | Step definitions exist and compile but are deliberately left pending (`PendingException`) because the scenario needs a later milestone's feature ("Switch between connections" in `01`, needs M2's stream list; in `02` the browsing scenario and the three topology-screen text scenarios, which need the M2 topology screen); excluded from the default Cucumber run |
| `@needs-M1-07` | Retired: the permission-state UI (task `M1-07`) is built and its five scenarios in `02` (sidebar pill, edit panel banner wording, asserted in core through `permissionPill` / `permissionBanner`) run in the normal `@fake-broker` set; the tag is no longer used on any scenario |
| `@needs-M1-06` | Retired: declared permissions on the profile (task `M1-06`) are built and their two scenarios in `02` run in the normal `@fake-broker` set; the tag is no longer used on any scenario |

The real-keychain JUnit tests (`KeyringSecretStoreTest`, `KeyringRoundTripSpikeTest` in `data-rabbit`) are outside the Gherkin tags: they carry the JUnit tag `keychain` and run only via `./gradlew :data-rabbit:keychainTest` (`task test:keychain`), never in `./gradlew test`.

Suggested runs: `@smoke` on every commit, `@fake-broker` in the normal build, `@broker` in a separate CI job.

## Vocabulary used in steps

- "the connection" is a saved profile; each profile is its own environment.
- "locked" and "unlocked" describe topology changes, decided by the configure permission.
- "offset" numbers start at 0; "page size" is the number of messages per page.
- "the broker" is the fake in `@fake-broker` scenarios and a real RabbitMQ in `@broker` scenarios.

## Traceability

| Feature file | Spec section | Milestone |
| --- | --- | --- |
| 01 connections | Screens (Connections), Architecture (TLS only), Topology migrations and permission guardrails (self-constraint level) | M1 |
| 02 permission check | Topology migrations and permission guardrails | M1 |
| 03 stream list | Goals (stream list), Screens (Stream list) | M2 |
| 04 publishers and consumers | Goals (publishers and consumers) | M2 |
| 05 message browser paging | Paging model | M3 |
| 06 super streams | Paging model (super streams), Screens | M2, M3 |
| 07 payload viewer and filter | Goals (viewer, filter), UX rules | M4 |
| 08 publish test message | Goals (publish), Screens | M5 |
| 09 topology and queues | Goals (quorum queues), Screens (Topology) | M2, M5 |
| 10 topology migrations | Topology migrations and permission guardrails | M5 |
| 11 global search | Goals (global search), Screens | M2, M3 |
| 12 stream management | Goals (publish and manage), UX rules | M5 |
| 13 connection lifecycle | Connection lifecycle (decisions D1, D2, D6, D7) | M2 |

Unit-level coverage with no Gherkin scenario (behaviour users see stays in `02`):

| Task | Tests | Covers |
| --- | --- | --- |
| M1-09 permission check hardening | `ConnectionSessionTest`, `PermissionDecisionTest`, `ConnectionsViewModelPermissionTest` (core); `PermissionCheckerFailureTest` (data-rabbit, no Docker) | A failed check never throws: `Unreadable(UNEXPECTED)`, declaration applied except without a password, no secret in the result, overlapping opens, invalid URL and silent server |
| M2-01 connection session | `ConnectionSessionTest`, `ConnectionsViewModelSessionTest` (core); `ConnectionsScreenUiTest` drawer card tests (ui) | The latest started check per profile name wins whichever finishes last, and a stale or forgotten check never makes a connection usable; each name keeps its own result; `current` follows select, delete, forget and re-check; the edit-panel selection and the current connection are independent; the drawer Connection card shows the current connection only. Two `02` scenarios cover the user-visible part (`@fake-broker`). |
| M2-01b single session state | `ConnectionSessionStateTest`, `ConnectionsViewModelSingleStateTest` (core) | The session exposes one `state: StateFlow<SessionState>` (statuses plus current): a reader or collector never sees a current connection whose status is not in the statuses (concurrent, no sleeps); the view model state stays synchronously readable, collectable and consistent; no `ExperimentalForInheritanceCoroutinesApi` opt-in left in any `.kt` file (source scan); after concurrent session changes the view model equals the final session state; after `close()` the view model stops following. No scenario: no user-visible change. |
| M2-03 part 1 connection lifecycle | `ActiveConnectionTest`, `ActiveConnectionSafetyTest`, `ConnectionsViewModelActiveConnectionTest` (core, with `FakeConnectionGateway`); `ConnectionsScreenUiTest` host name note (ui); Gherkin: `13` via `RunConnectionLifecycleAcceptanceTest` | Connect order (close held, select, Connecting, permission check, open clients, Connected), progress with a gated slow broker, Disconnect and window close release exactly once and are idempotent, one held connection at a time, latest started connect wins and a superseded or cancelled connect closes what it opened, the four failure reasons with exact texts, Unreadable permissions and a silent stream port still Connected, edit, rename, CA change and delete of the held profile (note only for settings changes; a failed rename keeps the connection), no password in any exposed value (recognisable password in the fake store), invariants under 400 concurrent connect/disconnect calls |
| M2-03 part 2 stream catalog (tests first, red until built) | core: `VisibilityTest`, `FakeStreamCatalogTest`, `FakeStreamOffsetsTest`, `ActiveConnectionCatalogTest` (with `FakeStreamCatalog`, `FakeStreamOffsets`, shared through the same extra srcDir as `FakeConnectionGateway`); data-rabbit: `ManagementJsonTest`, `ManagementErrorsTest`, `ManagementRequestsTest`, `FirstOffsetMapperTest`, `RabbitMqHeldConnectionTest`, `RabbitMqConnectionGatewayTest`, `TlsSupportTrustTest`, `StreamHostNameToleranceTest` (D7 on the stream side, local TLS server, no Docker), `RealBrokerCatalogTest` (@broker, incl. broker stopped mid-session and the `x-max-age=7D` retention text); desktopApp: `CompositionRootWiringTest`; Gherkin: `13` ("A user without the management tag ..." now runs, "first offsets will read unavailable" asks the real `StreamOffsets` port, "the app holds one connection") | Every recorded fixture parses to the data classes; error bodies map to `NotAuthorised`, `PluginMissing`, `BadPaging`, `NotFound`, `Unexpected`; the two 401 bodies are told apart (`Not management user` is not a failure); visibility from tags; request URLs and TLS-only; the fake pages like the broker (over all queues, so a page can hold fewer streams); `FirstOffset` Value, Empty, Unavailable and the exception mapping; calls off the caller's thread and limited in parallel; the holder logs only the exception class name on a failed close and closes once; no password in a failed Connect; a wrong CA is refused by the shared trust source; `ActiveConnection` and the close request are wired in the composition root; a window closed during a running Connect exits and leaves nothing open |
| M2-04 stream list core (tests first, red until built) | core: `RetentionDisplayTest` (D9), `RefreshIntervalTest` (D3 set and default), `StreamListViewModelRowsTest`, `StreamListViewModelPagingTest`, `StreamListViewModelRefreshTest`, `StreamListViewModelConnectionTest`, `StreamSourceTest` (accessor, D2 `connectionLost`), `FakeStreamCatalogTest` additions (`publisherCounts`, upsert, `holdNext`, `failListPage`); fakes `FakeAppSettings`, `CallGate` (in the `core-testing` module); data-rabbit: `ManagementRequestsTest` / `ManagementJsonTest` (`publisherCounts`), `ManagementErrorsTest` (F1: no `body` parameter), `StreamEnvironmentsFailureTest` (F1: no `CompletionException`), `RealBrokerCatalogTest` (@broker `publisherCounts`), `RealBrokerStreamListTest` (@broker, verify step: view model on a real broker in real time: wrong password, tick picks up a new stream, same-profile reconnect, profile switch around a tick, broker stopped mid-session; its vhost test and `StreamListViewModelRowsTest` "streams of another virtual host" are RED until finding F-1 is fixed (ruling: `StreamListRequest.vhost`, `/api/queues/{vhost}`, fake filters by vhost; new scenario "Another vhost's streams are not listed" in `03`, steps still to write)); desktopApp: `CompositionRootWiringTest` (logger parameter); Gherkin: `03` via `RunStreamListAcceptanceTest` (all `@fake-broker` except `@needs-M2`), virtual time | Display rules (counts with `n/a`, offsets loading, value, unavailable, blank; last offset = first + messages - 1; retention `7D`; no size member), D8 publishers only for FULL with the plugin and one call per load, paging over all queues in broker pages of 500 with the display total counting streams (12 among 700 queues reads "1 to 5 of 12", whole-load failure keeps old rows stale), in-memory filter and sort, the D3 timer in virtual time (not shown = no call, skip on overlap, cancel on Disconnect, switch and supersede, late results dropped, never a permission check), D2 (decided) (`Connection lost`, stale rows, no reconnect, Retry), the accessor (`source` only while Connected, dead source answers Unreachable and touches nothing) |

## Open points that affect these tests

- `04` keeps `@needs-spike` until M2-06 reviews it; the exact fields of the stream publisher and consumer endpoints were confirmed by the M2-02 spike (`docs/spec.md`, "Management API facts"). What users without the management tag, or with only the `management` tag, see is decided (S3a, S3b) and covered by `03` and `04`.
- What happens when a migration fails half-way (`10`, `@needs-decision`).
- Case-sensitivity of the server-side `name` filter (the `11` scenario "Search is case-insensitive for plain text"); not tested by the M2-02 spike.
- `13`: the lost-connection rule (D2) is decided (2026-11-04) and its scenario is `@needs-M2` until M2-05 builds the screen; D3 (refresh) is decided: interval in seconds in the connection control, values Off, 5, 10, 30, 60, per app, default 5 seconds (product owner 2026-11-04, to be revisited after trying); the `03` scenario "The refresh interval is 5 seconds on a fresh install" is no longer `@needs-decision`; the lost-connection rule D2 is not yet confirmed. The last three scenarios in `13` (the app bar connection control) are `@needs-M2` and belong to M2-05, not M2-03.
- The Size column in `03` and `06` is left blank until M3 (decision N1 (c)).
- Whether a user without the management tag can call `queryStreamStats` (mini-spike M2-03a): the "first offset unavailable" scenario in `03` is the fallback either way.

Resolved: how a non-administrator user's own permissions are read (`02`): they cannot be; safest mode by default, plus optional permissions declared on the profile (`docs/spec.md`, "Broker-read permission").

Resolved: whether the broker's configure permission alone is a sufficient guardrail. Resolved by the
self-constraint level (`docs/spec.md`, "Topology migrations and permission guardrails"): the app adds its own
rule on top, reserving delete for the Full control level. `01` now covers storing/editing the level; `02` and
`10` will need scenarios for the combined effective-permission check once M1-03 and M5 scope that enforcement.

- Test code that calls suspending functions uses `runBlocking<Unit> { ... }` or `runTest`: a `@Test` function that returns a value is silently never run by JUnit 5 (task `M2-TESTHYGIENE-01`). The Gradle task `verifyTestDiscovery` (part of `check` and of root `./gradlew test` / `task test`) fails the build when a test class's `@Test` count differs from the XML testcase count, or a `@Test` function has a non-Unit declared type or ends in `assertIs`/`assertFailsWith`/... inside `= runBlocking { }`; `@broker`, `@keychain` and screenshot classes are compared only when their own task has run (otherwise "unchecked"). Its self-test fixtures are in `tests/guard-fixtures/`.
- Cucumber `FILTER_TAGS` exclusions show as skipped in the XML but still count in the results (the acceptance totals include them).
