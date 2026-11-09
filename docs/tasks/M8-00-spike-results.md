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
