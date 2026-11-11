# Shovel forwarding: management API implementation and secret handling

Milestone: M8    Depends on: M8-07 (merged first), M8-00 (S-F, S-J)    Size: M (not split)

LEAN (owner, 2026-11-11): only what the workflow and the safety rules need; the goal is a 3.x queue forwarded to a 4.x queue for real, soon. No extra polish, no speculative hardening.

## Goal
`data-rabbit` implements the M8-07 `ShovelGateway` (four operations) through the management API of the held 4.x connection, with the source password never leaving memory and never in any text, proven against real 3.13 and 4.x brokers.

## Why
Spec "Forwarding messages with a dynamic Shovel" safeguards 3, 4, 7, 8 and 10; spike S-F and S-J.

## Scope
In (module `data-rabbit`, package `dev.caerbannog.datarabbit`, all `internal`):

### 1. `ManagementShovelGateway : ShovelGateway`
Built over the existing pieces: the held 4.x connection's `ManagementHttp` and `ManagementRequests` (destination), a lookup `profiles: (String) -> ConnectionProfile?` and the `SecretStore` (source). No new HTTP client, no change to `ManagementHttp` itself.

| Operation | Requests (all through `ShovelPathAllowList`, see 2) | Result mapping |
| --- | --- | --- |
| `dryRun` | 1 `GET /api/whoami` (tags); 2 `GET /api/shovels`; 3 `GET /api/queues/{dvhost}/{dqueue}`; 4 the source probe (below) | `userTags` from whoami; `/api/shovels` 200 = plugins on and `existingShovel` = the entry with the request's vhost and shovel name (or null); 404 = `shovelPluginsOn = false`; 401 (a policymaker without monitoring) = plugins assumed on, `existingShovel = null` (the planner blocks `CannotReadStatus` anyway); destination 404 = `Missing`, 200 = `Exists(type)` with `type` of the queue JSON: `quorum`, `stream`, `classic`, anything else `OTHER`; source counts `messages_ready` and `messages_unacknowledged` (null in JSON counts as 0). Any 401 or 403 on whoami or the destination = `DryRunOutcome.Failed("...")` with a fixed text; transport failure = `DestinationUnreachable`; the probe's failures = `SourceUnreachable(name)` or `SourceNotPermitted` |
| `start` | one `PUT /api/parameters/shovel/{vhost}/{name}` | 201 or 204 = `Submitted`; 401 or 403 = `NotPermitted`; 400 = `Failed` with a FIXED text (never the body); transport = `Unreachable` |
| `status` | `GET /api/shovels` | entry found = `Listed(ShovelStatus)`; not found = `Absent`; 401 = `NotPermitted`; 404 = `Failed("The shovel plugins are not enabled")`; transport = `Unreachable` |
| `remove` | one `DELETE /api/parameters/shovel/{vhost}/{name}` | 204 = `Removed`; 404 = `NotFound`; 401 or 403 = `NotPermitted`; transport = `Unreachable` |

- Path encoding: the vhost and the shovel name go through `ManagementRequests.encode` (`/` becomes `%2F`, a space `%20`); the shovel name is `request.shovel.name` (`caerbannog-migrate-<queue>`), so a queue name with a space, a slash or a percent sign is encoded in the path and stays literal in the name. One unit test with the name `a b/c%d`.
- PUT body (the measured shape of S-F): `{"value":{"src-protocol":"amqp091","src-uri":<built>,"src-queue":<source queue>,"dest-protocol":"amqp091","dest-uri":"amqp://","dest-queue":<destination queue>,"src-delete-after":"queue-length"|"never","ack-mode":"on-confirm"}}`. `ack-mode` comes only from `request.ackMode.wireName`; `dest-uri` is the constant `amqp://` (credential-free local destination, S-F).
- Status mapping: `state` `running` = RUNNING, `starting` = STARTING, `terminated` = TERMINATED, else OTHER; `reason` has its surrounding quotes removed (measured: the broker sends `"\"failed to connect to source\""`) and goes through the scrubber (2); `forwarded` a number or null; `remaining` `"unlimited"` = null, a number, else null.
- Source probe (safeguard 10): a one-shot `ManagementHttp` for the source profile (`profiles(request.source.connectionName)`), password from `SecretStore.retrieve(profileName)`, ONE `GET /api/queues/{vhost}/{queue}`, closed at once (precedent: the Check connection button). GET only; it sends nothing to the 3.x broker that changes anything. A missing password or profile = `SourceNotPermitted`. The destination TLS rules (D9a) are those of the held connection's client; the probe's client is built from the source profile like the live source (`ManagementRequests.forProfile`).

### 2. `ShovelPathAllowList` and the guard tests
A pure function like `ExecutorPathAllowList`: allowed are exactly `GET /api/whoami`, `GET /api/shovels`, `GET /api/queues/{v}/{q}`, `PUT` and `DELETE /api/parameters/shovel/{v}/{n}`. Everything else is refused before a request is made: in particular `GET /api/parameters/...` (it returns the source password in clear, S-F), `GET /api/definitions`, `/get`, `/contents`, `/purge`, any `DELETE /api/queues/...`. Every request of the gateway passes it. Tests: a table of allowed and forbidden requests (scenario "The app never reads the shovel parameters"), and the existing guard `only the migration executor calls ManagementHttp put or delete` (`ManagementHttpWriteTest`) is EXTENDED to name `ManagementShovelGateway` as the second allowed caller (nothing else).

### 3. Secrets
- The source URI `<scheme>://<user>:<password>@<host>:<port>/<vhost>` is built in a local `val` inside `start`, user, password and vhost percent-encoded (a user `a@b:c` and a password with `/`, `?`, `#`, `%`, a space and non-ASCII must survive; one unit test), the scheme from `ShovelAddress.scheme` (`amqps` with TLS; no fallback to `amqp`). The password is `request.source.credentials?.passwordForRequest()` or else `SecretStore.retrieve(<source profile name>)`; a missing password = `StartOutcome.Failed("The password of <connection> is not stored")` and NO request. It is not kept in a field, a log line, an exception or the returned outcome.
- `ShovelScrubber.scrub(text, secrets)`: removes every occurrence of each secret and the pattern `scheme://user[:pw]@` (regex `[A-Za-z][A-Za-z0-9+.-]*://[^@\\s/]*@` becomes `***@`) from any broker text before it becomes a reason. Used for the status `reason`. Texts of PUT and DELETE failures are fixed texts, never broker text.
- The source password is the one extra short-lived read of a stored password besides the holder (decision P1, recorded in the spec).
- Log guard test: a Ktor `MockEngine` double (the one `ManagementHttpWriteTest` uses) runs dry run, start, poll (terminated with a reason that contains the sentinel password) and remove with the sentinel password; the captured `java.util.logging` and System.out/err output, every returned outcome's `toString`, and every `StartOutcome`/`StatusOutcome` reason contain neither the sentinel nor `://user:`. HTTP logging stays off for this client.
- Scenario "The shovel's credentials are never shown or logged" and "The destination of the shovel is the local broker without credentials" are bound by this task's unit tests (the request body captured by the engine has `"dest-uri":"amqp://"` and the source password only inside `src-uri`). They are `@m8-08`, not run by Cucumber (the acceptance module cannot see `internal` classes); README says so.

### 4. TLS (carried-over item)
`amqps` TLS options are NOT measured and stay out of v1: the URI carries only the scheme, no query options (`cacertfile`, `verify`), so the 4.x broker verifies the 3.x listener with its own trust. If it cannot, the shovel goes `terminated`, the status reason shows it and the M8-07 cleanup removes the shovel: the user sees "the start failed" with the reason; there is no pre-check. The `@m8-07` scenario "A TLS profile never falls back to plain" is already met in core. Live TLS between containers is NOT built in this task (needs certificates on both containers); recorded as an open point. The live test runs with the source profile `tls = false` (plain `amqp`, the "Use TLS" switch off, D9a), and asserts that `amqps` is never used for a plain profile.

### 5. Carried-over unmeasured items, decided
- Parameter name length limit: measured in the live test (a queue name of 255 characters gives a shovel name of 272): if the PUT is accepted, nothing to do; if it is refused (400), `start` returns `Refused("The queue name is too long for a shovel name")` before any request, with the measured limit as a constant `MAX_SHOVEL_NAME_LENGTH` and one unit test. The developer records the measured result in the Status.
- Absent grace: the live test records how many polls after the PUT the shovel was absent before it was listed and asserts at most one (the M8-07 rule); if the measurement is two or more, the architect sets `Started.absentPolls < N` in `ForwardingProgress` (a one-constant change) and the unit test follows.
- Percent-encoding of the queue name inside the shovel name: see 1 (path) and the live test (a queue named `m8 08/%x`).
- `percentEncode` in core is `internal`: make it public in `core.forwarding` (one word) so the URI builder does not duplicate it, or use it through the `ShovelAddress` string; this is the only allowed change in `core`.

### 6. Live tests (tag `broker`, new class `RealBrokerM8ShovelTest`, helper `M8SpikeSupport`; two containers on one network as `RealBrokerM8SpikeShovelTest`)
Five tests, each cleans its shovels and queues:
1. Happy path: 3 messages in a 3.13 classic queue, a quorum queue on 4.x; `dryRun` gives counts 3, `Exists(QUORUM)`, plugins on, the user's tags; `ForwardingService.start`; bounded polling (no sleep without a limit) until `Finished`; the destination holds 3 messages (checked with the test's own management peek, not the gateway), the source queue still exists, no shovel remains, the `calls`-style record of the transport shows no `/get`, no `/purge` and no parameter GET from the gateway.
2. Wrong source password: `Submitted` (201), then `Failed(reason)` with `cleanedUp = true`, the parameter is gone, the sentinel password is nowhere in the reason.
3. Missing destination queue: `dryRun` gives `Missing`, `start` is `Blocked`, no shovel exists.
4. A user with `policymaker` but no `monitoring`: `prepare` blocks `CannotReadStatus` (the real 401 of S-F).
5. Measurements: the long shovel name, the absent grace, and the queue name with a space, a slash and a percent sign end to end.
Out: UI (M8-09), MCP status tool (M8-10), amqps live test.

## Acceptance
- BDD (bound as `data-rabbit` unit tests named after the scenarios, tag `@m8-08 @fake-broker`): "The shovel's credentials are never shown or logged", "The app never reads the shovel parameters", "The destination of the shovel is the local broker without credentials". The `@m8-07` scenarios stay green and unedited (they run against the fake; this task adds no step).
- Unit: allow-list table, path encoding, body shape, status mapping, scrubber, URI percent-encoding, log guard, extended put/delete caller guard.
- Live: the five tests above.

## Test plan limits
- Steps 2 and 3: `./gradlew :data-rabbit:test --tests '*Shovel*' --tests '*ManagementHttpWrite*'` (fast, no container); `:core:jvmTest --tests '*Forwarding*'` only if the `percentEncode` visibility changed.
- Live, once at step 4 and once after any fix: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8Shovel*'`. Not run: the other `brokerTest` classes, since `ManagementHttp` and the shared transport do not change; `RealBrokerM7GateTest` and `RealBrokerM8GateTest` are run only if a shared class changes (they are not expected to).
- Full fast suite once at the end of the task.

## Design notes
- `ManagementShovelGateway` has no knowledge of the planner, the service or the UI; it maps requests to outcomes. The decisions are in M8-07.
- Exception messages and outcome texts are fixed strings or scrubbed; never a URL, a body, a header or a password.
- Wiring into the composition root (`desktopApp`) is M8-09, which needs the held connection; M8-08 delivers a factory function `shovelGatewayFor(connection holder, profiles, secrets)` only if M8-09 asks.

## UX
None.

## MCP
None here. `ShovelGateway` is not given to `:mcp-server`; the read-only status tool is M8-10.

## Open points
- `amqps` between containers with real certificates and the `cacertfile`/`verify` options: not measured, out of v1 (see 4); the owner learns it from the confirmation (Not encrypted vs TLS) and from the failure reason.
- P1 decided 2026-11-09 (the profile's user is reused; its password is read from `SecretStore` at PUT time) and P14 (another user for this Shovel); P7 and P8 decided.

## Definition of done
Tests first and passing (unit and the five live tests), live tests clean up shovels and queues, no secret in any output, the measured field values (name length limit, absent grace) written into the Status and the spec, no change outside `data-rabbit` except the `percentEncode` visibility.

## Status
- Step 1 (architect, 2026-11-11): made exact against the M8-07 core (`5c1c6ad`). Size M kept. Owner questions: none.
- Step 2 (test developer, 2026-11-11, round 0): tests first on branch `m8-08-shovel-management` (base main `2fe39ad`). Stubs marked `M8-08 STUB`: `ShovelPathAllowList` (allows nothing), `ShovelScrubber` (returns the text), `ManagementShovelGateway` (every operation answers a failure; constructor `(http, requests, profiles, secrets, sourceHttp = one-shot client from the source profile)`, the last one is the seam the tests use for the source probe), `MAX_SHOVEL_NAME_LENGTH = 255` (to be measured). The one change in `core`: `percentEncode` made public.
  - Tests (data-rabbit `src/test`): `ShovelPathAllowListTest` (4), `ShovelScrubberTest` (3), `ManagementShovelGatewayTest` (28: dry run mapping and probe, start body and URI, path encoding, name length, status mapping, remove, every request on the allow-list), `ShovelSecretsTest` (4: log guard with a sentinel password over dry run, start, poll, remove, the service and java.util.logging, System.out/err; the three `@m8-08` scenarios as named tests), `ManagementHttpWriteTest` (+1: the shovel gateway is the second allowed caller, uses `ShovelPathAllowList.checked`, never POSTs, mentions no `/get`, `/contents`, `/purge`, `definitions`; `allowedCallers` extended), helpers `ShovelTestRig`. Live: `RealBrokerM8ShovelTest` (5 tests, compile only now; run at step 4): happy path, wrong source password, missing destination, policymaker without monitoring, measurements (the name limit must equal `MAX_SHOVEL_NAME_LENGTH`: accepted at the limit, 400 one above; the absent grace at most one poll with `never` and an empty source; a queue `m8 08/%x` end to end).
  - Red: `:data-rabbit:test --tests '*Shovel*' --tests '*ManagementHttpWrite*'` 50 tests, 35 red by assertion ("expected DryRunOutcome.Done but was Failed(M8-08 STUB)", "no PUT was sent", "... must be refused"). Green on arrival (15): the forbidden rows of the allow-list, scrubber no-op cases, the existing write-test cases, the "failed-start texts" test.
  - Open points: (1) the gateway needs a constructor seam for the source probe client; my stub fixes its shape, the developer may change it if the tests follow. (2) `status(ref)` has no password, so its reason is scrubbed by the URI pattern only (a bare password in broker text is not removable there); the log-guard reason therefore uses the `scheme://user:pw@` form. (3) `MAX_SHOVEL_NAME_LENGTH` 255 is a guess: the live measurement test decides. (4) whoami tags are read as a JSON array only (3.9+ and 4.x); a comma string is not tested. (5) The three `@m8-08` scenarios stay unbound in Cucumber (internal classes), as the brief says.
  - Not run: `brokerTest` (compile only), core tests, full suite.
- Step 3 (senior developer, 2026-11-11, round 0): built `ShovelPathAllowList` (shape test: whoami, shovels, queue GET, shovel parameter PUT and DELETE), `ShovelScrubber`, `ManagementShovelGateway` (dry run with a one-shot closed source probe, start with the URI built in a local `val`, status, remove; every request through `ShovelPathAllowList.checked`; PUT, DELETE and status failures give fixed texts), `percentEncode` made public (the only core change). Constructor shape of the stub kept. `MAX_SHOVEL_NAME_LENGTH` stays 255 (a guess until the live measurement). Run: `:data-rabbit:test --tests '*Shovel*' --tests '*ManagementHttpWrite*'` 50 tests, 49 green, 1 red; `:data-rabbit:compileTestKotlin` (live test compiles) green; `:core:jvmTest --tests '*Forwarding*'` green. Not run: brokerTest, full suite.
  - Test I think is wrong: `ManagementShovelGatewayTest` "start sends one PUT of the measured body..." line 133 asserts `put.contentType` startsWith `application/json`, but `ShovelRig.engine` records only `request.headers["Content-Type"]`, which a MockEngine request does not carry for a `contentType()` call; `M508aSupport` falls back to `request.body.contentType`. The gateway sends JSON through the same `ManagementHttp.put` (`sendJson`) as the executor. The rig needs `?: request.body.contentType?.toString()`.
  - Deviation from the brief: the scrubber regex is `scheme://[^@\s]*@` (no `/` exclusion), because the log-guard test has a password containing `/` echoed by the broker; `[^@\s/]*@` would leave part of it.
- Step 4 (test developer, 2026-11-11, build adabc50 plus test fixes): PASS. `:data-rabbit:test --tests '*Shovel*' --tests '*ManagementHttpWrite*'` and `:core:jvmTest --tests '*Forwarding*'` green. Test-side fixes: `ShovelRig` records the content type like `M508a` (body fallback); the live happy path waits until the 3.13 statistics show 3 ready messages (they lag); the odd-name live test published with the encoded name as routing key (now the raw name); the name-limit measurement became a search up to 4096. Live `RealBrokerM8ShovelTest`: 5 of 5 green (third run; the first two failed only for those test faults). MEASURED: (1) shovel name: accepted up to 4096 characters (search ceiling), so the broker has no limit near 255; `MAX_SHOVEL_NAME_LENGTH = 255` refuses names the broker accepts (a source queue name over 236 characters): FINDING, minor, the constant is a safe lower bound, the architect decides whether to raise it; (2) absent grace: 0 absent polls before the first Listed, so the M8-07 grace of one is not needed but harmless; (3) percent-encoding: a queue named `m8 08/%x` goes end to end (2 messages moved, encoded path, literal name). Scrubber regex was loosened by the developer to `scheme://[^@\s]*@`: accepted, it can only over-scrub (for example `http://h/p?a=b@c`), which is the safe side; the brief text with `/` excluded should be updated. Mutations, each red on its one class and restored: allow-list loosened (`ShovelPathAllowListTest`), scrubber off (`ShovelScrubberTest`), ack-mode changed and amqps to amqp fallback and name-length check removed (`ManagementShovelGatewayTest`). The "no overwrite" mutation lives in core (M8-07, already covered). Not run: other brokerTest classes, full suite, screenshots, amqps live.
