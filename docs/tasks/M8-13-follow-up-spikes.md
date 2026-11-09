# M8-13: follow-up spikes after M8-00

Milestone: M8    Depends on: M8-00 (done)    Size: M (each item S-M, run only when its result is needed)

## Goal
Measure what `M8-00-spike-results.md` listed as not measured, in the order of risk, so the report can drop "unverified".

## Why
Spec "Facts" (Not measured) and "Open questions". The first item can change the recommendation for existing brokers.

## Scope
In, in order:

| Item | Question | Why now |
| --- | --- | --- |
| S-N | Why did classic queues come back empty after an in-place 3.13.7 to 4.4.0 upgrade while a quorum queue kept its messages (crashes in `rabbit_classic_queue_index_v2:recover`)? Vary: persistent vs transient messages, classic queue version (`x-queue-version` 1 and 2), graceful vs forced stop, a copy of the volume vs the same volume, an intermediate 3.13 to 4.0 to 4.4 path. Compare with the documented upgrade path | Data loss; decides the wording of the report's global warning and whether the app recommends blue-green only |
| S-O | Exclusive queues and exclusive consumers, redelivery and the delivery limit (default 20 per the owner, 4.x) with real consumers, global QoS refusal. Needs an AMQP 0-9-1 client in test dependencies (`com.rabbitmq:amqp-client`, approved 2026-11-09, test-only) | Semantic warnings of the classic to quorum conversion |
| S-Q | Shovel over `amqps` with the TLS fixtures (URI options), `ack-mode` other than `on-confirm` (documentation only, v1 is on-confirm), integer `src-delete-after` | TLS rule D9a for the Shovel; today only the unit test covers it |
| S-P | `guest` loopback restriction on a plain (non-container) 4.x install | `guest` finding wording |
| S-R | Clustered 3.x export shape (mirrored queues with real mirrors, node fields) | Several of the owner's brokers may be clusters |
| S-T | Effect of `x-max-in-memory-length/-bytes` on a 4.x quorum queue | Warning wording |
| S-S | x86_64, Windows, Podman runs of the spike tests | Platform risk |

Out: product code.

## Acceptance
- Tests tagged `broker`, living proof like `RealBrokerM8Spike*Test`; results appended to `M8-00-spike-results.md` (a new section per item) and the spec "Facts" table updated; scenarios tagged `@needs-spike` in 16, 17, 18 updated.

## Design notes
Reuse `M8SpikeSupport.kt`. S-N first and time-boxed (a day); if the cause is an unsupported jump, say so and stop.

## UX
None.

## MCP
None (spike).

## Open points
The test-only dependency `com.rabbitmq:amqp-client` is approved by the owner (2026-11-09): test source sets of `data-rabbit` only, Apache-2.0 option, listed in `THIRD_PARTY_NOTICES.md` (done), check the licence in the jar's POM and the two-week age rule when adding. S-N runs first and is being run separately; its results go to `M8-00-spike-results.md`.

## Definition of done
Tests pass, results written, spec updated, no product code changed.
