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
| S-N | DONE 2026-11-09 (`M8-00-spike-results.md`, `RealBrokerM8SpikeUpgradeTest`): the loss is the classic queue index version 1, unrecoverable on 4.3+; 4.0 to 4.2 convert it. Remaining follow-ups: a 3.12 to 4.x jump, AMQP 1.0 published messages, a clustered upgrade, a volume copy, x86_64. Done 2026-11-12 (M8-13b, `RealBrokerM8SpikeUpgradeFollowUpTest`): the 4.3 release notes (documentation), `classic_queue.default_version`, MQTT-published messages, the timing of the policy-without-restart conversion | Decides report wording; done |
| S-O | DONE 2026-11-12 (M8-13a, `M8-00-spike-results.md` "S-O", `RealBrokerM8SpikeDeliveryTest`, `amqp-client` 5.37.0 test-only): exclusive consumer not enforced on quorum, 4.x default delivery limit 20 drops silently but never counts `nack`, global QoS accepted silently on 4.x. Original question: Exclusive queues and exclusive consumers, redelivery and the delivery limit (default 20 per the owner, 4.x) with real consumers, global QoS refusal. Needs an AMQP 0-9-1 client in test dependencies (`com.rabbitmq:amqp-client`, approved 2026-11-09, test-only) | Semantic warnings of the classic to quorum conversion |
| S-Q | DONE 2026-11-12 (M8-13b, `RealBrokerM8SpikeAmqpsShovelTest`, results in `M8-00-spike-results.md`): Shovel over `amqps` with the TLS fixtures (URI options), `ack-mode` other than `on-confirm` (documentation only, v1 is on-confirm), integer `src-delete-after` | TLS rule D9a for the Shovel; today only the unit test covers it |
| S-P | DONE 2026-11-12 (M8-13b, `RealBrokerM8SpikeGuestLoopbackTest`; measured with a container whose image config is not relaxed, management and AMQP only; a real non-container install was not run): `guest` loopback restriction on a plain 4.x install | `guest` finding wording |
| S-R | DONE 2026-11-12 (M8-13b, `RealBrokerM8SpikeClusterTest`, two-node 3.13 cluster): clustered 3.x export shape (mirrored queues with real mirrors, node fields) | Several of the owner's brokers may be clusters |
| S-T | DONE 2026-11-12 (M8-13a, "S-T", `RealBrokerM8SpikeInMemoryLimitTest`): accepted and echoed on 3.13 and 4.4, no effect observable through the management API. Original question: Effect of `x-max-in-memory-length/-bytes` on a 4.x quorum queue | Warning wording |
| S-S | x86_64, Windows, Podman runs of the spike tests | Platform risk |

Out: product code.

## Acceptance
- Tests tagged `broker`, living proof like `RealBrokerM8Spike*Test`; results appended to `M8-00-spike-results.md` (a new section per item) and the spec "Facts" table updated; scenarios tagged `@needs-spike` in 16, 17, 18 updated.

## Design notes
Reuse `M8SpikeSupport.kt`. S-N is done; the rest in the order of the table. Open S-N follow-ups (release notes, `default_version`, 3.12 jump, AMQP-published messages, clustered upgrade, volume copy, policy timing, x86_64) are small and may be batched with S-R and S-S.

## UX
None.

## MCP
None (spike).

## Open points
The test-only dependency `com.rabbitmq:amqp-client` is approved by the owner (2026-11-09): test source sets of `data-rabbit` only, Apache-2.0 option, listed in `THIRD_PARTY_NOTICES.md` (done), check the licence in the jar's POM and the two-week age rule when adding. S-N is done; its results are in `M8-00-spike-results.md`.

## Definition of done
Tests pass, results written, spec updated, no product code changed.
