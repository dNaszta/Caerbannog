# M8 gate: migrate a 3.13 topology to 4.x and move a message

Milestone: M8    Depends on: M8-06, M8-08, M8-09, M8-12, M5-08 (all merged or merging with `M8-12b`); NOT on M8-10 (the gate does not use MCP)    Size: M

LEAN (owner, 2026-11-11): one end-to-end happy path with the safeguard assertions, no extra cases (the wrong password, destination missing, the in-place upgrade, TLS are covered by the `M8-08`, `M8-12c` and spike live classes).

## Goal
`RealBrokerM8GateTest` (`@gate-M8`, tags `broker` and `broker-3x`, in `data-rabbit/src/test`) proves the whole M8 flow on real brokers with the app's real classes: live analysis of a 3.13 topology, review and generate, apply to a 4.x broker, forward one queue with a Shovel, a migrated user logs in.

## Why
Spec milestone table, M8 gate; CLAUDE.md "Keep `RealBrokerM7GateTest` and `RealBrokerM8GateTest` green" (this class does not exist yet; this task creates it).

## What exists (the gate only composes it)
`ManagementThreeXReads` + `analyseLive` (M8-02b), `ConversionReview` and `MigrationGenerator` (M8-05, with the access file of M8-12b), `MigrationRunner` + `ManagementTopologyMigrationExecutor` (M5-08, M8-12c access actions), `ForwardingService` + `ManagementShovelGateway` (M8-07, M8-08), helper `M8SpikeSupport` (two containers on one network), `RealBrokerM8ShovelTest` and `RealBrokerM8AccessTest` (patterns for polling, cleanup, peeking with the test's own client).

## Scope
In (one test class, one container pair per class, unique names, cleanup in `@AfterAll`, every wait a bounded poll with a failure message):
1. Fixture on `rabbitmq:3.13-management` through the management API (test code, not the app): the default vhost `/`; a mirroring policy `ha-all` (`ha-mode: all`); the classic queue `orders` (durable, no priority) holding the message `hello` (body checked later); a transient queue `temp` (`durable: false`); an exchange `events` with a binding to `orders`; users `svc` (a known test password; its hash is whatever 3.13 stores, sha256) with permissions on `/`, and `old` with a bcrypt-style hash set through the definitions import (unsupported algorithm), plus `guest`. `rabbitmq:4-management` with the shovel plugins enabled (the destination), reachable from the 3.13 container by network alias.
2. The app side, real classes: the 3.13 client is a `ManagementHttp` built with a RECORDING Ktor plugin (every request method and path is kept); `analyseLive(LiveDefinitionsSource(ManagementThreeXReads))` gives the report; the review: accept every proposal that can be accepted, Skip the items that cannot (`temp`); `generate` gives the access file (0001: `svc` declared, `old` and `guest` listed as not migrated) and one topology file; both are applied through `MigrationRunner` on a 4.x administrator profile at Full control (the container's admin user has the administrator tag: the permission check reads the tags through the real checker), and the dry run of each shows the rows without a hash.
3. Forwarding: `ForwardingService` over `ManagementShovelGateway` (plain source address, the per-profile "Use TLS" off, so the confirmation says "Not encrypted"; the source password read from a test `SecretStore`): `prepare` is Ready with 1 ready message, `start(request, "orders")`, poll (bounded) until `Finished`.
4. Assertions: the quorum queue `orders` on 4.x holds the message with the body `hello` intact (checked with the test's own management peek on 4.x, requeue); the queue `orders` still exists on 3.13 (and its other state untouched); the recorded requests to 3.13 are ALL `GET` (the analysis and the source probe); the Shovel parameter is gone after `queue-length`; the user `svc` logs in on 4.x (management `GET /api/whoami`) with its OLD password, and `old` and `guest` do not exist on 4.x; a second apply of both files writes nothing (the runner reports already applied, and a forced re-run of the executor is all no-op); no sentinel appears anywhere: the test's password, the hash text of `svc`, `amqp://` followed by credentials, in captured `java.util.logging`, stdout and stderr, in `toString` of every state and result of the run, in the report JSON and text, the generation report and the Migrations raw-view text (`redactedRaw`). A request recorder on the 4.x side asserts no request path is a message path (`/get` is the test's own peek and is excluded by using a separate client).
5. Mutation kills (step 4 of the loop, by the test developer): skip the typed-name check, `ack-mode` changed, a queue DELETE added to the shovel path, a non-GET sent to 3.13, the hash printed in a reason: each makes this class or its unit twin red.
Out: new features, MCP, the in-place upgrade, `amqps`, other failure cases.

## Acceptance
- BDD: `16` / "Migrate a 3.13 topology to 4.x and move a message" (`@gate-M8 @broker-3x @m8-11`) is bound by this class (its Given/When/Then are the numbered steps above; no Cucumber step, like the other live gates).
- Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8GateTest*'` once in step 4 and once after any fix; nothing else needs the broker. `RealBrokerM7GateTest` and the other live classes are NOT run unless a shared class changed (none is expected). Fast unit suites stay as they are.

## Design notes
Unique queue, vhost and user names with a run id; both containers are stopped in teardown; every bounded poll names what it waited for. The test must not call any app class in a way production does not (no private access). The recording plugin is test code only; no production change is expected; if one is needed (a seam), the architect reviews it.

## UX
None.

## MCP
None (the report tool is `M8-10`; this gate does not use MCP).

## Open points
Needs Docker or rootless Podman (as every `broker` test). Time: two containers, a few minutes.
`amqps` between containers and the other failure cases stay covered by `M8-08` and `M8-12c`.

## Definition of done
Gate green on a machine with a container runtime, mutations of the key safeguards killed, spec milestone status updated with the date, no change outside `data-rabbit/src/test` (and the README rows).

## Status
- Step 1 (architect, 2026-11-11): made exact against main plus `M8-12b`; independent of `M8-10`; the 3.13 scenario in `16` carries `@m8-11`. Not started.
