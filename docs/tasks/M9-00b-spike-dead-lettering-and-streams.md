# M9-00b: Spike on a live 4.x broker: dead-lettering by policy, at-least-once, and streams

Milestone: M9    Depends on: the owner's review of the M9 plan (nothing else)    Size: M

PLANNED 2026-11-15. Not started. Runs in parallel with `M9-00a` and `M9-00c`.

## Goal
The items S-C and S-D of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured on a real RabbitMQ 4.x broker, so that the dead-lettering recipe (`M9-09`) and the explaining text of the response stream (`M9-07`) are built on facts.

## Why
The architect decided dead-lettering by policy (A4) and described the response stream (DLN) as a convention of the applications. Both rest on broker behaviour that this project has not measured: M8-00 measured which arguments and policy keys the broker accepts, and S-O saw `x-first-death-reason: delivery_limit` with a real consumer, but nothing measured dead-lettering set by a policy on an existing queue, at-least-once, or a stream on either side.

## Scope
In: `@broker` spike tests in `data-rabbit` (tag `broker`, Testcontainers, `rabbitmq:4-management`), fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the shared results file `docs/tasks/M9-00-spike-results.md`, spec and scenario corrections.

| Item | Question | Output | Used by |
| --- | --- | --- | --- |
| S-C1 | A quorum queue that exists without any argument gets a policy with `dead-letter-exchange` (fanout exchange, bound to a quorum dead-letter queue). A message that expires (per-message `expiration`, published through the management API by the test), one that is rejected without requeue, one over the delivery limit and one dropped by `max-length` (default overflow): which of them arrive in the dead-letter queue | arrival table per cause; `effective_policy_definition` and `policy` of the queue | `M9-09` (the summary sentence "rejects, expires or drops") |
| S-C2 | The same by argument (`x-dead-letter-exchange` at declare), and a queue that has both an argument and a policy: which one applies | note | `M9-09`, spec A4 |
| S-C3 | At-least-once by policy: `dead-letter-strategy: at-least-once` with `overflow: reject-publish`: is the `PUT` accepted, what `effective_policy_definition` shows, does a dead letter arrive. Then with the dead-letter target unable to take it (the binding removed by the test): is the message kept in the source queue (which field shows it) where at-most-once loses it | table at-most-once against at-least-once | `M9-09` (whether "At least once" is offered) |
| S-C4 | At-least-once without `overflow: reject-publish`, by policy and by argument: accepted or refused, and which strategy is in effect | note | `M9-09` (the required tick) |
| S-C5 | What publishers see with `overflow: reject-publish` when the queue is full (a management publish answer, an AMQP publisher confirm with the test-only `amqp-client`) | note | `M9-09` (the text "Publishers are refused when the queue is full") |
| S-C6 | A policy that names a dead-letter exchange that does not exist: is the `PUT` accepted, what happens to a dead letter | note | `M9-09` (the order of the four steps), `M9-05` |
| S-C7 | A stream as the dead-letter target: the dead-letter exchange bound to a stream; do dead letters arrive (management `messages`), what the stream message carries (annotations, death information; read with the app's stream reader), with at-most-once and, if S-C3 works, at-least-once | fixture of one stream message | `M9-09` (Q12), `M9-07` |
| S-C8 | A classic queue with the same policy: at-most-once works; `dead-letter-strategy` on a classic queue by policy | note | `M9-09` |
| S-C9 | A direct dead-letter exchange without `dead-letter-routing-key`: which routing key does the dead letter carry | note | later (direct dead-letter exchange) |
| S-D1 | A stream declared with `x-dead-letter-exchange` (management `PUT` with `x-queue-type: stream`): accepted, ignored or refused | status | `M9-07` |
| S-D2 | A policy with `dead-letter-exchange` that applies to `streams` or `all` and matches a stream: is the `PUT` accepted, does the stream's `effective_policy_definition` show the key | note | `M9-07`, `M9-08` |
| S-D3 | A stream with a tiny retention (`max-length-bytes` and segment size) and such a policy, filled beyond its retention: does anything arrive at the dead-letter exchange (positive control afterwards) | observation, stated as observed, not as a proof | `M9-07` (the sentence "RabbitMQ does not move messages into it by itself") |

Out: product code; Shovel; anything that makes the app itself consume queue messages (the tests may use the test-only `amqp-client` and the app's requeue peek).

## Acceptance
- BDD: none run by this task. It unblocks the `@needs-spike` scenarios of `tests/bdd/features/21_topology_planner_recipes.feature` tagged `@m9-09` ("Dead-lettering for a queue adds four steps, the policy last", "Dead letters can go to a stream", "At least once needs my tick and says what it changes", "At most once is the default and adds no strategy setting", "At least once is not offered for a classic queue", "A queue that already has a policy is pointed out before the recipe is added") and `@m9-07` ("The wizard says what a response stream is"), and the dead-letter part of the gate scenario.
- Spike test classes `RealBrokerM9SpikeDeadLetterTest` and `RealBrokerM9SpikeStreamDeadLetterTest` assert the findings.
- `docs/spec.md`: rows S-C and S-D replaced by the measured facts; the sections "Dead-lettering for a queue" and "Streams and the response stream" lose every "UNVERIFIED" that was measured and keep the ones that were not.

## Design notes
Two helpers exist: the test-only `com.rabbitmq:amqp-client` (approved 2026-11-09, version 5.37.0 in `data-rabbit/build.gradle.kts`, used by `RealBrokerM8SpikeDeliveryTest`) for reject and confirms, and the management publish for expiring messages. Counts come from the management API by bounded polling (the statistics lag up to 5 s); message content of a quorum queue is read with `ManagementQueueMessageInspector` (requeue peek), of a stream with the app's stream reader. Record the floated image version. A negative ("nothing arrived") always has a positive control.

## UX
None.

## MCP
None (spike).

## Open points
- If S-C3 shows that at-least-once cannot be set by policy, `M9-09` ships without it and the question Q12 loses that part; the architect updates the spec and the mockup (the "How sure" block goes).
- If S-D2 or S-D3 shows that the broker does dead-letter out of a stream in some case, the description of the response stream changes and the owner is asked again (O9 stays the owner's definition; only the explaining sentence follows the measurement).

## Definition of done
Spike tests pass on a machine with Docker or rootless Podman; fixtures committed; results written; spec, scenarios and the briefs `M9-07`, `M9-09` corrected by the architect; no product code changed.
