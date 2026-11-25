# M10-06: Clients tab: connections, channels and consumers by user

Milestone: M10    Depends on: M10-02, M10-00 (S-D to S-H, S-N, S-O)    Size: L (cut into 06a data and redaction, 06b screen if it passes three days)

## Goal
The Clients tab lists connections grouped by user with counts, and for a chosen queue shows its consumers with channel, connection and user and, as far as the broker tells, who publishes to it; every value passes the redaction rules; the lists page, filter and refresh only while shown.

## Why
Spec "Access view (M10)", goal 4 and "Redaction rules"; the owner's wish to see who produces and consumes. Connection and channel records carry client-supplied properties: this is where a secret can leak.

## Scope
In:
1. `AccessGateway` calls: connections, channels, consumers (GET only), with `columns` and paging as S-O measured (pages of at most 500, total count shown), through `SecretGuard`: allow-list fields, shape scan, held-password scan, counter of hidden values (`M10-02`).
2. Model `ClientConnection(user, name, vhost, peer, protocol, state, channels, tls, product, version, connectionName)`, `QueueConsumer(queue, consumerTag, channel, connection, user, active, prefetch)`, and the publisher side by what S-G showed: `PublishesTo(user, exchange, routesTo)` when the broker tells, `PublishersUnknown` otherwise (wording AQ10); never a publisher guessed from names.
3. Visibility by tag (S-H): `monitoring` and administrator see the broker; `management` sees own connections only with the fixed notice of S3b; no tag gets the message of `M10-02`.
4. Filter by user and by queue (client side over the loaded pages); group by user; empty state; settling not applicable here.
5. Refresh with the shared driver (D3), only while shown, one load at a time; stale on a lost connection (D2).
6. Large broker: 2,300 connections read in pages of at most 500 without freezing the screen (virtual time in the test; a lazy list on screen).
Out: stream rows and MQTT (`M10-07`), closing a connection (non-goal), reading queue messages (never).

## Acceptance
- BDD (`24`, tag `@m10-06`): "Connections are grouped by user with counts" (@smoke), "The consumers of a queue show connection, channel and user", "The publishers of a queue are shown through the exchange they publish to" and "When the broker does not tell who publishes, the view says so" (both `@needs-spike`; one stays), "A user with the management tag sees only their own connections and is told so", "A user with the monitoring tag sees the connections of the whole broker", "A connection of a token user shows the user name the broker reports", "Filter by user and by queue", "A broker with thousands of connections is read in pages", "An empty broker shows an empty state", "The lists refresh while the tab is shown and only then", and the security scenarios "Only allow-listed client properties are shown", "A value that looks like a secret is replaced before it is shown" (outline), "The held password and its parts are never shown", "The app asks the broker only for the columns it shows", "No control closes a connection or reads a queue message".
- Unit: parser tests on the S-D to S-H and S-N fixtures; guard tests with the hostile fixtures; view model states.

## Design notes
`core/access`, `data-rabbit`, `ui/access`. Redaction at the gateway boundary. The fake in `core-testing` generates hostile values on demand (JWT-shaped names, URIs with credentials). No `!!` on network data. The app never calls a consumer or message endpoint (`/api/queues/.../get`): the only queue-message read path stays `ManagementQueueMessageInspector`.

## UX
Clients tab of `Access.dc.html`.

## MCP
`caerbannog_list_clients` in `M10-08`, same rows, same guard, bounded.

## Open points
AQ8 (client properties), AQ9 (visibility of other users), AQ10 (publisher wording). S-G decides what "producers of a queue" can honestly mean.

## Definition of done
Tests first and passing; a test that the log of a run with hostile values holds none of them; README and tags updated.
