# M8 gate: migrate a 3.13 topology to 4.x and move a message

Milestone: M8    Depends on: M8-06, M8-08, M8-09, M8-10, M8-12    Size: M

## Goal
`RealBrokerM8GateTest` (`@gate-M8`, tag `broker-3x`) proves the whole flow on real brokers.

## Why
Spec milestone M8 gate.

## Scope
In: two Testcontainers on one network (`rabbitmq:3.13-management` source with a mirroring policy, a mirrored classic queue holding a message, a priority queue, an exchange and bindings; `rabbitmq:4-management` destination with the shovel plugins): live analysis, accept every proposal, generate and apply the migration through the existing runner, dry run, forward `orders`, wait (bounded polling) until the shovel finishes. Assertions: the quorum queue on 4.x holds the message with intact body; the queue still exists on 3.13 (the app did not delete it); only GET requests reached 3.13 from the app (request recorder); no password or shovel URI in captured logs, screens text or exported files; a user `svc` of the 3.13 broker (known test password) logs in on 4.x after the access migration, so the hash passed through, and a re-run of the access migration is a no-op; no hash appears in captured logs, screens or reports; a re-run of the migration is a no-op; the shovel is gone after `queue-length`. The gate uses a plain profile through the per-profile TLS switch (explicit "Not encrypted"); `amqps` is covered by the unit and `M8-08` live tests with the TLS fixtures.
Out: new features.

## Acceptance
- BDD: `16` / "Migrate a 3.13 topology to 4.x and move a message" (@gate-M8 @broker-3x), "A real RabbitMQ 3.13 export is read and analysed".
- All earlier suites green; `M7` gate unaffected (`RealBrokerM7GateTest`).

## Design notes
Containers share a network alias for the shovel source URI. Unique names, clean up in teardown. Do not run the whole `brokerTest` suite unless shared transport code changed.

## UX
None.

## MCP
None (the report tool is covered by `M8-10`).

## Open points
Packaging impact of any new dependency (none expected).

## Spike findings (M8-00, 2026-11-09)
Gate additions from the spike: the 3.13 fixture also holds a transient queue and a `x-queue-master-locator` queue (the analyzer must flag them and the apply must not rely on the import), a priority-carrying message (the report shows the ordering warning and the tick is required), a user with a bcrypt or unknown hash (blocker, manual step) next to a sha256 user that logs in on 4.x; after the apply the runner's read-back finds every declared queue; the Shovel is polled to completion and, once finished, its parameter is gone; a deliberate wrong-source-password run ends `terminated` and is reported as failed; the destination-missing case is blocked (no durable classic queue appears); no source password appears in any log, screen text or exported file, including after a read of the 4.x definitions. The in-place upgrade is NOT part of the gate (S-N).

## Definition of done
Gate passes on a machine with Docker or Podman, mutations of the key safeguards (confirmation bypass, ack mode, queue delete) are killed by tests, spec milestone status updated with the date.
