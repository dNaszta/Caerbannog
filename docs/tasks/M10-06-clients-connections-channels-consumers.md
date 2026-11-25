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
- BDD (`24`, tag `@m10-06`): "Connections are grouped by user with counts" (@smoke), "The consumers of a queue show connection, channel and user", "The publishers of a queue are shown through the exchange they publish to" (detailed rates) and "When the broker does not tell who publishes, the view says so" (default rates), both kept after the spike; new: "Users that share a name are warned about", "The user-provided connection name is scanned like the connection name", "The consumers of the whole broker are not paged by the broker", "A user with the management tag sees only their own connections and is told so", "A user with the monitoring tag sees the connections of the whole broker", "A connection of a token user shows the user name the broker reports", "Filter by user and by queue", "A broker with thousands of connections is read in pages", "An empty broker shows an empty state", "The lists refresh while the tab is shown and only then", and the security scenarios "Only allow-listed client properties are shown", "A value that looks like a secret is replaced before it is shown" (outline), "The held password and its parts are never shown", "The app asks the broker only for the columns it shows", "No control closes a connection or reads a queue message".
- Unit: parser tests on the S-D to S-H and S-N fixtures; guard tests with the hostile fixtures; view model states.

## Design notes
`core/access`, `data-rabbit`, `ui/access`. Redaction at the gateway boundary. The fake in `core-testing` generates hostile values on demand (JWT-shaped names, URIs with credentials). No `!!` on network data. The app never calls a consumer or message endpoint (`/api/queues/.../get`): the only queue-message read path stays `ManagementQueueMessageInspector`.

## UX
Clients tab of `Access.dc.html`.

## MCP
`caerbannog_list_clients` in `M10-08`, same rows, same guard, bounded.

## Open points
AQ8, AQ9 and AQ10 are decided (four allow-listed properties; other users visible; indirect publisher wording). No "token" pill and no Stop button (review of the mockup, Q8 and Q5). Measured by M10-00: queue publishers are NOT available (`incoming` of a queue lists exchanges only); "user U publishes to exchange X" only when the broker runs `management.rates_mode = detailed` (exchange `incoming[].channel_details.user`, channel `publishes[]`), otherwise "could not be told". Stream publishers always carry `connection_details.user`. The connection user of a token connection is the broker's claim-derived name (`sub`, then `client_id`; only `preferred_username` if the broker is set so); the name the client sends is ignored; with the setting a token without the claim is the user `unknown`, so "by user" must show a notice that names may be shared (scenario "Users that share a name are warned about", `24`). Visibility by tag is the same for AMQP and stream endpoints; `management` sees "own" by user NAME, so shared names share visibility. `columns` works on `/api/connections`, `/api/channels`, `/api/consumers` and keeps `client_properties` out; `page` and `page_size` (max 500, 1000 gives 400) work on connections and channels but `/api/consumers` ignores them (read once, bound on arrival and say when cut); the `name` filter matches `ip:port -> ip:port`, not the client's connection name, so the user filter is local. Channels have no client connection name (`connection_details` = name, host, port). `user_provided_name` is scanned like `connection_name`. Facts: `docs/tasks/M10-00-spike-results.md`, spec "Measured facts of M10-00".

## Definition of done
Tests first and passing; a test that the log of a run with hostile values holds none of them; README and tags updated.
