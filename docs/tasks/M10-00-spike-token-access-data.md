# M10-00: Spike on a live 4.x broker with the OAuth 2 backend: what the management API shows for token users

Milestone: M10    Depends on: none (owner answers AQ4 and AQ11 improve it but do not block it)    Size: M

PLANNED 2026-11-25. Runs in parallel with `M10-01` (mockup). Blocks the data parts of `M10-02` to `M10-07` as marked there.

## Goal
Every item S-A to S-P below is measured on a real RabbitMQ 4.x broker with the OAuth 2 backend, and written into `docs/tasks/M10-00-spike-results.md` with sanitised fixtures, so that the Access view is built on facts: what whoami, vhosts, permissions, connections, channels, consumers and the stream endpoints return for internal users and for token users, what the connection user name of a token user is, how permission patterns really match, and whether any token text ever comes back.

## Why
`docs/spec.md` "Access view (M10)", "Facts to verify first". The owner's brokers use Keycloak. The spec says the view must not claim more than the management API shows; today nothing about token users is measured. The permission read is known to be administrator-only for internal users (M1-03); for token users it is unknown.

## Scope
In:
1. **Broker fixture** (`brokerSpikeTest`, class `RealBrokerAccessSpikeTest`: `broker` and `Spike` in the name, enforced by `BrokerTestSplitGuardTest`). Image from `BrokerImages` (4.4.1 pin). Plugins: `rabbitmq_management`, `rabbitmq_stream`, `rabbitmq_stream_management`, `rabbitmq_mqtt`, `rabbitmq_auth_backend_oauth2` (through the copied `enabled_plugins` file, `RabbitContainers.brokerWithPlugins`). Config through a copied `rabbitmq.conf`: `auth_backends.1 = oauth2`, `auth_backends.2 = internal` (the internal backend gives an administrator and comparison users), `auth_oauth2.resource_server_id`, a static signing key (`auth_oauth2.default_key` and `auth_oauth2.signing_keys.<id>` pointing at a copied public key file), the algorithm. Key names are taken from the plugin's documentation and confirmed by the broker starting and accepting a token; if a key is wrong the spike records the correct one.
2. **Token issuer: a static signing key, no Keycloak, no JWKS server.** The test generates an RSA key pair with the JDK, copies the public key into the container, and mints JWTs itself with `java.security.Signature` and `Base64.getUrlEncoder` (about 30 lines of test code, no new dependency; if a JWT library is wanted, it needs a reason in the commit message and a licence check). Claims are free, so every claim shape can be tested (`sub`, `client_id`, `preferred_username`, `scope` as string and as list, `aud`, `exp`). Why not Keycloak: a container of about 500 MB with a start of a minute or more, realm import to maintain, and it adds nothing the broker can see beyond the claims; the stub models the claims exactly.
3. **Personas** (internal users made through the management API with an administrator; token users by scopes). Same rights for the pairs so the results can be compared:
   - `I-admin` / `T-admin`: tag administrator, configure, write, read on all.
   - `I-mon` / `T-mon`: tag monitoring, read on all.
   - `I-mgmt` / `T-mgmt`: tag management, rights on one virtual host.
   - `I-none` / `T-none`: no tag, rights on one virtual host.
   - `T-narrow`: read on `queue.*` only, write on `ex.*` only, in virtual host `/orders` (scope syntax from the plugin docs; the spike records the form that works, including the leading-slash vhost).
   - `T-topic`: write on exchange `amq.topic` with a routing key pattern (topic scope).
   - `T-multi`: scopes for two virtual hosts.
   - `T-expired`, `T-badsig`, `T-noaud`: refusals.
4. **Clients** (test code only, as fixtures; the rule that the app's own process never reads queue messages concerns product code, and these clients run on a throwaway container): an AMQP 0.9.1 connection (`amqp-client`, test scope since `M8-13a`) with a consumer on a queue and a publisher to an exchange; a stream-client publisher and consumer; an MQTT client (Paho) with a subscription. Each for a token persona and an internal persona. The token is passed as the password; the user name field is varied (empty, arbitrary, the `sub` value) to see what the broker accepts. Never copy this code into product modules.
5. **The measurements**, all with GET only on the management API (plus the clients above); each answer records status, the fields present and the persona. Raw bodies are saved as fixtures under `data-rabbit/src/test/resources/fixtures/m10-00/` with every token, password and signing key replaced by `<JWT>`, `<PASSWORD>`, `<KEY>` before saving (a guard test fails if a fixture contains `eyJ` or the test's password).

| Id | Question |
| --- | --- |
| S-A | `GET /api/whoami` and `GET /api/vhosts` for every persona: status, fields, vhosts listed (only those with permission? all for monitoring?). Basic auth with the token as password (user name varied) and, with `management.oauth_enabled` on, `Authorization: Bearer`. Which of the two works, and does it need the plugin's management settings |
| S-B | Permission endpoints for token users: `GET /api/permissions`, `/api/permissions/{vhost}/{user}`, `/api/users`, `/api/users/{user}/permissions`, `/api/topic-permissions`, `/api/topic-permissions/{vhost}/{user}`, called as `I-admin` and as `T-admin` (an administrator by token), for the internal persona and for the token persona's user name. Does anything return what RabbitMQ resolved from the token? Status codes and bodies. Also: what a non-administrator gets (known 401 for internal, check for token) |
| S-C | How to tell the OAuth 2 backend is in use, and with which tag: `GET /api/auth` (unauthenticated?), `GET /api/overview`, `GET /api/nodes` (`applications`, `auth_mechanisms`), `GET /api/extensions`, `GET /api/rabbitmq/plugins` if it exists. For each: which persona may call it, and whether it separates "the management UI uses OAuth" from "the broker's auth backend is OAuth 2" (they are different settings) |
| S-D | What `user` is on connections made with a token: AMQP, stream and MQTT, for claims `sub` only, `client_id`, `preferred_username` with and without `auth_oauth2.preferred_username_claims`. Also `auth_mechanism`, `ssl`, `client_properties`, `connection_name`, `user_who_performed_action`. **Security row:** search every response body of every endpoint called in this spike for the full token and for its payload segment; record "found in ..." or "not found". Also whether a client-set `connection_name` that looks like a JWT comes back verbatim (it will; it is the input to the redaction fixtures) |
| S-E | `GET /api/channels` and `/api/connections/{name}/channels`: `user`, `connection_details`, `consumer_count`, `message_stats`, `publishing` details; for token and internal personas |
| S-F | `GET /api/consumers[/{vhost}]`: `channel_details` (`user`, `connection_name`, `peer_host`), `queue`, `consumer_tag`, `ack_required`, `prefetch_count`, `active`; the stream consumers of `/api/stream/consumers` for a token user (`connection_details.user`) |
| S-G | Who publishes to a classic or quorum queue: is there any publisher field for a queue; `GET /api/exchanges/{vhost}/{name}` `incoming` (needs `rates_mode` or detailed statistics? default off); `message_stats.publish_details` on channels; `/api/stream/publishers` for a token user. Conclusion: can the app say "user U publishes to exchange X" at all, and by which setting |
| S-H | Visibility by tag on the AMQP endpoints (`connections`, `channels`, `consumers`) for `I-mon`, `I-mgmt`, `I-none` and the token twins: whole broker, own only, refused. Compare with the stream endpoints (S3) |
| S-I | Pattern matching, by trying operations as a throwaway user with the pattern under test and recording allowed or refused (publish to an exchange for write, `basic.get` or consume on a queue for read, declare for configure; the messages are the spike's own on a throwaway broker): anchored or not (`ex` against `ex.orders`, `my-ex`), `^$`, the empty pattern, `.*`, a pattern with an invalid regex (can it even be set), case, the default exchange (is its permission name `amq.default` or empty), publish to a queue through the default exchange, `queue.bind` (write on queue, read on exchange), stream publish and consume permissions (write or read on the stream), topic permissions on `amq.topic` and the routing key pattern, MQTT topic to routing key translation (`/` to `.`) against a topic permission. Result is a case table of at least 30 rows `(pattern, name, operation, allowed)` that becomes the differential fixture of `M10-04` and the gate |
| S-J | Scope to permission, behaviourally: for `T-narrow`, `T-topic`, `T-multi`, scopes with wildcards, with `extra_scopes_source` and `scope_aliases` if time allows, which operations are allowed and refused. Does the effect match the documented scope syntax. This is observed from the broker, not read from any API |
| S-K | **Optional, only if AQ11 says yes or S-D shows a gap:** the same S-D and S-J with a Keycloak container (`quay.io/keycloak/keycloak`, pinned tag, realm imported from a file, a client with a `rabbitmq.*` client scope). Records the claims Keycloak puts in a token and the user name RabbitMQ derives |
| S-L | Token expiry: an expired token (S-A to S-F: the status code and body, does the body echo the token); a connection that outlives its token (not required to measure, record "not measured" if skipped) |
| S-M | Error bodies: for every refusal above, is any token or password text in the body or the reason |
| S-N | `GET /api/connections` with a connection name set to a JWT-shaped value, a `amqps://u:p@h` value and `password=x` in a client property: raw bodies for the redaction fixtures of `M10-02` |
| S-O | The `columns` parameter on `/api/connections`, `/api/channels`, `/api/consumers`; `page`, `page_size` (500 max?), `sort`, `name` filter. Is `client_properties` left out when not asked for. How many connections (use 600 cheap AMQP connections) a page of 500 takes |
| S-P | Does anything above change with a TLS listener on the management port (one fixture run with TLS, since the product default is TLS) |

Out: any product code, any Keycloak configuration beyond S-K, 3.x, clusters, any call that is not GET on the management API, any write that is not on the throwaway broker. The spike does not change the app.

## Acceptance
- `RealBrokerAccessSpikeTest` runs under `./gradlew :data-rabbit:brokerSpikeTest --tests '*AccessSpike*'` and records one test per item (S-K only when asked); a failing assertion means "the claim does not hold", never a silent skip.
- Results file `docs/tasks/M10-00-spike-results.md`: one section per item, with the status, the fields, the fixture path, and a plain verdict ("measured on RabbitMQ 4.4.1, one node, one version").
- The decisive verdicts are written at the top: (1) does any endpoint show resolved permissions for a token user (outcome A or B of the spec), (2) what is the connection user of a token user, (3) how the OAuth 2 backend is detected, (4) whether a token text appears in any response, (5) whether a queue's publishers are available, (6) the regular-expression rules.
- A guard test fails if a fixture holds a token, a password or a key.
- No BDD scenario is run by this task; the scenarios tagged `@needs-spike` in `22`, `23`, `24`, `25` are updated by the architect after the results (the tag goes, the steps are adjusted).

## Design notes
Module `data-rabbit` (tests and fixtures only). Container helper `RabbitContainers.brokerWithPlugins`; image pin `BrokerImages`; Testcontainers 2.0.5. No sleeps: poll on values, bounded, with a message (statistics lag is about 5 s, spec "Statistics lag"). Clean up every container and client. The signing key and tokens exist only in the test JVM and the container.

## UX
None.

## MCP
None (spike). The MCP decision for the milestone is in `docs/spec.md` "Access view (M10)", "MCP evaluation".

## Open points
AQ4 (do real Keycloak users put the JWT in the password field; sample claim names) and AQ11 (run S-K or not). Meanwhile the stub covers `sub`, `client_id`, `preferred_username`. Not measured on purpose: clusters, other 4.x versions, 3.x, Windows and Podman.

## Definition of done
Tests written first (each item as a test that fails until the fixture works), results file and fixtures committed, the spec section "Measured facts of M10-00" written by the architect from the results (the open items of "Access view (M10)" moved out), no product code touched.

## Status
- Spike (senior developer, 2026-11-25, branch `M10-00-spike-token-access-data`): DONE, ready for review. `RealBrokerAccessSpikeTest` (12 tests, tags `broker` and `spike`) passes 12 of 12 on RabbitMQ 4.4.1 / Erlang 29.1.2 (Docker Desktop, Apple silicon), about 195 s, run with `./gradlew :data-rabbit:brokerSpikeTest --tests '*AccessSpike*'`. S-A to S-J and S-L to S-P measured; **S-K not run** (AQ11 open, working assumption skip). Results: `docs/tasks/M10-00-spike-results.md`; 80 sanitised fixtures in `data-rabbit/src/test/resources/fixtures/m10-00/` (guard `AccessFixtureGuardTest`, fast); spec subsection "Measured facts of M10-00" added by the spike author from the results (the architect still moves the decisions out of "Facts to verify first" and "Known facts"). No product code touched, no new dependency. Decisive findings: outcome B (no endpoint resolves a token user's permissions); the connection user is the first present claim of `preferred_username_claims` (`sub` then `client_id` by default, `unknown` when none), and `whoami` names the same user; the OAuth 2 backend cannot be detected, a token user can (`whoami.is_internal_user: false`); no token text in any response, but client-set names come back verbatim also in `user_provided_name`; queue publishers are not available, exchange publishers only with `rates_mode detailed`; patterns are unanchored, case-sensitive, the default exchange is `amq.default`. Not measured: S-K, `scope_aliases`, `extra_scopes_source`, token refresh, TLS client connections to a token broker, clusters, other versions, Podman. Next: architect review, then the `@needs-spike` scenarios of `22` to `25`.
