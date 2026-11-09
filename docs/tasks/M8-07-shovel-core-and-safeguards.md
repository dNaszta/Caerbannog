# Shovel forwarding: core interface, dry run, safeguards

Milestone: M8    Depends on: M8-00 (S-F), M8-02 for the source profile probe    Size: M

## Goal
`core` defines `ShovelGateway` and a `ForwardingPlanner` that decide, from a dry run and the permissions, whether forwarding of one queue may start, with every safeguard of the spec as pure, tested rules. A fake gateway exists.

## Why
Spec "Forwarding messages with a dynamic Shovel". This is the one place the app moves messages, so the rules are written and tested before any request exists.

## Scope
In: `ShovelGateway` (dryRun, start, status, remove; nothing else, in particular no get, consume, ack, purge, delete-queue), `ForwardingRequest` (source profile, source vhost and queue, destination queue, `SrcDeleteAfter`, fixed `AckMode.ON_CONFIRM`), `DryRunResult` (source counts, destination exists and type, plugins on, policymaker tag, existing shovel), `ForwardingPlanner` rules (blocked: destination missing, level Read-only, no policymaker, plugins off, existing shovel; warning: destination not quorum or stream; confirmation requires the typed source queue name), `ShovelAddress` type with redacting `toString` and no password field in anything that can be logged, TLS rule (profile TLS means `amqps`, no fallback), `FakeShovelGateway` in `core-testing` that records every request so tests can assert "no get, no consume".
Out: HTTP implementation (`M8-08`), UI (`M8-09`).

## Acceptance
- BDD (`17_forward_messages_with_shovel.feature`): "A dry run shows what forwarding would do and sends nothing", "Forwarding is blocked when the destination queue does not exist", "A destination that is not a quorum queue gets a warning", "The confirmation names both queues and the consequences", "Cancelling the confirmation sends nothing", "Starting creates one confirmed dynamic shovel on the 4.x broker", "The app itself never reads or settles queue messages while forwarding", "Removing the shovel never deletes the source queue", "Removing a shovel needs Full control", "A user without the policymaker tag cannot forward", "A broker without the shovel plugins cannot forward", "Forwarding is blocked when the level is Read-only", "An existing shovel is never overwritten", "One queue at a time", "A TLS profile never falls back to plain for the shovel", "A plain profile says it is not encrypted".
- Unit: a structural test that `ShovelGateway` exposes only the four operations; redaction test (`toString`, string templates, data-class `copy`).

## Design notes
Interfaces in `core`, no RabbitMQ types. Strategy for the rules (each safeguard a small rule). Names: `caerbannog-migrate-<queue>` deterministic. Uses effective permission from the spec (Declare and edit to start, Full control to remove).

## UX
`M8-09`.

## MCP
None here. Decision (spec MCP evaluation): shovel create and remove are NOT exposed (moves data, typed confirmation, credentials); status read-only in `M8-10`.

## Open points
P1, P4, P5, P7 and P9 decided (2026-11-09); field and behaviour details from S-F.

## Spike findings (M8-00, 2026-11-09)
Measured (S-F, S-J): the destination check is REQUIRED (a missing destination is created by the Shovel as a durable classic queue; block, and warn when the destination is not quorum or stream); the PUT answers 201 without validation, so `start` returns `Started` only as "submitted" and the state machine continues in `status` polling: `Running`, `Finished` (parameter gone after `queue-length`), `Failed(reason)` for `terminated` (then remove the Shovel); status reading needs the monitoring or administrator tag and creating needs policymaker or administrator (planner checks both from the whoami tags and says which is missing); the confirmation text carries the strengthened disclosure (the source password is stored in clear on the 4.x broker and any administrator of it can read it until the Shovel finishes or is removed; P14 decided 2026-11-09: the profile user is the default and the dialog offers another user for this Shovel; `ForwardingRequest` carries an optional `ShovelSourceCredentials` (typed per run, redacting `toString`, memory only, never saved)); `ShovelGateway` has no method that reads the parameter endpoints. Progress is `forwarded` and `remaining`; completion is judged by the Shovel, not by the lagging queue counter.

## Definition of done
Tests first and passing, the safety wording of the spec is quoted in the KDoc of `ShovelGateway`, spec updated.
