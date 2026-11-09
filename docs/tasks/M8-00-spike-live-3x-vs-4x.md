# M8-00: Spike on live RabbitMQ 3.13 and 4.x (definitions, compatibility, Shovel)

Milestone: M8    Depends on: none    Size: M (two to three days)

## Goal
Every UNVERIFIED item of "Migrating from RabbitMQ 3.x (M8)" in `docs/spec.md` is measured on real `rabbitmq:3.13-management` and `rabbitmq:4-management` containers, written down with fixtures, and the spec is updated. No M8 rule or request is built from assumptions after this.

## Why
Spec "Migrating from RabbitMQ 3.x (M8)": a wrong compatibility rule silently loses features; a wrong Shovel assumption can move or lose messages. The repo already proves unverified claims with live tests (`RealBrokerStreamExchangeAnnotationsTest`, M2-02, M7 gate).

## Scope
In: `@broker` spike tests (JUnit tag `broker`, Testcontainers, two images on one network where needed) in `data-rabbit`, fixtures in `data-rabbit/src/test/resources/fixtures/m8-00/`, a Status section in this brief, spec updates. Spike items, in this order:

| Item | Question | Output |
| --- | --- | --- |
| S-A | Shape of 3.13 `GET /api/definitions` and `/api/definitions/{vhost}`: top-level keys, queues (`arguments`, `durable`, `auto_delete`, `type` present?), exchanges (internal, arguments), bindings (`destination_type`, exchange-to-exchange), policies (`apply-to`, `priority`, `definition`), operator policies, parameters, users (`password_hash`, `hashing_algorithm`, tags), permissions, vhosts (metadata, default queue type), global parameters. Which tag may call it (administrator only? monitoring? management?) | fixture of a rich 3.13 topology, field table |
| S-B | Feed that 3.13 export as is to 4.x (`POST /api/definitions`): what is imported, what is rejected and with which message, per object kind | fixtures, list of rejects |
| S-C | Which queue declarations a 4.x quorum queue rejects or ignores: exclusive, durable false, auto-delete, `x-max-priority`, `x-queue-mode`, `x-queue-master-locator`, `x-single-active-consumer`, `x-message-ttl`, `x-expires`, `x-max-length`, `x-max-length-bytes`, `x-overflow` (`drop-head`, `reject-publish`, `reject-publish-dlx`), `x-dead-letter-exchange`, `x-dead-letter-routing-key`, `x-dead-letter-strategy`, `x-delivery-limit`, `x-quorum-initial-group-size` | matrix: accepted, ignored, rejected (code and text) |
| S-D | Are classic queues with the same arguments still declarable on 4.x (mirrored or not; `x-queue-mode`, `x-max-priority`, exclusive, transient)? Is a non-exclusive transient queue still allowed? | matrix |
| S-E | Policy keys on 4.x: `ha-mode`, `ha-params`, `ha-sync-mode`, `ha-promote-on-*`, `queue-master-locator`, `queue-mode`, accepted and ignored, or rejected when set through the API and through a definitions import? Does a 4.x node start with such a policy in an imported definitions file (upstream; the Tanzu claim stays "not verified for upstream" unless reproduced) | matrix |
| S-F | Dynamic Shovel on 4.x, plugins `rabbitmq_shovel` and `rabbitmq_shovel_management`, 3.13 as source over a Docker network: (1) PUT body and the exact `GET /api/shovels` fields (state, counts, names); (2) is the password shown or redacted in `GET /api/shovels` and `GET /api/parameters/shovel/...`; (3) does a credential-free local URI (`amqp:///%2F`) work for the destination; (4) `src-delete-after` `never` vs `queue-length` semantics and whether the shovel removes itself; (5) does a missing destination queue get declared, and as what type; (6) `ack-mode` `on-confirm` actually removes from the source only after the confirm; (7) message properties, headers and priority preserved; order preserved; (8) `amqps` with the TLS fixtures, which URI options are needed; (9) how to detect missing plugins (`GET /api/shovels` 404? `GET /api/extensions`?); (10) policymaker tag visible in `GET /api/whoami`; (11) PUT with an existing name replaces it | fixtures, exact request and response shapes |
| S-G | Classic to quorum semantics: ordering, redelivery and the default delivery limit, priority parity, per-message TTL vs per-queue TTL, single active consumer, exclusive consumers, `x-max-length` behaviours | list of verified differences |
| S-H | `GET /api/overview` on 3.13: `rabbitmq_version` field; `GET /api/whoami`, `GET /api/permissions/{vhost}/{user}` on 3.13; does the existing permission check and Connect work against 3.13 as they are | notes, version-detection rule |
| S-I | Client-visible changes that the app cannot see in definitions (global QoS removal and others the release notes mention): write down only what a test or the 4.x release notes (cite the page) confirm | list with source |
| S-J | One Shovel per queue at the same time on a source with 120 messages and a running publisher: does `queue-length` stop at the start count? | observation |
| S-K | Does 4.x accept a definitions-less `vhost` with a different default queue type (`default_queue_type`)? Relevant to the manual vhost step | note |
| S-M | Users (P2): which `hashing_algorithm` values 3.13 exports and which 4.x accepts in `POST /api/definitions` and `PUT /api/users/{name}` (`password_hash` plus `hashing_algorithm`); does an imported hash let the user log in with the old password; what `GET /api/users/{name}` returns on 4.x (hash readable for the idempotency comparison?); is `guest` still loopback-only on 4.x; `PUT /api/permissions` and `/api/topic-permissions` replace semantics and bodies; `PUT /api/vhosts/{name}` body (description, tags, default queue type); which tag is needed for each call; can an administrator without permission on a vhost create it and its permissions | fixtures, matrix |
| S-L | `GET /api/definitions` size and time on a 1000-queue 3.13 vhost (the dev seed can generate it) | numbers, decides whether live fetch needs streaming |

Out: any product code beyond test helpers.

## Acceptance
- BDD: none run; the spike's deliverable is knowledge. It unblocks `@needs-spike` scenarios in `16_migrate_from_3x.feature` ("A user who may not export definitions...", "Queue settings that a quorum queue does not accept...", "Switching a classic queue to quorum...") and the Shovel field names in `17_forward_messages_with_shovel.feature`.
- The spike tests assert the findings (tag `broker`), so they stay as living proof, and clean up containers.
- Spec updated: the M8 section's "UNVERIFIED" list is replaced by the measured facts, open question rows ticked, no item left silently unanswered (an unanswered item says so).

## Design notes
Helpers: reuse `RabbitMQContainer` usage and the TLS fixtures in `data-rabbit/src/test/resources/tls`; put the two containers on one `Network` with aliases for S-F. Image tags `rabbitmq:3.13-management` and `rabbitmq:4-management` (note the exact floated versions in the Status, as M2-03a did). Enable shovel plugins with `rabbitmq-plugins enable` through exec, not the deprecated `withPluginsEnabled`. Poll, never sleep.

## UX
None.

## MCP
None (spike).

## Open points
- Decided by the owner (2026-11-09): P1 reuse the profile user for the Shovel, with disclosure in the confirmation; P5 `src-delete-after` default `queue-length`; P2 users, vhosts and permissions ARE part of the generated migration (`M8-12`). The spike results can change P1 and P5 (credential-free destination URI) and must answer the hash questions below.
- Also decided by the owner (2026-11-09, accepting the architect's recommendations): P3 one topology migration per source vhost; P4 removing a Shovel needs Full control; P6 automatic 3.x detection; P7 Shovel on the 4.x broker; P8 AMQP host and port asked in the dialog; P9 `on-confirm` only; P10 access actions exempt from V1, own file; P11 `guest` skipped by default; P12 unsupported hash algorithm is a manual step; P13 owner-only file and export warning. Nothing product-wise is open; the first M8 step is this spike.

## Definition of done
Spike tests pass on a machine with Docker or Podman, fixtures committed, Status written (what was measured, floated image versions, what could not be measured), spec and `16`/`17` updated, no product code changed.
