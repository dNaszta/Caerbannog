# M7-08: Non-destructive queue message inspection (requeue peek)

Milestone: M7    Depends on: M2-08 (topology screen), M4-01 (payload viewer formatting)    Size: L    Commit: d501d11 (the commit message names M7-08)

## Goal
In the Topology queue detail the user can fetch the first messages of a queue, read them (text, JSON, hex) and see that they were not acknowledged and went back to the queue.

## Why
Accepted by the product owner with M7 on 2026-11-09 as an exception to the old rule "queue messages are never read". It is the only accepted read path for queue messages; do not add another. Spec: "Queue message inspection (M7-08)".

## Scope (what shipped)
In:
- `core/topology/QueueMessageInspection.kt`: `QueueInspectionConstants` (`ACK_REQUEUE_TRUE` = "ack_requeue_true", default count 10, `MAX_INSPECT_COUNT` 50, badge "Not acknowledged (requeued)", requeue notice); `InspectedQueueMessage` (always `acknowledged = false`); `QueueInspectionResult` Success, Refused, Failed; `QueueMessageInspector` interface with `UnavailableQueueMessageInspector`; `QueueMessageInspectionViewModel` with states Idle, Loading, Empty, Loaded, Error.
- `data-rabbit/ManagementQueueMessageInspector`: `POST /api/queues/{vhost}/{name}/get` with body `{"count": n, "ackmode": "ack_requeue_true", "encoding": "auto", "truncate": 65536}`; count clamped to 1..50; vhost and queue URL-encoded. Outcomes: 200 parsed (string and base64 payloads; a bad base64 falls back to the raw text), 401 and 403 Refused "User does not have read access to queue", 404 Refused "Queue ... not found", other status, unreachable, certificate rejected, no TLS answer: Failed with a safe message.
- Wired through `StreamSource.queueInspector` and `ActiveConnection`, `RabbitMqHeldConnection`, `Main.kt`; `ui/topology/QueueMessageInspectionView.kt` (count options, Inspect and Clear buttons, requeue notice, list and detail with viewer tabs) inside `TopologyQueueDetail`.
Out: any ack mode other than requeue; consume, get with ack, delete, purge; an MCP tool.

## Acceptance
- `core/.../topology/QueueMessageInspectionViewModelTest`: requeue mode and not-acknowledged marking; empty queue; refused or failed gives Error.
- `data-rabbit/.../ManagementQueueMessageInspectorTest`: URL encoding; "sends POST with ack_requeue_true and parses string and base64 messages as not acknowledged"; "maps 401 and 403 to Refused" (the test uses 401 only).
- `ui/.../topology/TopologyDetailAndSafetyUiTest`: "non-destructive queue message inspection shows requeue mode notice and marks messages as not acknowledged".
Not covered by a test: the 404 outcome, the count clamp (1..50), the 64 KB `truncate` value, certificate and TLS failures, a live broker.

## Design notes
Read-only access path: no topology permission is needed or checked beyond the broker's own read check (401/403). The request goes over the profile's management transport (TLS unless the per-profile switch is off, D9a). Messages are never read through the stream client.

## UX
No file in `docs/ux/`. States: idle, loading, empty, error, loaded.

## MCP
None, deliberately. The delivery-limit risk is now disproved (see below), so this can be revisited as a product decision.

## Non-goals
Choosing an ack mode, paging beyond 50, filtering, acknowledging, republishing.

## Open points
**RISK RESOLVED 2026-11-09:** the worry was that a requeue counts as a delivery attempt toward a quorum queue's delivery limit and dead-letters messages. `RealBrokerM7GateTest` (live RabbitMQ 4) shows it does not: 8 inspections of a queue with `x-delivery-limit` 2, and 30 of a queue with the default limit, lost no message and a consumer afterwards received all of them. Scenarios covered live: non-destructive and repeatable (same order and bodies, count unchanged), properties and headers visible, never acknowledged, unknown queue refused.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
