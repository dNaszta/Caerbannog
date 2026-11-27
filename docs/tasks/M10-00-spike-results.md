# M10-00 spike results: what the management API shows for token users, measured

Measured on 2026-11-25 on throwaway Testcontainers brokers, Docker Desktop on an Apple-silicon Mac (host `arm64`; that the image is arm64 is presumed, not recorded): `rabbitmq:4.4.1-management` (`BrokerImages.CURRENT_4X`) = **RabbitMQ 4.4.1**, Erlang **29.1.2**, one node, one version. Plugins: `rabbitmq_management`, `rabbitmq_stream`, `rabbitmq_stream_management`, `rabbitmq_mqtt`, `rabbitmq_auth_backend_oauth2`. Clients in test scope only: `amqp-client` 5.37.0, `stream-client` 1.12.0, Paho MQTT 5 (1.2.5). No new dependency.

Living proof (tags `broker` and `spike`): `RealBrokerAccessSpikeTest`, 12 tests, run with `./gradlew :data-rabbit:brokerSpikeTest --tests '*AccessSpike*'` (about 3 minutes, four containers). Status at the end of the spike: **12 of 12 pass**. The measured facts are pinned as assertions (a changed answer fails its test and prints the new value). Raw answers (sanitised: `<JWT>`, `<PASSWORD>`, `<KEY>`): `data-rabbit/src/test/resources/fixtures/m10-00/` (written when `RECORD_M10_00_FIXTURES` is set; 80 files); the S-I case table is `fixtures/m10-00/s-i-pattern-rows.tsv`; every measured line is also written to `data-rabbit/build/m10-00-facts.txt`. `AccessFixtureGuardTest` (fast, no container) fails if a fixture holds `eyJ`, the spike's password or a key.

Setup, so a reader can reproduce it. Token issuer: a static RSA key pair made by the JDK; the public key is copied into the container; tokens are minted by the test with `java.security.Signature` (no Keycloak, no JWKS, no JWT library). Config lines used (`rabbitmq.conf`, copied to `conf.d`): `auth_backends.1 = oauth2`, `auth_backends.2 = internal`, `auth_oauth2.resource_server_id = rabbitmq`, `auth_oauth2.default_key = spikekey`, `auth_oauth2.signing_keys.spikekey = <public key file>`, `auth_oauth2.algorithms.1 = RS256`; the broker started and accepted tokens with exactly these keys, so no key had to be corrected. Four brokers: **A** = base; **B** = A + `management.rates_mode = detailed` + `auth_oauth2.preferred_username_claims.1 = preferred_username`; **C** = A + management over TLS on 15671 (S-P); **D** = A + `management.oauth_enabled = true`, `management.oauth_client_id`, `management.oauth_provider_url` (a dummy address) and the default of the basic-auth setting (S-A). Personas: internal `i-admin`, `i-mon`, `i-mgmt`, `i-none` (made through the management API by `guest`); token `t-admin`, `t-mon`, `t-mgmt`, `t-none`, `t-narrow`, `t-topic`, `t-topic-plus`, `t-multi` (user = the `sub` claim, rights from scopes); refusals `t-expired`, `t-badsig`, `t-noaud`, `t-wrongaud`, `t-unknown-kid`, `t-no-kid`, `t-garbage`. A token is the password of the client; the user name field was varied (the `sub`, empty, arbitrary). "Not measured" is written where it was not.

## Read first: the decisive verdicts

1. **No endpoint shows what RabbitMQ resolved from a token: outcome B of the spec.** A token user is not in `/api/users`, `/api/permissions` or `/api/topic-permissions` (internal list: `guest` and the four `i-` users only); `GET /api/permissions/{vhost}/{user}` and `GET /api/users/{user}/permissions` for a token user answer **404**, also for an administrator by token (`t-admin`) and for an internal administrator. The administrator-only rule holds for tokens too: a `monitoring` or `management` token gets 401 on every permission endpoint (S-B). Two partial signals do exist: `GET /api/vhosts` for a token lists only the virtual hosts the token has some scope for (a `management` token with scopes on `/orders` sees `/orders` only; `administrator` and, for the internal twin, `monitoring` see all), and `GET /api/whoami` gives name and tags from the token (S-A).
2. **The connection user of a token user is the value of the first present claim of `auth_oauth2.preferred_username_claims`, and the user name the client sends is ignored.** With no setting (broker A) the claims are tried as `sub`, then `client_id` (`preferred_username` is NOT used: a token with all three shows `sub`). With `preferred_username_claims.1 = preferred_username` (broker B) only that claim counts, and a token without it shows the user **`unknown`**, so several token users can share one name. The same user is shown on AMQP, stream and MQTT connections, on channels, consumers, stream publishers and consumers, and it is what `GET /api/whoami` answers for the same token (`name`), measured on both brokers (S-D, S-A).
3. **Detecting the OAuth 2 backend: it cannot be read directly.** `GET /api/nodes` (administrator or monitoring, token or internal; 401 for management and untagged) lists `applications` with `oauth2_client` and `rabbitmq_auth_backend_oauth2` and `enabled_plugins`: that says the plugin is loaded, not that `oauth2` is in `auth_backends`, and not the order. `config_files` gives only paths. `GET /api/auth` and `GET /auth` answer 404 on A and on D (management OAuth on), so the 4.4.1 management API has no such route to ask; `/js/oidc-oauth/helper.js` is served anonymously in both (static, not a switch). The reliable signal for the signed-in user is **`GET /api/whoami` field `is_internal_user`: `false` for a token user, `true` for an internal user** (S-A, S-C).
4. **No token text, payload segment or password was found in any response body of the whole run** (every reply of every endpoint is scanned by the helper; leaks: none). Error bodies for refused tokens are the generic `{"error":"not_authorized","reason":"Not_Authorized"}` (S-L, S-M). The one way a token-shaped text comes back is the client's own input: a `connection_name` set to a JWT-shaped value, to a URI with credentials, and a client property `password` all come back verbatim, in `client_properties` AND in the field `user_provided_name` (two places to scrub, S-N).
5. **Who publishes to a queue: no.** A classic or quorum queue lists `consumer_details` (with `channel_details.user`) but its `incoming` names only exchanges, never a user. "User U publishes to exchange X" is available only with `management.rates_mode = detailed` (broker B): `GET /api/exchanges/{vhost}/{name}` then has `incoming[].channel_details.user`, and `GET /api/channels/{name}` has `publishes[]` with the exchange. With the default mode both are empty lists. Stream publishers always carry `connection_details.user` (S-G).
6. **The regular-expression rules (S-I, 137 rows):** a pattern is **not anchored** (`ex` allows `ex.orders` and `my-ex`); case-sensitive (`(?i)` works); `^$` and the empty pattern allow no named object; the default exchange is checked as **`amq.default`**, never as the target queue; a topic pattern behaves the same; with no topic permission nothing is limited; an invalid expression cannot be set (400 `invalid_regexp` over the API, exit 69 from `rabbitmqctl`). Details below.

Design consequences for M10 (for the architect): Outcome B for part 3 is confirmed, as the briefs assume. The Who am I tab can say "token user" from `is_internal_user` and show the user from `whoami` without any guess about claims. Clients grouped "by user" must warn that the name comes from a claim and may be `unknown` for several people. The allow-list must treat `user_provided_name` like `connection_name`. Queue publishers: only the indirect wording (AQ10) and only with `rates_mode detailed`; otherwise say it is not available. The preview rules of S-I are measured. Token expiry shows up as 401 with no distinguishing text, and as a broker-forced connection close.

## S-A whoami and vhosts: measured

`GET /api/whoami` answers `{name, tags, is_internal_user}` for every persona with a monitoring, management or administrator tag, and 401 for a persona without a tag (`i-none`, `t-none`, `t-multi`), token or internal (fixtures `s-a-whoami-*`, `s-a-vhosts-*`). `tags` is the list from the token's `rabbitmq.tag:` scope. Basic auth with the token as the password works with any user name: with `sub`, empty or `someone-else` the answer is the same and `name` is the token's user (the name sent is ignored). `Authorization: Bearer <token>` works as well, on broker A (no `management.oauth_enabled`) and on broker D (`management.oauth_enabled = true`). On D, basic auth for `guest` also works (nothing was set for `disable_basic_auth`, so this is the default of 4.4.1 as measured). Virtual hosts: `i-admin`, `t-admin` all three; `i-mon` (permission only on `/`) all three; `i-mgmt` and `t-mgmt` only `/orders`; on D `t-mgmt` sees none (D has no `/orders`). The `t-mon` token has read on every vhost, so it does not separate "all for monitoring" from "by scope". `GET /api/auth` answers 404 (no auth info route in 4.4.1), see S-C. Verdict: measured on RabbitMQ 4.4.1.

## S-B permission endpoints: measured

Callers `i-admin`, `t-admin`, `i-mon`, `t-mon`, `i-mgmt`, `t-mgmt` against targets `i-admin`, `t-admin`, `t-none`, `t-narrow` (fixtures `s-b-*`):

| Caller | `/api/permissions`, `/api/users`, `/api/topic-permissions` | `/api/permissions/{vhost}/{user}` and `/api/users/{user}/permissions` |
| --- | --- | --- |
| `i-admin` and `t-admin` | 200; the lists hold internal users only (`guest`, `i-*`), never a token user | internal target 200; a **token** target 404 (also for the token user's own name) |
| `i-mon`, `t-mon`, `i-mgmt`, `t-mgmt` | 401 | 401 |

`/api/topic-permissions/{vhost}/{user}` for a token user: 404 (administrator), 401 (others). Verdict: nothing returns what RabbitMQ resolved from the token; a token administrator can read the internal users' permissions but nothing about token users.

S-B follow-up (M10-03 step 2, live capture, 4.4.1): `GET /api/users` rows have `has_password`, `hashing_algorithm`, `limits`, `name`, `tags` (array) and no `password_hash`; topic permission rows are exactly `user, vhost, exchange, write, read`; unknown and token users answer 404 `{"error":"not_found","reason":"Not Found"}`; non-administrators get 401 `{"error":"not_authorised","reason":"Not administrator user"}` on both paths; a token administrator can read `/api/users` (internal users only); a user name with a space and a slash works as one encoded path segment; an empty pattern is `""`. Not measured on 4.0.9 or 3.13.

## S-C how the OAuth 2 backend is detected: measured

| Endpoint | Callers that may | What it shows |
| --- | --- | --- |
| `/api/overview` | all tagged personas (management gets fewer fields: no `listeners`, `contexts`, `node`) | no OAuth word |
| `/api/nodes` | administrator and monitoring (token or internal); 401 for management and untagged | `applications` incl. `oauth2_client`, `rabbitmq_auth_backend_oauth2`; `enabled_plugins`; `auth_mechanisms` (PLAIN, AMQPLAIN, ANONYMOUS enabled; RABBIT-CR-DEMO off); `config_files` paths. The first call on D right after start returned 6 keys, after a bounded wait 98 keys |
| `/api/extensions` | all tagged | 10 UI extension entries |
| `/api/auth`, `/auth`, `/api/rabbitmq/plugins`, `/api/plugins` | n/a | 404 on A and D |
| `/api/auth/attempts/{node}` | administrator, monitoring (404 for the node name tried); 401 management and untagged | not explored |

The management UI setting (`management.oauth_enabled`) is not visible in the API: the same `/api/nodes` words on A and D. The backend order (`auth_backends`) is not visible anywhere measured. So: "OAuth 2 plugin loaded" is knowable for an administrator or monitoring user; "backend in use" is not, but "this user came from a token" is, by `is_internal_user`. Fixtures `s-c-*`.

## S-D connection user of token users: measured

Claims minted: `sub` only; `client_id` only (no `sub`); `sub` and `client_id`; all three. Connected over AMQP, stream (`PLAIN`) and MQTT 5, each with the user name field empty and arbitrary: **all accepted**; the broker took the user from the token.

| Token claims | Broker A (default) | Broker B (`preferred_username_claims.1 = preferred_username`) |
| --- | --- | --- |
| `sub` only | the `sub` | `unknown` |
| `client_id` only | the `client_id` | `unknown` |
| `sub` and `client_id` | the `sub` | `unknown` |
| `sub`, `client_id`, `preferred_username` | the `sub` | the `preferred_username` |

`GET /api/whoami` of the same token answers the same name in all eight cases. In `GET /api/connections`: `user` and `user_who_performed_action` both equal that user; `auth_mechanism` is `PLAIN` for AMQP and stream and **`none` for MQTT** (also with credentials); `ssl` false (plain listeners); `type` is present for AMQP only; `client_properties` holds `connection_name` for AMQP and stream (`rabbitmq-stream-locator-0`, `rabbitmq-stream-producer-0`), none for MQTT. Stream connections also appear in `/api/connections` (`protocol: stream`) next to `/api/stream/connections` (fields: `auth_mechanism`, `client_properties`, `user`, `user_provided_name`, `user_who_performed_action`, `vhost`, TLS fields). Fixtures `s-d-connections-A-sub-and-client-id.json`, `s-d-stream-connections-A.json`. Security row: see S-M.

## S-E channels: measured

`GET /api/channels` (fixture `s-e-channels-A.json`): per channel `user`, `user_who_performed_action`, `vhost`, `connection_details` = only `name`, `peer_host`, `peer_port` (no `connection_name`), `consumer_count`, `prefetch_count`, `state`, `messages_unacknowledged`, `message_stats` (a consumer channel: `ack`, `deliver*`, `get*`, `redeliver`; a publishing channel: `confirm`, `publish`, `drop_unroutable`, `return_unroutable`; a channel with no activity yet: absent). Token and internal users look the same. `GET /api/connections/{name}/channels` (name url-encoded) returns the same shape, 2 channels for the connection of the test. The `publishes` list is present only in the detail of `GET /api/channels/{name}` (S-G). Fixtures `s-e-*`.

## S-F consumers: measured

`GET /api/consumers`, `/api/consumers/{vhost}` (fixtures `s-f-*`): `ack_required`, `active`, `activity_status`, `arguments`, `consumer_tag`, `consumer_timeout`, `exclusive`, `prefetch_count`, `queue` (an object with name and vhost) and `channel_details` with `user`, `connection_name` (the `ip:port -> ip:port` name, not the client's), `name`, `node`, `number`, `peer_host`, `peer_port`. A **stream consumer also appears here** (`consumer_tag` `stream.subid-0`, `queue` the stream, `channel_details.connection_name` ending in the stream port). `/api/stream/consumers` has `connection_details.user`, `offset`, `offset_lag`, `consumed`, `credits`, `subscription_id`, `queue`, `properties`. For token users the user is as in S-D (the `unknown` bucket on B). Verdict: the user of every consumer is available for AMQP and stream.

## S-G who publishes: measured

- Stream: `/api/stream/publishers` has `connection_details.user`, `published`, `confirmed`, `errored`, `publisher_id`, `queue`, `reference`, for token and internal users.
- Queue: `GET /api/queues/{vhost}/{name}` has `consumer_details` (with `channel_details.user`) and `incoming`: empty on A; on B (detailed) `incoming` lists `{exchange: {name, vhost}, stats: {publish, publish_details}}` (the exchange, not a user). No publisher field.
- Exchange: `GET /api/exchanges/{vhost}/{name}`: `message_stats` (`publish_in`, `publish_out`) in both modes; `incoming` is `[]` on A and, on B, a list with `channel_details` (`user`, `connection_name`, `name`, `peer_host`) and `stats`.
- Channel: `GET /api/channels/{name}` has `publishes`: `[]` on A; on B `[{exchange, stats: {publish, confirm, drop_unroutable, ...}}]`.

Conclusion: "user U publishes to exchange X" can be said only with `management.rates_mode = detailed` (a broker setting the app cannot change, and not the default), through the exchange or the channel detail; "user U publishes to queue Q" cannot be said at all, only "to exchange X which routes to Q" using bindings. Fixtures `s-g-*`.

## S-H visibility by tag: measured

Same on AMQP and stream endpoints. On A (fixture `s-h-connections-*`): `monitoring` (`i-mon`, `t-mon`) sees the whole broker (all 15 connections, 14 channels, 10 consumers, all stream rows); `management` (`i-mgmt`, `t-mgmt`) sees **only the connections, channels and consumers of its own user** (1 connection, 2 channels, 1 consumer; stream endpoints 0 because that user had none); an administrator sees everything; a user without a tag gets **401** on all of them. Queues and exchanges listed follow the virtual hosts of the permissions (management 5 queues in `/orders`; monitoring with permission only on `/` 10, the `t-mon` token with `*` 15). On B all token users are `unknown`, so `t-mgmt` saw 7 connections, 3 stream connections and 1 stream publisher: **"own" means "the same user name", and shared names share visibility.**

## S-I pattern matching: measured (137 rows)

The table is `fixtures/m10-00/s-i-pattern-rows.tsv` (`permission, pattern, name, operation, outcome`). Throwaway internal user, one permission set to the pattern under test and the other two to `.*`, a fresh connection per row. Rules the rows pin (assertions in the test):

| Rule | Rows (examples) |
| --- | --- |
| Not anchored, a regular-expression search | write `ex`: `ex`, `ex.orders`, `my-ex` allowed; `^ex$`: only `ex`; `ex.*` and `.*ex` allow all three |
| Case-sensitive; `(?i)` switches it off | `ex` refuses `EX`; `EX` allows only `EX`; `(?i)ex` allows all four |
| `^$` and the empty pattern allow no named object | refused for every name, for write, read, configure and topic |
| `.*` allows all | all rows allowed |
| Operations: write = `basic.publish` to an exchange; read = `basic.get` and `basic.consume` on a queue; configure = `queue.declare` and `exchange.declare` | rows per operation |
| Default exchange: checked as `amq.default` | `amq\.default`, `^amq\.default$`, `.*` allow; `^$`, empty, `queue`, `^queue$` refuse (the target queue name is not checked) |
| `queue.bind` needs write on the queue AND read on the exchange | `write=queue read=ex` allowed; either one missing refused |
| Topic permission (`amq.topic`): a pattern on the routing key, same search rule | `^a\..*` allows `a.b`, `a.b.c`, refuses `b.c`, `xa.b`; `a` and `a.*` also allow `xa.b`; `^a\.b$` only `a.b`; empty refuses all; **no topic permission: nothing limited** |
| MQTT topic is checked as the routing key | with `^a\..*`: publish to `a/b` and `a/b/c` allowed, `b/c` refused (the refusal arrives as a lost connection, Paho code 32109, not as a PUBACK reason code); `a.b` as a topic is also allowed (the broker's translation of a dot was not measured here) |
| Streams: write = declare publisher, read = subscribe, configure = create, same search rule | `st` allows `st.perm`; empty refuses; refusals are `StreamException` code 16 `ACCESS_REFUSED` |
| An invalid expression cannot be set | `[` as write: `PUT /api/permissions` 400 `invalid_regexp`; `rabbitmqctl set_permissions` exit 69 (fixture `s-i-invalid-regex.json`) |

Not measured: Erlang-versus-Java regular-expression differences beyond `(?i)`, `^`, `$` and `.*` (character classes, lookahead, Unicode, named groups); the broker's translation of a `.` in an MQTT topic; read permission as a check on `queue.bind` by itself (only the combination was measured); permissions of a policy or a Shovel. The brief wants the differential fixture of `M10-04` built from these rows plus the ones the architect adds for the unmeasured differences.

## S-J scopes to permissions: measured (behaviour only)

- **A virtual host with a leading slash is written `%2F` in a scope**: for the vhost `/orders` the form `rabbitmq.read:%2Forders/*` grants access; `/orders/*`, `orders/*` and `\/orders/*` give none (the connection is refused). (The first spike test probed the four forms and the first that worked is used for the personas.)
- `*` as the vhost matches every vhost. Scopes can be a list or one space-separated string (both worked: `t-narrow` is the string form).
- `t-narrow` (read `queue.*`, write `ex.*` on `/orders`): `basic.get` on `queue.a` allowed, on `other` refused (403 `read access to queue 'other' ... refused for user 't-narrow'`); publish to `ex.a` allowed, to `other-ex` refused; declaring a queue refused (no configure scope); connecting to `/` refused. Same search (unanchored) rule as S-I is presumed, not tested separately for scopes.
- `t-multi` (two vhosts): both allowed, a third refused at the connection.
- `t-topic` (`rabbitmq.write:*/amq.topic/a.*` only): publish to `amq.topic` with `a.b` allowed, `b.c` refused (403 `write access to topic 'b.c' in exchange 'amq.topic'`); also over MQTT (`a/b` allowed, `b/c` refused). **A topic scope also gives write on the exchange itself** (`t-topic-plus`, with the extra exchange scope, behaved the same).
- A token without a tag can connect and work on its vhosts but cannot use the management API (401), as an internal user.
- Not measured: `scope_aliases`, `extra_scopes_source`, several tags in one token, scopes with `rabbitmq.` prefix variants other than `rabbitmq`.

## S-K Keycloak variant: not run

Owner answer AQ11 is open; working assumption: skip. Nothing was measured with Keycloak: its real claim names and whether its users put the JWT in the password field (AQ4) stay open. The AQ4 claim sample is not yet provided; the stub modelled `sub`, `client_id` and `preferred_username`.

## S-L token expiry: measured

For `t-expired`, `t-badsig`, `t-noaud`, `t-wrongaud`, `t-unknown-kid` and `t-garbage` (not a JWT): management API 401 with the body `{"error":"not_authorized","reason":"Not_Authorized"}` for basic and bearer alike, the same as a wrong password, so the app cannot tell "expired" from "wrong" by the answer; AMQP `ACCESS_REFUSED - Login was refused using authentication mechanism PLAIN`; stream `AuthenticationFailureException` code 8; MQTT code 134 `Bad User Name or Password`. A token without a `kid` header is accepted when `default_key` is set. A connection that outlives its token: a token with 12 seconds of life, an AMQP connection made with it; **the broker closed the connection about 10 seconds after the wait began with `CONNECTION_FORCED - credential expired` (reply code 320)**. Not measured: token refresh on a live connection (`update-secret`), the clock skew allowed, an MQTT or stream connection outliving its token. Fixtures `s-l-*`.

## S-M error bodies: measured

Every refusal above was scanned: no token, payload or password text in any body or reason. The stream and AMQP client errors say only the refusal reason. Verdict: **none found** (the helper scans every response body of the run, and the last test asserts the list of leaks is empty). Not covered: the broker's own log, and responses that the helper did not request.

## S-N connection names that look like secrets: measured

On broker A, three AMQP connections by `guest`: a `connection_name` that is a JWT-shaped text (a made-up one, not a minted token), `amqps://spike-user:spike-pw@broker.example:5671/vhost`, and a connection with the client properties `password=x` and `note=password=x`. `GET /api/connections` returns **all three verbatim**: `client_properties.connection_name`, the extra client properties (`password`, `note`) and the field `user_provided_name`. `GET /api/channels` does not echo the name (`connection_details.name` is the `ip:port` text). So the allow-list (`product`, `version`, `platform`, `connection_name`) is the right shape, `user_provided_name` needs the same treatment, and the shape scan must catch the JWT and the URI. Fixtures `s-n-*` (the JWT-shaped value is shown as `<JWT>`, the shape is `eyJ` + two dot-separated base64url parts).

## S-O columns, paging, sorting: measured

- `columns=a,b` returns objects with only those keys, on `/api/connections` (`name,user` gives `{name,user}`; `name,user,client_properties` gives the three), `/api/channels` and `/api/consumers`. Without `columns` the full object, including all `client_properties`, comes back. So `columns` keeps client properties from leaving the broker (rule 4 of the redaction rules).
- `page` and `page_size` on `/api/connections` and `/api/channels` return `{filtered_count, item_count, items, page, page_count, page_size, total_count}`. **`/api/consumers` ignores the paging parameters and returns the plain array.** `page_size=1000` answers **400**; 500 works.
- 600 cheap AMQP connections (opened in about 2.3 s; the list reached 600 after the statistics lag within the bounded wait): `page_size=500` returned 500 items, `page_count` 2; the full objects were about 689 KB and took 42 to 64 ms; with `columns=name,user,vhost,protocol` 50 KB, 15 to 21 ms; the unpaged call returned all 600. `sort=name&sort_reverse=true` worked. The `name` filter matches the `ip:port -> ip:port` name (a prefix of a real name found 1 item), NOT the client's connection name (`name=bulk-1` found 0, `use_regex=true` with `^bulk-1$` found 0). Fixtures `s-o-*`.

## S-P management over TLS: measured

Broker C, HTTPS on 15671 with the repo's throwaway certificate (trust-all in the test only): for five callers (`i-admin`, `t-admin`, `i-mon`, `t-mon`, `i-mgmt`) and eleven paths, status and the shape of the answer (object keys or array element keys) were **identical to the plain 15672 answers: 0 differences**. Bearer works over TLS. The AMQP connection in that run was plain, so `ssl` was false; the `ssl` fields for a TLS client connection were not measured here. Fixtures `s-p-*`.

## Side finding (not part of the brief)

RabbitMQ 4.4.1 refuses `queue.declare` of a queue that is neither durable nor exclusive: `INTERNAL_ERROR - Feature transient_nonexcl_queues is deprecated. By default, this feature is not permitted anymore.` (reply code 541). The spike's own declares are durable. Relevant to anything that declares queues on 4.4 with `durable=false`.

## What was not run

S-K (Keycloak, AQ11 open); `scope_aliases` and `extra_scopes_source`; token refresh; clusters; other 4.x versions and 3.x; Windows and Podman (Docker Desktop only); a TLS AMQP, stream or MQTT client against a token broker; `management.oauth_disable_basic_auth` set explicitly; the Erlang regular-expression differences beyond the basics. Everything is one node, RabbitMQ 4.4.1.
