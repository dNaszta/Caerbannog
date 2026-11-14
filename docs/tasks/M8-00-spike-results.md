# M8-00 spike results: RabbitMQ 3.13 vs 4.x, measured

Measured on 2026-11-09 on real brokers, Docker on an Apple-silicon Mac (aarch64 images): `rabbitmq:3.13-management` = **3.13.7** (Erlang 26.2.5.16) and `rabbitmq:4-management` = **4.4.0** (Erlang 29.1.2). The image tag `4-management` floats, so a future 4.x may answer differently; the spike tests fail on purpose when it does.

Living proof (tag `broker`): `RealBrokerM8SpikeDefinitionsTest` (17 tests: S-A to S-E, S-G, S-H, S-K, S-L, S-M) and `RealBrokerM8SpikeShovelTest` (6 tests: S-F, S-J), helper `M8SpikeSupport.kt`. Run `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM8Spike*'`. Raw responses: `data-rabbit/src/test/resources/fixtures/m8-00/` (throwaway users only; one URI secret redacted). Status: all 23 pass.

## Contradictions with `docs/spec.md` and the briefs (read first)

1. **"After an upgrade to 4.0 the mirroring parts of policies have no effect" (spec, Verified paragraph) is only half true.** An already stored `ha-*` policy survives an in-place upgrade and the node starts (S-E). But on 4.4 creating such a policy, or importing a definitions file that contains one, is **rejected** with `400 ... are not recognised policy settings` (S-B, S-E). So a 3.x export is not importable as is, and the compatibility rule for `ha-mode`, `ha-params`, `ha-sync-mode`, `ha-sync-batch-size`, `ha-promote-on-shutdown`, `ha-promote-on-failure` is **blocker (rejected)**, not "no effect". The Tanzu claim that leftover mirroring policies stop the node from starting is **refuted for upstream 4.4.0** (node started, policy still stored).
2. **A definitions import into 4.x is neither atomic nor reliably loud.** It stops at the first rejected policy or parameter but has already created vhosts, users, permissions (S-B). A queue that violates a deprecated feature (transient/non-durable, or `x-queue-master-locator`) is **silently not created and the import still answers `204`** (only a broker log line). Validation errors (for example quorum + `x-max-priority`) answer `400` with ONE reason, and which valid sibling queues exist afterwards varies from run to run. Consequence for M8-05/M8-11: never rely on 4.x to tell us what it dropped; the analyzer must predict it and the migration runner must verify after apply.
3. **A quorum queue on 4.4 orders by message priority, always** (S-G): with no `x-max-priority` argument, messages 1,9,2,8,0,5,4,6 come out 9,8,6,5,4,2,1,0; messages WITHOUT a priority property sort AHEAD of priority 3 (opposite of a classic queue, where no priority is lowest). A classic queue without `x-max-priority` ignores priorities (FIFO). So moving a classic queue (even one that never declared priorities) to quorum can reorder messages that carry a priority property. This is a semantic warning the briefs do not list.
4. **A missing Shovel destination queue is created by the Shovel as a durable CLASSIC queue** (S-F), not quorum. M8-07/M8-09 "block when the destination queue is missing" is therefore required, not optional.
5. **`GET /api/parameters/shovel/...`, `GET /api/parameters/shovel` and `GET /api/definitions` return the Shovel `src-uri` with the password in clear text**; only `GET /api/shovels` redacts it (S-F). The spec's rule "never call `GET /api/parameters/shovel`" is right and must also cover definitions exports taken while a Shovel exists.
6. **Brief M8-02/spec: `GET /api/definitions` needs the `administrator` tag**, confirmed (S-A), and so does `GET /api/definitions/{vhost}` and `GET /api/permissions/{vhost}/{user}` (existing finding). The per-vhost export contains **no** users, vhosts or permissions, so M8-12 needs the full export, not the per-vhost one.
7. **3.13 queue entries in the export have no `type` key** (S-A); the type is only `arguments.x-queue-type` (absent for classic). 4.x exports add `"type"` to every queue. The parser must treat a missing type as classic.
8. Spec "UNVERIFIED" items now answered: S-A, S-B, S-C, S-D, S-E, S-F, S-H, S-J, S-K, S-L, S-M (below). Not measured: see the last section.

## S-A: shape of the 3.13 export

- **Method:** seeded 3.13.7 (13 queues of all kinds, 4 exchanges, 3 bindings incl. exchange-to-exchange, 3 policies, 1 operator policy, 3 users, vhost `v1`, permissions, a topic permission, a federation parameter) and called `GET /api/definitions`, `/api/definitions/{vhost}`.
- **Observed:** top-level keys `rabbit_version, rabbitmq_version, product_name, product_version, users, vhosts, permissions, topic_permissions, parameters, global_parameters, policies, queues, exchanges, bindings`. Queue: `name, vhost, durable, auto_delete, arguments` (no `type`). Exchange: `name, vhost, type, durable, auto_delete, internal, arguments`. Binding: `source, vhost, destination, destination_type (queue|exchange), routing_key, arguments`. Policy: `vhost, name, pattern, apply-to, definition, priority`. **Operator policies are NOT a top-level key**: they appear in `parameters` with `component: operator_policy`. User: `name, password_hash, hashing_algorithm (rabbit_password_hashing_sha256), tags (array), limits`; the `guest` user and its permissions are exported. Vhost: `name, description, tags, metadata{description,tags[,default_queue_type]}`. Per-vhost export: only `rabbit_version, parameters, policies, queues, exchanges, bindings`.
- **Who may call it:** `administrator` only. Users with `management,policymaker` or `monitoring` get `401 {"error":"not_authorised","reason":"Not administrator user"}`, for the full and the per-vhost export alike.
- **Secrets:** a parameter's value is exported verbatim; a federation upstream URI with `user:password` appears in clear in the export (confirmed with a global parameter in the test).
- **Verdict:** confirmed. **Meaning:** M8-01 model: no `type`, queue type from arguments; operator policies read from `parameters`; users carry `passwordHash` + `hashingAlgorithm` (secret); the live source needs an administrator profile; every parameter value must be treated as possibly secret (drop federation and shovel URIs, never display).

## S-B: feeding the 3.13 export as is to 4.4

- **Observed:** `400` at the first `ha-*` policy: `Validation failed [{<<"ha-mode">>,<<"all">>},...] are not recognised policy settings (vhost: "/" key: "ha-all")`. By then the vhosts, users, permissions and topic permissions were created. Next blockers found one at a time by dropping the offender: `use of deprecated queue-master-locator argument is not permitted (key: "ha-exactly")` (a policy with `queue-master-locator`), and `component federation-upstream not found` (a parameter of a plugin that is not enabled on 4.x). With those removed the import returned `204`, but the queues `classic-transient`, `classic-autodel` (non-durable) and `classic-locator` (`x-queue-master-locator`) were **missing**; the broker log says `Feature transient_nonexcl_queues is deprecated. By default, this feature is not permitted anymore` and the same for `queue_master_locator`. Kept as is on classic: `x-queue-mode: lazy`, `x-max-priority`, `x-single-active-consumer`, TTL and limits, `x-dead-letter-*`; the quorum queue; bindings incl. exchange-to-exchange; the operator policy (as a parameter); the non-durable auto-delete *exchange*.
- **Validation errors:** a queue with `x-queue-type: quorum` and `x-max-priority` answers `400 precondition_failed: invalid arg 'x-max-priority' for queue ... of queue type rabbit_quorum_queue`; valid siblings may or may not be created (parallel, varies).
- **Verdict:** confirmed; see contradiction 2. **Meaning:** generator rule: drop or convert exactly the set in S-C/S-D/S-E before writing the migration; apply must read back and compare.

## S-C: what a 4.4 quorum queue declaration accepts (`PUT /api/queues`, `queue-declaration-matrix-4.x.json`)

- **Rejected (400 `invalid arg ... of queue type rabbit_quorum_queue`):** `x-max-priority`, `x-queue-mode` (lazy and default), `x-queue-master-locator`, `x-queue-version` (1 and 2). Property: durable `auto_delete=true` -> `400 invalid property 'auto-delete'`. `durable=false` -> `400 Feature transient_nonexcl_queues is deprecated ... not permitted`.
- **Accepted and kept:** `x-single-active-consumer`, `x-message-ttl`, `x-expires`, `x-max-length`, `x-max-length-bytes`, `x-overflow` (drop-head, reject-publish, reject-publish-dlx), `x-dead-letter-exchange/-routing-key`, `x-dead-letter-strategy at-least-once` (with an exchange and reject-publish), `x-delivery-limit`, `x-quorum-initial-group-size`, `x-queue-leader-locator`, `x-max-in-memory-length/-bytes` (accepted, not an error on 4.4).
- **Exclusive:** the management API cannot declare an exclusive queue, so not measured (see last section). **Verdict:** confirmed per argument. **Meaning:** blockers for classic->quorum: priority, lazy mode, master locator, queue version, transient, auto-delete; the rest converts as is; warn about `x-max-in-memory-*` (accepted, probably ignored; unverified effect).

## S-D: classic on 4.4

- **Accepted:** `x-queue-mode` lazy and default (kept as an argument), `x-max-priority` 5 and 255, `x-single-active-consumer`, TTL, limits, overflow modes, `x-dead-letter-*`, `x-queue-version: 2`, `x-queue-leader-locator`, durable `auto_delete=true`.
- **Rejected:** `x-queue-master-locator` (`Feature queue_master_locator is deprecated ... not permitted`), `x-queue-version: 1` (`unsupported queue version '1'`), `x-dead-letter-strategy`, `x-delivery-limit`, `x-quorum-initial-group-size`, `x-max-in-memory-length/-bytes` (invalid arg for the classic type), and every non-durable queue (`transient_nonexcl_queues`, also for stream and quorum).
- **Verdict:** confirmed. **Meaning:** keeping a queue classic is possible for everything except transient and the master locator; the app's proposal (classic -> quorum) is the owner's choice per queue, not forced by 4.x except for those two. `GET /api/deprecated-features` on 4.4 lists `queue_master_locator`, `global_qos`, `transient_nonexcl_queues`, `amqp_filter_set_bug`, `amqp_address_v1` as `denied_by_default`, `management_metrics_collection` as `permitted_by_default`, `classic_queue_mirroring` and `ram_node_type` as `removed`.

## S-E: policy keys on 4.4 (policy and operator policy)

- **Rejected both ways (400 `are not recognised policy settings`):** `ha-mode` (all, exactly, nodes), `ha-params`, `ha-sync-mode`, `ha-sync-batch-size`, `ha-promote-on-shutdown`, `ha-promote-on-failure`. **Rejected:** `queue-master-locator` (`use of deprecated queue-master-locator argument is not permitted`).
- **Accepted as a normal policy:** `queue-mode` (lazy), `queue-version`, `max-length`, `max-length-bytes`, `message-ttl`, `expires`, `overflow`, `dead-letter-exchange`, `dead-letter-routing-key`, `delivery-limit`, `max-in-memory-*`, `target-group-size`, `alternate-exchange`, `queue-leader-locator`. As an **operator** policy `queue-mode`, `dead-letter-*`, `alternate-exchange`, `queue-leader-locator` are rejected (not recognised), the limits are accepted. `federation-upstream-set` is rejected only because the federation plugin is off.
- **In-place upgrade (extra, same spike):** a 3.13.7 data directory with an `ha-all` policy was started on 4.4.0 (same hostname and volume). The node **starts** and serves; the policy stays stored with its `ha-*` keys (so a 4.x `GET /api/policies` shows keys the API would refuse to create); transient queues are gone. **Observed, cause unverified:** two classic queues whose 3 persistent messages were confirmed on 3.13 came back with 0 messages after a graceful stop, with `rabbit_classic_queue_index_v2:recover` crashes in the 4.x log (repeated twice); the quorum queue kept all 3. This path is not what M8 does (M8 moves topology and shovels messages), but it is a reason the Shovel route, not an in-place upgrade, is the safe one; a direct 3.13 -> 4.4 jump may simply be an unsupported path.
- **Verdict:** confirmed. **Meaning:** the analyzer maps every `ha-*` key to "remove (rejected on 4.x; replicate by choosing quorum)", `queue-master-locator` to remove, `queue-mode` policy to info (accepted, lazy has no effect on quorum).

## S-F: dynamic Shovel on 4.4 pulling from 3.13 over a Docker network

- **Plugin detection:** `GET /api/shovels` is `404` without the shovel plugins and `200` with `rabbitmq_shovel` + `rabbitmq_shovel_management`.
- **PUT:** `PUT /api/parameters/shovel/%2F/{name}` with `{"value":{src-protocol:"amqp091",src-uri:"amqp://user:pw@old:5672",src-queue,dest-protocol:"amqp091",dest-uri:"amqp://",dest-queue,src-delete-after,ack-mode:"on-confirm"}}` -> `201` with an empty body. **No validation at PUT time**: a wrong source password still answers `201`.
- **Credential-free local destination:** `dest-uri: "amqp://"` works; only the source password is in the parameter. **P1 consequence:** only the source credentials are exposed.
- **Status (`GET /api/shovels`, `api-shovels-running-4.x.json`):** `node, timestamp, name, vhost, type ("dynamic"), state ("running"), pending, remaining ("unlimited" for never), remaining_unacked, forwarded (count), src_uri (`amqp://old:5672`, password removed), dest_uri, src_protocol, dest_protocol, src_queue, dest_queue, blocked_status`. A failed one: `state: "terminated"`, `reason: "\"failed to connect to source\""`, no counters.
- **Redaction:** `GET /api/shovels` redacts; `GET /api/parameters/shovel[/vhost/name]` and `GET /api/definitions` return the password in clear (contradiction 5).
- **Properties and order:** with `ack-mode on-confirm` body, `message_id`, `content_type`, headers and `delivery_mode` arrive unchanged and FIFO order is kept (messages without priority; see S-G for priority). `redelivered` is false on the destination, `exchange` is empty and the routing key is the destination queue.
- **Who may:** `PUT` as `management+policymaker` -> `201`; as `monitoring` -> `401 User not authorised to access object`; as an administrator without permission on the vhost -> `400 user may not connect to vhost`. `GET /api/shovels`: `monitoring` and administrator -> `200`, `management+policymaker` -> `401`. `GET /api/whoami` lists the tags, so the app can tell before it tries; reading status needs `monitoring` or `administrator`, creating needs `policymaker` or `administrator`.
- **Remove:** `DELETE /api/parameters/shovel/%2F/{name}` -> `204`, the status list empties, the **source queue is kept**.
- **Missing destination queue:** created by the Shovel as durable **classic** (contradiction 4).
- **Not measured:** `amqps` and TLS options, `ack-mode` other than `on-confirm`.
- **Verdict:** confirmed. **Meaning:** M8-07/08: dry run must check destination existence and type; poll `GET /api/shovels` after PUT and treat `terminated` as failed (then delete the shovel); progress from `forwarded`/`remaining`; never read the parameter endpoints; require `policymaker` (create) and `monitoring` or administrator (status).

## S-J: `queue-length` with a running publisher

- **Observed:** 120 messages on the source, shovel with `src-delete-after: queue-length`, 20 more published immediately after the PUT. The shovel stopped after moving the messages present **when it connected** (126 in the first run, not exactly 120, not 140); the rest stayed on the source. Dest + source always equalled 140 (nothing lost or duplicated). **The shovel removed its own parameter** when finished (`GET /api/parameters/shovel/...` -> `404`), so the stored credentials disappear (P5 holds).
- **Management stats lag:** `GET /api/queues/{vhost}/{name}` `messages` was stale (a destination showing 0 for several seconds while 126 messages were readable); use `forwarded` from `GET /api/shovels` or a peek, never the cached count, to judge completion.
- **Verdict:** confirmed. **Meaning:** "move what is there now" is approximate under load; the UI says "about N", re-reads the source after completion and tells the user messages published meanwhile stay; an exact cut-over needs the publishers stopped first.

## S-G: classic -> quorum semantics measurable through the management API (4.4)

- **Priority:** see contradiction 3. Same order on 3.13 classic and 4.4 classic with `x-max-priority`; quorum always priority-ordered.
- **Per-message TTL (`expiration`):** works on quorum and classic (the message expired, the other stayed).
- **`x-max-length: 3` default overflow:** both keep the newest 3 (drop-head).
- **Single active consumer:** accepted on both types (behaviour not measured, no consumer).
- **Not measured (needs an AMQP client, not in the project's test dependencies):** redelivery and the default delivery limit under real consumers (M7 gate shows that management peeks do not count), exclusive consumers, consumer-visible global QoS removal.

## S-H: detection and permissions against 3.13

- `GET /api/overview` -> `rabbitmq_version: "3.13.7"` (and `"4.4.0"` on 4.x); also `product_name`, `product_version`, `management_version`, `erlang_version`, `cluster_name`, `node`.
- `GET /api/whoami` -> `{name, tags[], is_internal_user}` identical shape on both. `GET /api/permissions/{vhost}/{user}` -> `200` for an administrator, `401 Not administrator user` for `management`/`monitoring` on both: the existing permission check and Connect work unchanged against 3.13 (stream plugin absent: `/api/stream/...` -> `404`, which the app already treats as "no stream plugin").
- `GET /api/queues/{vhost}?page=&page_size=&columns=...`, `/api/exchanges/{vhost}`, `/api/bindings/{vhost}`, `/api/vhosts` work the same on 3.13.
- **Extra, valuable:** `GET /api/deprecated-features` exists on 3.13 (phases `permitted_by_default` for `classic_queue_mirroring`, `global_qos`, `ram_node_type`, `transient_nonexcl_queues`, `management_metrics_collection`) and on 4.x; **`GET /api/deprecated-features/used` on 3.13 reported `classic_queue_mirroring` as in use** (the ha policy), `[]` on 4.x. This is a live signal the analyzer can add (`global_qos` use is not detectable, per the docs page `https://www.rabbitmq.com/docs/deprecated-features`).
- **Verdict:** confirmed; version detection by `rabbitmq_version` works, a profile flag is not needed (P6 recommendation holds).

## S-I: client-visible changes the app cannot see

Only what the brokers themselves state: `/api/deprecated-features` on 4.4 marks **`global_qos` denied by default** (doc_url `https://blog.rabbitmq.com/posts/2021/08/4.0-deprecation-announcements/#removal-of-global-qos`), `transient_nonexcl_queues` and `queue_master_locator` denied, `amqp_address_v1` and `amqp_filter_set_bug` denied (AMQP 1.0 clients), `classic_queue_mirroring` and `ram_node_type` removed. Fetched `https://www.rabbitmq.com/docs/deprecated-features`: it only describes the phases (permitted by default, denied by default, disconnected, removed) and the `deprecated_features.permit.<name>` setting; the per-feature table is on a separate page that was not fetched. The report should list these as "check your clients" info items (global QoS `basic.qos(global=true)`, transient/exclusive-less transient queues, AMQP 1.0 address format v1), not as definitions findings.

## S-K: vhost default queue type

- 3.13 and 4.4 both accept `PUT /api/vhosts/{v}` with `default_queue_type: quorum`; a type-less queue declared there becomes quorum (also via `POST /api/definitions/{vhost}` on 4.4). The 3.13 export carries `default_queue_type` at the vhost top level and in `metadata`; the 4.4 export carries only `metadata` (+ `limits`) and **names `type` on every queue**.
- **Meaning:** an option for the generator ("make quorum the vhost default instead of rewriting each queue"); the importer must read `metadata.default_queue_type`.

## S-L: size and time

- A 3.13 vhost with 1000 queues (100 bindings, a third lazy): `GET /api/definitions/{vhost}` 84 KiB in about 10 ms, full export 105 KiB; importing it into 4.4 took 0.6 s and all 1000 queues existed. **Verdict:** no streaming needed for the live fetch; 1000 queues is trivial.

## S-M: users and password hashes

- **Accepted on 4.4 (`PUT /api/users` and import):** `rabbit_password_hashing_sha256`, `rabbit_password_hashing_sha512`, `rabbit_password_hashing_md5`. **Rejected:** `rabbit_password_hashing_bcrypt` and unknown names -> `400 ["unsupported_hashing_algorithm","..."]`. **3.13 accepted any name** (including a nonsense one), so a 3.x export can contain a hash 4.x refuses.
- **An imported hash keeps the old password working:** users from the 3.13 export (`svc`, `mon`) logged in on 4.4 with their original passwords (`whoami` 200); also for fresh sha256/sha512/md5 hashes.
- **Omitting `hashing_algorithm` is unsafe:** `PUT /api/users` with only `password_hash` stored the user but the login with the original password failed (401) on both 3.13 and 4.4. Always send the algorithm explicitly.
- **Reading back for the idempotency compare:** `GET /api/users/{name}` on 4.4 **hides** the hash (`has_password: true`); **`GET /api/definitions` on 4.4 still includes `password_hash`** (administrator), so a comparison is possible through the full export on the target.
- **Conflicts:** importing a different hash for an existing user answers `204` and **overwrites it silently** (the old password stops working). The app must compare before it applies (spec says conflict, never overwrite: this confirms why).
- **`guest`:** exported like any user (default hash). Loopback restriction not measured (the container image allows remote guest; see below).
- **Verdict:** confirmed; contradicts nothing. **Meaning:** P12: algorithm outside {sha256, sha512, md5} on a 4.x target = blocker with the manual step; the migration action always carries the algorithm; conflict detection reads `/api/definitions` on the target.

## Not measured, and why

- **Exclusive queues and exclusive consumers**, **redelivery/delivery limit with real consumers**, **global QoS** behaviour: need an AMQP 0-9-1 client; the project has none in its test dependencies and adding one was out of scope.
- **`guest` loopback-only default**: the official image configures `loopback_users` for container use; the default of a plain install was not measured.
- **Shovel over `amqps`/TLS**, **other ack modes**, **Shovel with `src-delete-after` as an integer**, **a Shovel under a `local` protocol**: not measured; v1 uses amqp091 + `on-confirm`.
- **The cause of the classic-queue message loss in the in-place 3.13 -> 4.4 upgrade** (unsupported jump, index format, or an artifact of the volume copy): observed twice, not diagnosed.
- **Federation upstream/shovel static parameters on 3.x**: only measured as "parameters are exported verbatim with credentials"; the federation plugin was off.
- **Clusters** (mirrored queues with real mirrors, `slave_nodes`): single nodes only; the export shape for a clustered 3.x broker may add node-related fields.
- **Windows/Podman and Intel images:** run on Docker/aarch64 only.

## S-N: classic queue messages after in-place upgrade

Measured 2026-11-09 (M8-13), same hosts as above (3.13.7; `rabbitmq:4.0` = 4.0.9, `4.1` = 4.1.8, `4.2` = 4.2.9, `4.3` = 4.3.7, `4` = 4.4.0). Living proof: `RealBrokerM8SpikeUpgradeTest` (6 tests, two clean runs). Method: one Docker volume is the broker data directory; a 3.13 node is stopped with `docker stop -t 60` (or killed) and another image is started on the same volume with the same host name (node name `rabbit@upg-host`). Messages are counted with a requeueing peek (exact, no statistics lag). Every queue held 3 persistent messages unless noted.

**Verdict: cause found. It is the classic queue index version, not feature flags, not the stop mode and not the volume.** RabbitMQ 3.13 creates classic queues with the **version 1** index by default (management API field `storage_version` = 1). RabbitMQ **4.3 and later cannot recover a version-1 index**: the queue process crashes at start with `{case_clause,undefined}` in `rabbit_classic_queue_index_v2:recover/4` (`Queue ... failed to initialise`), and the queue comes back **empty**. Versions 4.0, 4.1 and 4.2 still convert a version-1 index to version 2 on recovery and keep the messages.

### What is lost and what survives (direct 3.13.7 -> 4.4.0, graceful stop)

| 3.13 queue | Messages on 4.4 |
| --- | --- |
| classic, declared without arguments (the 3.13 default, version 1) | **0, lost** |
| classic, `x-queue-version: 1`; also `x-queue-mode: lazy`, `x-max-priority`, 6 KB bodies, empty | **0, lost** (an empty one is recreated empty) |
| classic, `x-queue-version: 2` (also with `x-max-priority`, 6 KB bodies) | 3, kept |
| quorum | 3, kept |
| any queue, **transient** messages (`delivery_mode` 1) | 0 (lost by every restart, unrelated to the version) |
| non-durable queue | queue gone (unrelated to the version) |

### The loss is silent

After the upgrade the node boots normally, the queue is `state: running`, accepts publishes and is listed in the API with `messages: 0`. **Nothing in the management API says that messages were lost.** The only trace is in the broker log: `Queue <pid> failed to initialise: {{case_clause,undefined},[{rabbit_classic_queue_index_v2,recover,4,...` (one crash block per queue, repeated). The app can therefore only prevent it, never detect it afterwards.

### Where the boundary is (direct jump from 3.13.7, one fresh volume per target)

| Target | v1 queue | v2 queue |
| --- | --- | --- |
| 4.0.9, 4.1.8, 4.2.9 | 3, converted and kept | 3 |
| 4.3.7, 4.4.0 | **0, lost** | 3 |
| 3.13 -> 4.0 -> 4.4 (two hops, graceful stop each) | 3 for every classic variant, 3 on 4.4 | 3 |

So going **through 4.0 (or 4.1/4.2) first** converts the queues and the next jump to 4.4 is clean. (The 4.3 release notes were not fetched; the boundary is measured, not cited.)

### Hypotheses tested and refuted

- **Feature flags:** on 3.13.7 every stable feature flag was already enabled; only `khepri_db` was disabled and 4.x enabled it by itself during boot (Mnesia to Khepri migration, logged, no problem). The hop through 4.0 works with exactly the same flags, so flags are not the cause. (Not measured: a node with a stable flag deliberately disabled, which a current 3.13 does not allow.)
- **Unclean stop:** a forced `docker kill` of 3.13 loses nothing on 3.13 itself (control: restart of the same image keeps all queues), and the 4.4 result after a kill is the same as after a graceful stop (v2 kept, v1 lost). This holds for state the node has already settled: 3.13 records a declared queue in Mnesia and the management API answers before Mnesia has written it, so a kill right after a declare can lose the newest queue definition (seen in about 4 of 100 rounds on a throttled node, CI 2026-11-14). The test therefore publishes over AMQP with confirms and waits until both queues are in the durable-queue table files (`mnesia:dump_log()` through `rabbitmqctl eval`) before it kills the node. The exact path is only partly proven; fsync is not the cause (the 3.13 version-2 queue store only calls `pwrite`).
- **Persistent vs transient, durable vs not, lazy mode, priorities, large bodies (index vs store):** none changes the outcome for version 1 (all lost) or version 2 (all kept; transient messages lost as always).
- **Volume copy vs same volume, management publish vs AMQP publish:** not varied (the cause is the index version of the queue, which neither can change; AMQP publishing needs the AMQP client dependency, not added in this spike).

### How to tell, and how to convert, on the 3.13 broker

- **Detection:** `GET /api/queues/{vhost}/{name}` and the list endpoint `GET /api/queues/{vhost}` on 3.13 return `storage_version` (1 or 2) for classic queues (absent for quorum). It appears with the queue's first statistics, a few seconds after the queue starts or is declared, so a scan must tolerate a missing value (poll or retry). `GET /api/definitions` does NOT carry it (only `x-queue-version` when the application set it), and a queue without that argument is version 1 on 3.13. `rabbitmqctl list_queues name version` is not a valid column.
- **Conversion on 3.13:** a policy `queue-version: 2` plus a **graceful restart of the 3.13 node** converts the queue (after the restart `storage_version` = 2, the 3 messages are still there, and they survive a direct jump to 4.4). Setting the policy on a running node makes the API report version 2 immediately, but whether the on-disk index is converted by the time the node stops was **not deterministic**: queues survived in two runs (policy 2 s and 25 s before the stop) and one queue was lost in a run where the node was stopped 3 s after the policy. So the API value alone is not proof; verify after a restart.
- **Node-wide default:** `classic_queue.default_version = 2` in `rabbitmq.conf` makes new classic queues version 2; it does not convert existing ones (not measured separately).

### What the app should say and recommend (suggested wording for M8-03/M8-06)

Blocker for every classic queue with `storage_version` 1 (or without a known version) on a 3.13 source, when the target is 4.3 or later:

> "Queue `orders` uses the old classic queue index (version 1). RabbitMQ 4.3 and later cannot read it: **an in-place upgrade to 4.3+ would silently empty this queue (messages lost, no error in the management API).** Convert it first: set the policy `queue-version: 2` on this broker and restart the node, then check that the queue shows version 2. Or upgrade through RabbitMQ 4.0, 4.1 or 4.2 before 4.3+. The safest way is not to upgrade in place: migrate the topology to a new 4.x broker and forward the messages with a Shovel (the Shovel path is not affected)."

Recommendations: (1) the report must contain this as a **blocker with severity "message loss"** and must not offer "upgrade in place" as a green option; (2) the live scan should read `storage_version` per classic queue and the report should list every version-1 queue by name; for a definitions file (no version field) it must warn for **every classic queue** that does not have `x-queue-version: 2`; (3) the recommended path stays blue-green (new 4.x broker, topology migration, Shovel), which does not read the old index; (4) the mirrored-queue and transient-queue findings (S-D, S-E) are separate from this one.

### Not measured in S-N

Release-note confirmation of the 4.3 change, `classic_queue.default_version` in `rabbitmq.conf`, a 3.12 -> 4.x jump, AMQP-published messages, a clustered upgrade, a volume copy (`docker cp`) instead of the same volume, the non-deterministic policy-without-restart conversion (timing), and x86_64 (aarch64 images only).

## S-O: exclusive queues and consumers, redelivery and the delivery limit, global QoS

Measured 2026-11-12 (M8-13a) on `rabbitmq:3.13-management` = 3.13.7 and `rabbitmq:4-management` = 4.4.0 (Docker, aarch64), with `com.rabbitmq:amqp-client` 5.37.0 (test-only; POM licences AL 2.0 / GPL v2 / MPL 2.0, used under Apache-2.0; 5.37.0 is from 2026-10-01, 5.37.1 from 2026-11-06 is too young). Living proof: `RealBrokerM8SpikeDeliveryTest` (9 tests, helper `M8SpikeAmqpSupport.kt`). Method: real consumers with manual acks and `basic.qos(1)`, one poison message requeued in a loop (a loop cap of 60 acks it to end the test), and connections dropped without an ack.

### Exclusive queues and exclusive consumers

- **An exclusive queue is never in a definitions export, on either version** (not in `GET /api/definitions`, so it never reaches a migration file or a scan through the export). The management API lists it (`exclusive: true`) with the owner connection, and **reports `durable: false` even when the client declared it durable**. It is deleted when its connection closes.
- Another connection that consumes from or passively declares it gets `405 RESOURCE_LOCKED - cannot obtain exclusive access to locked queue`.
- **A quorum queue cannot be exclusive:** `queue.declare` with `exclusive=true` and `x-queue-type: quorum` (durable or not) is refused on 3.13 and 4.4 alike with `406 PRECONDITION_FAILED - invalid property 'exclusive-owner'`; the same for a stream. The client gets a refusal, not a silent change.
- **Exclusive consumer (`basic.consume` with `exclusive=true`):** on a classic queue a second consumer is refused (`403 ACCESS_REFUSED - queue ... in exclusive use`). **On a quorum queue the flag is accepted but not enforced:** the first consumer is listed with `exclusive: true`, and a second consumer from another connection is accepted too. Same on 3.13 and 4.4. (Measured: acceptance and listing, not message distribution.)
- **Meaning:** an exclusive classic queue is transient by nature and invisible to a definitions export, so it is not a migration item. An application that relies on an exclusive consumer for ordering or failover keeps running against a quorum queue **without** the guarantee and gets no error. That is a semantic warning for classic to quorum conversion (the alternative is `x-single-active-consumer`, accepted by both types).

### Redelivery and the delivery limit

| Case | 3.13.7 | 4.4.0 |
| --- | --- | --- |
| classic, nack or reject with requeue, 60 times | no limit | no limit |
| classic, connection dropped without ack, 30 times | no limit | no limit |
| quorum, no `x-delivery-limit`, **reject** with requeue | no limit (60 deliveries) | **dropped after 21 deliveries (the default limit 20 is real: 1 + 20 redeliveries)** |
| quorum, no limit, **nack** with requeue | no limit | **no limit (60 deliveries, never counted)** |
| quorum, `x-delivery-limit` 5, reject | 6 deliveries, then gone | 6 deliveries, then gone |
| quorum, `x-delivery-limit` 5, **nack** | 6 deliveries, then gone | **60 deliveries, the limit is never reached** |
| quorum, connection dropped without ack | no limit (30 returns) | **21 deliveries, then gone** (same as reject) |
| quorum, policy `delivery-limit: 3` | reject and nack both stop after 4 | reject stops after 4, **nack never** |

- **Surprise: on 4.4 `basic.nack` with `requeue=true` on a quorum queue is not counted toward the delivery limit, not even an explicit `x-delivery-limit` or a policy one, while `basic.reject` with `requeue=true`, a closed channel and a closed connection all are.** On 3.13 nack and reject behave the same and count only when a limit is set. Redeliveries from nack carry no `x-delivery-count` header on 4.4; those from reject do (1 to 20). (Measured with single-message nacks, `multiple=false`, prefetch 1. Why 4.4 treats them differently was not investigated.)
- **A message past the limit is dropped silently when the queue has no dead-letter exchange** (queue empty, no error, the consumer just stops getting it). With a dead-letter exchange it is routed there with `x-first-death-reason: delivery_limit` on both versions.
- `GET /api/queues/{vhost}/{name}` on 4.4 reports the effective limit as `delivery_limit` (20 for a quorum queue without an explicit limit, 3 under the policy; also `effective_policy_definition: {"delivery-limit":3}` for a policy). **Correction of a first reading:** right after the queue is declared the field is absent or `null` (statistics not collected yet); a first exploratory run that read it too early concluded "not shown", and the second clean run of the test caught it. The field is absent on 3.13. A scan can therefore read the effective limit from a 4.x broker, but not from the 3.13 source (where no default exists anyway).
- **Meaning for classic to quorum:** a classic queue redelivers forever; a 4.x quorum queue drops a message after 20 failed deliveries (reject, consumer crash or connection loss) unless the queue has a dead-letter exchange with a queue behind it, or `x-delivery-limit` is raised. A poison message that loops forever today becomes a lost message. Consumers that use nack+requeue are not affected on 4.4 (they still loop, which hides the problem).

### Global QoS (`basic.qos` with `global=true`)

- **3.13:** accepted with a classic queue (the limit is shared by the whole channel: two consumers on one channel with prefetch 3 got 3 and 0 unacked messages). With a **quorum queue it is refused**: global qos followed by `basic.consume` closes the whole connection with `540 NOT_IMPLEMENTED - queue ... does not support global qos`; consuming first and setting global qos afterwards is accepted. Per-consumer qos works for both.
- **4.4: `global=true` is accepted everywhere without an error, for classic and quorum queues, and silently behaves per consumer** (two consumers on one channel with prefetch 3 got 3 and 3, six unacked messages instead of three). `GET /api/deprecated-features` marks `global_qos` `denied_by_default` on 4.4 (`permitted_by_default` on 3.13), yet the AMQP call is not refused. There is no refusal to react to: a client that relied on a channel-wide limit has more in flight after the migration, with no error.
- **Meaning:** the "check your clients" item for global QoS is worth keeping, and its wording should be "not an error on 4.x: the limit silently applies per consumer", not "refused".

### Not measured in S-O

Single-active-consumer failover, `basic.nack` with `multiple=true`, why nack differs from reject on 4.4, a cluster, AMQP 1.0 clients, whether an exclusive queue declared durable survives a restart (the broker lists it as non-durable), x86_64.

### Follow-up for the architect (S-O)

- **The scenario "Classic to quorum differences the spike has not measured are shown as unverified" (`16_migrate_from_3x.feature`, around line 369) is now outdated.** No scenario carries the `@needs-spike` tag (only the feature headers mention it), and I did not edit feature files or step definitions (they assert the product output "unverified"). Suggested report warnings for a classic queue converted to quorum, severity "behaviour change", not blocker: (1) "a 4.x quorum queue drops a message after 20 failed deliveries (default `x-delivery-limit`); add a dead-letter exchange or raise the limit; a classic queue redelivers forever"; (2) "an exclusive consumer is not enforced on a quorum queue; use `x-single-active-consumer`"; (3) the priority warning stays. The global QoS "check your clients" item should say it is **not refused** on 4.x and silently becomes per consumer.
- A live scan through `GET /api/queues` should skip `exclusive: true` entries (never exported, transient).

## S-T: `x-max-in-memory-length` and `x-max-in-memory-bytes` on a quorum queue

Measured 2026-11-12 (M8-13a), same brokers (3.13.7 and 4.4.0). Living proof: `RealBrokerM8SpikeInMemoryLimitTest` (3 tests).

- **Declaration:** a quorum queue declared with `x-max-in-memory-length` and/or `x-max-in-memory-bytes` (AMQP `queue.declare` and a `POST /api/definitions` import) is **accepted on 3.13 and on 4.4**; 0 is accepted, a negative value is refused (`invalid arg ... {value_negative,-5}`), a string is refused (`expected integer, got longstr`). A classic queue refuses both (`invalid arg ... of queue type rabbit_classic_queue`). Adding the argument to an existing quorum queue by redeclaring is an `inequivalent arg` refusal, like any argument.
- **What the management API shows:** the arguments are echoed in `arguments` and kept in `GET /api/definitions`. A policy with `max-in-memory-length` / `max-in-memory-bytes` is accepted (201), also as operator policy, on both versions; it is applied to a matching quorum queue (`policy` and `effective_policy_definition` show it) and does not rewrite `arguments`.
- **Effect: none observable.** Four quorum queues with 2000 messages of 4 KiB each (two plain, two with `length=1, bytes=1`): `messages_ram` and `message_bytes_ram` are **0 for every queue on both versions**, the unlimited ones included, so the API cannot show an in-memory cap. The queue `memory` field differed between two identical plain queues by about 160 KB on 3.13 and 34 KB on 4.4, more than any difference between a limited and an unlimited queue: noise. No broker log line mentions the arguments. **Conclusion: accepted and echoed; no effect this spike can measure, on either version.** That does not prove the broker ignores them internally (the statistics for quorum queues do not report in-memory messages); it means neither the app nor the user can tell.
- **Meaning for the report:** a classic queue refuses these arguments, so a 3.x classic queue never has them. They matter only for a **3.x quorum queue** that already carries them: 4.4 accepts them unchanged, so the generator may keep them. Severity: information, not a warning. Do not state that 4.x ignores them (not measured).
- **Not measured:** the broker's internal memory behaviour (needs an Erlang-side memory breakdown), behaviour under memory pressure, other 4.x versions.

### Follow-up for the architect (S-T)

- The spec text "accepted (effect unverified, warn)" can become "accepted and echoed, no effect observable through the management API; information, keep the argument". The product change, if wanted, is only the severity (warning to information).

## S-Q: Shovel over `amqps`, other ack modes, integer `src-delete-after`

Measured 2026-11-12 (M8-13b). Living proof: `RealBrokerM8SpikeAmqpsShovelTest` (4 tests, green). Brokers: Shovel on `rabbitmq:4-management` (4.4.0) with `rabbitmq_shovel` and `rabbitmq_shovel_management`, source `rabbitmq:3.13-management` (3.13.7) on a Docker network under the alias `tlsold`, listening for AMQP over TLS on 5671 (`data-rabbit/src/test/resources/tls/amqps-only.conf`, the throwaway fixtures: CA `Caerbannog Test CA`, server certificate CN and SAN `localhost`, so the host name used by the Shovel does NOT match the certificate). The destination is the local `amqp://`. Fixture credentials only (`guest:guest`).

### TLS: what the URI the product builds today does, and which options change it

The product builds `amqps://user:pw@host:5671/vhost` with no query string (`ManagementShovelGateway.start`). Outcomes on 4.4:

| Source URI (after `amqps://guest:guest@tlsold:5671/%2F`) | Result |
| --- | --- |
| nothing (what the product builds) | **terminated**, `reason: "failed to connect to source"`; the 4.4 log says `{tls_alert,{unknown_ca,"TLS client: ... Fatal - Unknown CA"}}` |
| `?verify=verify_none` | works, 5 of 5 moved (encrypted, certificate NOT checked) |
| `?verify=verify_peer` (no CA file) | terminated (unknown CA) |
| `?verify=verify_peer&cacertfile=/etc/rabbitmq/tls/ca.pem` | terminated: the CA is trusted but the host name `tlsold` is not in the certificate |
| `?cacertfile=...` alone | terminated (same host name check) |
| `?verify=verify_peer&cacertfile=...&server_name_indication=localhost` | **works**, 5 of 5 moved (verified, host name checked against `localhost`) |
| `?verify=verify_peer&cacertfile=...&server_name_indication=disable` | works (CA verified, host name check skipped) |
| `amqp://` to the TLS port 5671, or `amqps://` to the plain port 5672 | terminated |

Findings:

1. **A plain `amqps://` URI verifies the server certificate by default on 4.4.** That is the right default for D9a, but against a broker with a private CA (the owner's usual case, and our fixtures) the Shovel simply fails. It never falls back to `amqp` and never silently turns verification off.
2. **`cacertfile` (and `certfile`, `keyfile`) name a path on the machine that runs the Shovel, the 4.x broker, not on the app's machine.** The profile's CA field in the app is a file on the user's computer and cannot be used for this. The file has to exist on the 4.x broker (or its CA must be in that broker's trust store).
3. **The host name must match the certificate, or `server_name_indication` must name a name that does.** The AMQP host the Shovel uses is the one asked in the dialog (decision P8), which can differ from the certificate name: `server_name_indication=<certificate name>` handles that; `disable` skips the host name check only.
4. **The Shovel status gives no reason for any of this.** A TLS failure, a wrong password (S-F) and a protocol/port mix-up all show `state: terminated`, `reason: "failed to connect to source"`. The TLS detail (`unknown_ca`) is only in the 4.x broker log. The app cannot tell the user which one it was.
5. **The broker log line of the failed attempt redacts the credentials**: `Shovel 's5' failed to connect (URI: amqps://tlsold:5671/%2F)`. The test asserts that `guest:guest` does not appear in the 4.4 log.
6. The query options are carried in the URI as plain `key=value` pairs, so the product's URI builder CAN carry them (append a query string after the percent-encoded vhost). It does not today.

**Follow-up for the architect (S-Q, no product code changed):** with TLS on, the forwarding start will fail for every source whose certificate is not trusted by the 4.x broker, and the screen can only say "terminated: failed to connect to source". Decide (a) whether the dialog offers `cacertfile` (a path ON THE 4.X BROKER) and `server_name_indication` as optional advanced fields, with a warning that `verify=verify_none` encrypts without verifying and must never be applied silently (D9a); (b) the wording of the terminated state for `amqps` ("the 4.x broker may not trust this source's certificate, or its host name does not match"; there is no machine-readable reason); (c) that the dry run cannot detect any of this (it does not open the source over AMQP).

### `ack-mode` other than `on-confirm` (documentation only; the product stays on `on-confirm`)

- Accepted values: `no-ack`, `on-publish`, `on-confirm`; anything else is refused at PUT with 400 `ack-mode should be one of [no-ack, on-publish, on-confirm]`.
- On a healthy path all three move everything the same way (5 of 5, source empty, shovel removed with `queue-length`).
- **Against a destination that refuses publishes** (classic queue, `x-max-length: 2`, `x-overflow: reject-publish`; 5 messages; `src-delete-after: never`): **`on-confirm` keeps the 3 refused messages** (the Shovel retries them, the `forwarded` counter climbed past 3000 in seconds, and when the Shovel was removed the 3 messages were back on the source queue: source 3, destination 2). **`on-publish` and `no-ack` lost the 3 refused messages for good** (source 0, destination 2, no error anywhere). That is the measured reason for decision P9.
- Side observation: the `forwarded` counter of a Shovel under `on-confirm` counts attempts, not delivered messages, when the destination refuses.
- The same limit on a QUORUM destination (`x-max-length` + `reject-publish` declared as a quorum queue) did not limit it in an earlier exploratory run (5 of 5 arrived); not investigated and not recorded as a result.

### Integer `src-delete-after`

- A JSON number is accepted (`>= 0`); the string `"3"` is refused at PUT (400 `src-delete-after should be a number greater than or equal to 0, "never" or "queue-length"`).
- `3` with 10 messages: exactly 3 moved (source 7, destination 3), then the Shovel removed itself (its parameter and the stored password are gone). The count is exact, no approximation as with `queue-length`.
- `50` with 10 messages: 10 moved, the Shovel stays `running` waiting for the rest (a count larger than the queue behaves like `never`).
- `0`: accepted, moves nothing and removes itself at once (10 messages stay on the source).
- The `forwarded` figure of a Shovel that has already removed itself is no longer available; judge the result from the queues.

## S-P: the `guest` loopback restriction on a plain 4.x install

Measured 2026-11-12 (M8-13b). Living proof: `RealBrokerM8SpikeGuestLoopbackTest` (2 tests, green). Broker `rabbitmq:4-management` (4.4.0). The image ships `loopback_users.guest = false` in `/etc/rabbitmq/conf.d/10-defaults.conf`; the test replaces that file with one that does not contain the line, so the broker runs with its built-in default. The host connects through the mapped Docker port, which reaches the broker from the Docker bridge gateway, not from 127.0.0.1 inside the container.

- **Management API:** `guest:guest` answers **401** `{"error":"not_authorised","reason":"User can only log in via localhost"}` (broker log: `HTTP access denied: user 'guest' - User can only log in via localhost`).
- **AMQP 0-9-1:** after the client's `start-ok` with PLAIN `guest`/`guest` the broker **closes the TCP connection without a `connection.close` frame**; a client library sees a dropped socket, not an "access refused" text. The broker log has the reason: `PLAIN login refused: user 'guest' can only connect via localhost`.
- A user created with `rabbitmqctl add_user` (administrator, full permissions) logs in the same way from the same place: management 200, AMQP handshake to `connection.tune`. The restriction is the `loopback_users` list, whose default is exactly `guest`.
- Control: with the stock image configuration `guest` logs in from outside (200 and `connection.tune`).
- Not measured: the stream protocol and MQTT with `guest` (same user check, but only management and AMQP were exercised), and a real (non-container) install.

**Wording for the report's `guest` finding (suggested):** "`guest` is the publicly known default account. On a standard RabbitMQ 4.x installation it can only log in from the broker's own host (`loopback_users`), so a migrated `guest` would be refused over the network ('User can only log in via localhost'); where it IS allowed from outside (the official container image does this) it is a publicly known password. The app does not migrate it (M8-12, D2); create a named user for each client instead." Keep the broker's own text visible, as the rule says.

## S-R: a clustered 3.13 export

Measured 2026-11-12 (M8-13b). Living proof: `RealBrokerM8SpikeClusterTest` (2 tests, green). A real two-node cluster of `rabbitmq:3.13-management` (3.13.7), nodes `rabbit@rabbit1` and `rabbit@rabbit2` on a Docker network with a shared Erlang cookie, joined with `rabbitmqctl stop_app; join_cluster rabbit@rabbit1; start_app`; it started and joined reliably here in about 25 s (a third node was not tried). Three HA policies (`ha-mode` `all`, `exactly` with `ha-params: 2`, `nodes` with `ha-params: [rabbit@rabbit1, rabbit@rabbit2]`), classic queues matching them (3 messages each, a real mirror, `synchronised_slave_nodes` filled), a plain classic queue, a classic queue created on node 2 (`"node":"rabbit@rabbit2"` in the declare), and a quorum queue.

**`GET /api/queues` carries the mirror and node fields, `GET /api/definitions` carries none of them.**

- `GET /api/queues/{vhost}` per classic queue: `node` (`"rabbit@rabbit1"`), `policy` (`"ha-all"` or null), `effective_policy_definition` (the applied definition), **`slave_nodes`** (`["rabbit@rabbit2"]`, null when not mirrored), **`synchronised_slave_nodes`**, `recoverable_slaves`, `storage_version` (1 on all of them), `state`. `mirror_pids` is not present. A quorum queue shows `members`, `online`, `leader` instead, and no `storage_version`. A queue created on node 2 shows `node: rabbit@rabbit2` and no mirror fields (no policy).
- `GET /api/definitions`: top-level keys identical to a single node (`bindings, exchanges, global_parameters, parameters, permissions, policies, product_name, product_version, queues, rabbit_version, rabbitmq_version, topic_permissions, users, vhosts`). Every queue entry is exactly `{name, vhost, durable, auto_delete, arguments}`: **no `node`, no mirror list, no policy name** (the node of `pinned-on-2` is lost). The words `slave` and `mirror` do not appear anywhere in the export. The only node names in it are inside the `ha-params` list of the `nodes` policy. `global_parameters` holds `internal_cluster_id`. `rabbit_version` and `rabbitmq_version` are `3.13.7`.
- So for a cluster the export looks like a single node's export, and which queues are mirrored can only come from the policies (`ha-mode`), which is what `MirroringIndex` already does.
- **The product copes.** `DefinitionsParser.parse` reads the clustered export (all queues, all three policies). The live analysis (`ManagementThreeXReads` + `analyseLive`, through the app's own gateway against node 1) reads `storage_version` for every classic queue (`classic-index-v1` findings), flags the mirrored queue with `classic-not-replicated`, lists the `ha-*` policy (`ha-policy-key`) and gives the global `mirroring-in-use` finding from the deprecated-features list. Nothing failed on a cluster.
- Not measured: a three-node cluster, a node that is down during the export, an `ha-params` list naming a node that has left, a clustered in-place upgrade.

**Follow-up for the architect (S-R):** none needed for correctness. Optional: say in the report that on a cluster only the policies tell which queues are mirrored, and that a queue's node and mirror list are not in the export.

## S-N follow-ups (M8-13b)

Measured 2026-11-12. Living proof: `RealBrokerM8SpikeUpgradeFollowUpTest` (3 tests, green); same method as `RealBrokerM8SpikeUpgradeTest` (one volume, fixed host name, graceful stop). Images: 3.13.7, 4.0 (4.0.9), 4 (4.4.0).

- **`classic_queue.default_version = 2`:** on **3.13.7** a new classic queue declared without arguments is version 2 (default without the setting: 1), and a queue declared with `x-queue-version: 1` stays version 1. So it changes the default for NEW queues only, not existing ones, and an explicit argument still wins. **On 4.4.0** the default is version 2 with or without the setting, and **`x-queue-version: 1` is refused at declare time** (400 `unsupported queue version '1'`), so version 1 queues cannot be created on 4.4 at all (consistent with the 4.3 release notes, below).
- **MQTT-published messages in a version-1 classic queue** (3.13.7 with `rabbitmq_mqtt`, QoS 1 to `amq.topic`, queue bound with the routing key; the messages arrive as `delivery_mode: 2`; queue `storage_version` 1): after the direct upgrade **4.0.9 keeps 3 of 3 and 4.4.0 has 0 of 3**, the same as management-published messages. The way a message was published does not matter. **AMQP 1.0 was NOT tested** (no AMQP 1.0 client on the test class path; a persistent message sits in the same index, so no difference is expected, but that is not measured).
- **Timing of the policy-without-restart conversion:** policy `queue-version: 2` on a running 3.13.7 node, then a graceful stop after 0, 3 and 10 s (each twice), then a start of 4.4.0: **3 of 3 messages survived in all six runs, including the one stopped immediately after the policy**. The management API's `storage_version` is NOT immediate: it still said 1 right after the policy and 3 s later, and said 2 only after the 10 s pause (statistics lag). **This differs from the earlier S-N note** ("the API reports version 2 immediately") and the single loss seen then (a stop 3 s after the policy) did **not reproduce**. What can be said: the conversion on disk was in time in six of six runs here, the API value lags and is no proof, and the earlier loss is unexplained. Keep the advice "verify after the restart" and do not claim a timing guarantee.
- **4.3 release notes (documentation lookup: the GitHub release body of v4.3.0, fetched 2026-11-12; not measured, but the measured result agrees):** "Classic Queues v1 Storage (CQv1) is Removed ... attempts to declare a queue using ... `x-queue-version` set to `1` ... will fail ... Existing classic queues upgraded to CQv2 during an earlier upgrade to `4.2.x` will continue operating as usual." and "This release series supports upgrades from `4.2.x`. Upgrades from earlier series are **not** supported: users must upgrade to the latest available `4.2.x` patch release before upgrading to `4.3.0`." So the in-place 3.13 to 4.3+ jump that loses the messages is also an unsupported upgrade path by the project's own statement, and CQv2 became the default in 4.2.0 per those notes. The same notes move `global_qos`, `queue_master_locator`, `transient_nonexcl_queues`, `amqp_address_v1` and `amqp_filter_set_bug` to `denied_by_default` and make Khepri the only metadata store (documentation only, not measured here).

**Follow-up for the architect (S-N):** the report's message-loss blocker may add "RabbitMQ 4.3 documents in-place upgrades only from 4.2.x" as a second reason for the blue-green recommendation. The sentence about the policy ("an API value of 2 right after the policy is not proof, it lags by seconds") is now more accurate than "the policy applies immediately"; the spec Facts row says it. Check the 4.3 `denied_by_default` list against the existing `clients-*` rules (they already exist for global QoS, transient queues, AMQP address v1).

### Not measured in M8-13b

AMQP 1.0 publishing; a 3.12 to 4.x jump; a clustered upgrade and a volume copy (`docker cp`) instead of the same volume (heavy, out of this task); **S-S (x86_64, Windows, Podman) cannot be run here** (aarch64 images on Docker Desktop only); a Shovel with `src-protocol` other than `amqp091`; a three-node cluster; a quorum destination refusing publishes.
