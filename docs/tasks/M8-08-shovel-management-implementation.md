# Shovel forwarding: management API implementation and secret handling

Milestone: M8    Depends on: M8-07, M8-00 (S-F)    Size: M

## Goal
`data-rabbit` implements `ShovelGateway` through the management API with the credentials never leaving memory, proven against real 3.13 and 4.x brokers.

## Why
Spec safeguards 3, 4, 7, 8 and 10.

## Scope
In: `ManagementShovelGateway` (`PUT`/`DELETE /api/parameters/shovel/{vhost}/{name}`, `GET /api/shovels`, GET-only source probe for counts, plugin detection per S-F, `GET /api/whoami` for the tag), URI builder (percent-encoding, `amqps` with the profile TLS settings, credential-free local destination if S-F proves it), password read from `SecretStore` at PUT time only, error scrubber (password and `//user:...@` removed from any broker text), a log guard test (capture all log output of a full forwarding and assert the password and URI are absent), `@broker-3x` tests on both images.
Out: UI.

## Acceptance
- BDD: `17` / "The shovel's credentials are never shown or logged" (@security), "Starting creates one confirmed dynamic shovel...", "Progress is shown while the broker moves the messages", "Removing the shovel never deletes the source queue", "A TLS profile never falls back to plain for the shovel"; `16` / "Analysing a live 3.x broker sends only read requests" extended to the probe.
- Live: on 3.13 source and 4.x destination, 3 messages move into a quorum queue, on-confirm, source queue still exists afterwards, the shovel removes itself with `queue-length` (if S-F confirms), and the app log contains no secret.

## Design notes
Never call `GET /api/parameters/shovel`. HTTP request and response logging stays off for this client. Exception messages are built from typed errors, not from broker text. Passwords never in `SessionState` (D6 stays: this is the one additional, short-lived read, recorded in the spec).

## UX
None.

## MCP
None here.

## Open points
P1 decided 2026-11-09: the profile's user is reused, so its password is read from `SecretStore` at PUT time. P7 (Shovel on 4.x) and P8 decided 2026-11-09.

## Definition of done
Tests first and passing, live tests clean up shovels and queues, no secret in any output, spec updated with the measured field names.
