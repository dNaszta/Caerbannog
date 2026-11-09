# Live 3.x source: version detection, read-only source role, definitions fetch

Milestone: M8    Depends on: M8-00, M8-01    Size: M

## Goal
A connection profile that points at a RabbitMQ 3.x broker is recognised on Connect as a migration source, locked to read-only, and its definitions can be fetched through the management API into the model of `M8-01`, sending only GET requests.

## Why
Spec decision 1 (live source) and "3.x as a read-only source"; the app otherwise supports 4.x only.

## Scope
In: `core` interface `DefinitionsSource` (fetch(profile): FetchResult sealed: Fetched, NotPermitted, Unreachable, Failed), `BrokerVersion` parsing, effective-permission rule "major below 4 means Read-only and no publishing" in the existing permission and session logic, connection pill text "RabbitMQ 3.x: migration source only"; `data-rabbit` implementation: `GET /api/overview` for the version, `GET /api/definitions` (or per-vhost, per `M8-00` S-A/S-L), reuse of the existing management client, TLS and permission rules (D9a, D7) unchanged; fake in `core-testing`; a test asserting every request to a 3.x source is a GET.
Out: UI screen (`M8-06`), analysis.

## Acceptance
- BDD: `16` / "Analyse a live RabbitMQ 3.x connection", "A user who may not export definitions is offered the file import" (@needs-spike), "A RabbitMQ 3.x connection is a migration source only", "Analysing a live 3.x broker sends only read requests" (@safety), "The analysis survives switching to the 4.x connection", "A real RabbitMQ 3.13 export is read and analysed" (@broker-3x).
- The existing 4.x scenarios in `01`, `02`, `13` stay green unedited.

## Design notes
Version rule lives in `core` as a pure function. Keep the "one held connection" rule: the fetch result is an in-memory model owned by an M8 session object, not by `SessionState`. Missing or unparsable version: treat as unknown and say so, never assume 4.x. Do not add a profile field (decision P6, 2026-11-09) unless `M8-00` S-H shows `rabbitmq_version` is not reliable.

## UX
Pill wording in the spec; the Connections screen mockup gets the pill variant (`docs/ux/Main.dc.html` update by the architect before step 2).

## MCP
None here (`M8-10`).

## Open points
From `M5-08a` (final review, 2026-11-10): `MigrationAccess.forBroker` (data-rabbit) already reads `GET /api/overview` once at connect (5 s limit) and keeps only the executor decision and the fixed reason; this task reuses that one read and adds the version text to the held connection instead of making a second call; the connection pill "RabbitMQ 3.x: migration source only", the forced Read-only level and the disabled stream features stay here (and `M5-08c` shows `migrationUnavailableReason` on the Migrations screen). A broker whose overview cannot be read is treated as unknown (no executor): the live 3.x source reading must say "could not read the version" the same way.
M5-08a review (2026-11-09, F3): the 3.x version check that keeps a 3.x connection from getting a migration executor is done in `M5-08a` (`brokerMajorVersion` in `core`, one `GET /api/overview` when the connection opens; unknown or 3.x means no executor and a fixed reason). This task still owns: reading the live 3.x source (definitions, `storage_version` with retry, deprecated features), the connection pill "RabbitMQ 3.x: migration source only", the forced Read-only level, disabled stream features, the held connection's version field (reuse the one overview read, do not add a second), and the analysis from a live connection. Scenario "A 3.x source connection cannot apply migrations" moved to `M5-08a`; the screen wording of that case is `M5-08c`.
Whether `GET /api/definitions` needs the administrator tag (UNVERIFIED, S-A). Large exports (S-L).

## Spike findings (M8-00, 2026-11-09)
Measured: `GET /api/definitions` needs the administrator tag (401 "Not administrator user" for management, policymaker and monitoring users; also per vhost), so the live source needs an administrator profile and everything else falls back to the file import with the stated reason; `rabbitmq_version` is `"3.13.7"`; `whoami` and `permissions` have the same shape on 3.13, so Connect and the permission check need no change; `/api/stream/...` is 404 on 3.x (already handled); 1000 queues export in about 10 ms (84 KiB), no streaming. Also read `GET /api/deprecated-features/used` from the 3.x source when allowed (it reported `classic_queue_mirroring` for a mirroring policy) and hand it to the analyzer as an extra signal; the call is a GET and its failure never fails the fetch.

## Spike S-N finding
S-N (spike results): for every classic queue the live scan reads `storage_version` (1 or 2) from `GET /api/queues/{vhost}` (or the single-queue call) on the 3.x source; the value appears a few seconds after the queue starts, so a missing value is retried (bounded polling, no sleep) and finally reported as "unknown" (treated like version 1); quorum queues have none. The definitions export does not carry it. The values are GET-only and go into the model beside the definitions.

## Definition of done
Tests first and passing, GET-only test green, no 4.x behaviour changed, spec updated.
