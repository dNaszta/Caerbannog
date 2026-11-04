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

The real-keychain JUnit tests (`KeyringSecretStoreTest`, `KeyringRoundTripSpikeTest` in `data-rabbit`) are outside the Gherkin tags: they carry the JUnit tag `keychain` and run only via `./gradlew :data-rabbit:keychainTest` (`task test:keychain`), never in `./gradlew test`.
| `@needs-M1-06` | Retired: declared permissions on the profile (task `M1-06`) are built and their two scenarios in `02` run in the normal `@fake-broker` set; the tag is no longer used on any scenario |

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

Unit-level coverage with no Gherkin scenario (behaviour users see stays in `02`):

| Task | Tests | Covers |
| --- | --- | --- |
| M1-09 permission check hardening | `ConnectionSessionTest`, `PermissionDecisionTest`, `ConnectionsViewModelPermissionTest` (core); `PermissionCheckerFailureTest` (data-rabbit, no Docker) | A failed check never throws: `Unreadable(UNEXPECTED)`, declaration applied except without a password, no secret in the result, overlapping opens, invalid URL and silent server |

## Open points that affect these tests

- Exact fields of the stream publisher and consumer endpoints (`04`, `@needs-spike`).
- What happens when a migration fails half-way (`10`, `@needs-decision`).
- Whether streams are found by name through the same API call as queues (`11`, `@needs-spike`).

Resolved: how a non-administrator user's own permissions are read (`02`): they cannot be; safest mode by default, plus optional permissions declared on the profile (`docs/spec.md`, "Broker-read permission").

Resolved: whether the broker's configure permission alone is a sufficient guardrail. Resolved by the
self-constraint level (`docs/spec.md`, "Topology migrations and permission guardrails"): the app adds its own
rule on top, reserving delete for the Full control level. `01` now covers storing/editing the level; `02` and
`10` will need scenarios for the combined effective-permission check once M1-03 and M5 scope that enforcement.
