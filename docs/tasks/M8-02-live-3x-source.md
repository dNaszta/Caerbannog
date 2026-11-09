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
Version rule lives in `core` as a pure function. Keep the "one held connection" rule: the fetch result is an in-memory model owned by an M8 session object, not by `SessionState`. Missing or unparsable version: treat as unknown and say so, never assume 4.x. Do not add a profile field (proposed decision P6, awaiting owner confirmation) unless `M8-00` S-H shows `rabbitmq_version` is not reliable.

## UX
Pill wording in the spec; the Connections screen mockup gets the pill variant (`docs/ux/Main.dc.html` update by the architect before step 2).

## MCP
None here (`M8-10`).

## Open points
Whether `GET /api/definitions` needs the administrator tag (UNVERIFIED, S-A). Large exports (S-L).

## Definition of done
Tests first and passing, GET-only test green, no 4.x behaviour changed, spec updated.
