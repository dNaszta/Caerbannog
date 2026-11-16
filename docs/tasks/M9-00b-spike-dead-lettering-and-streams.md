# M9-00b: Spike on a live 4.x broker: dead-lettering by policy, at-least-once, and streams

Milestone: M9    Depends on: none (the owner answered the open questions on 2026-11-15)    Size: M

PLANNED 2026-11-15, confirmed for the live run 2026-11-16 (a container runtime exists now). Runs in parallel with `M9-00a` and `M9-00c`; see "How the spike is run".

## Goal
The items S-C and S-D of `docs/spec.md` "Topology planner (M9)", "Facts to verify first" are measured on a real RabbitMQ 4.x broker, so that the dead-lettering recipe (`M9-09`) and the explaining text of the response stream (`M9-07`) are built on facts.

## Why
The architect decided dead-lettering by policy (A4) and described the response stream (DLN) as a convention of the applications. Both rest on broker behaviour that this project has not measured: M8-00 measured which arguments and policy keys the broker accepts, and S-O saw `x-first-death-reason: delivery_limit` with a real consumer, but nothing measured dead-lettering set by a policy on an existing queue, at-least-once, or a stream on either side.

## Scope
In: `@broker` spike tests in `data-rabbit` (tag `broker`, Testcontainers, `rabbitmq:4-management`), fixtures in `data-rabbit/src/test/resources/fixtures/m9-00/`, the results file `docs/tasks/M9-00b-spike-results.md` (one file per spike), spec and scenario corrections.

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
- BDD: none run by this task. It unblocks the `@needs-spike` scenarios of `tests/bdd/features/21_topology_planner_recipes.feature` tagged `@m9-09` ("Dead-lettering for a queue adds four steps, the policy last", "Dead letters can go to a stream", "At least once needs my tick and says what it changes", "At most once is the default and adds no strategy setting", "At least once is not offered for a classic queue", "A queue that already has a policy is pointed out before the recipe is added") and the S-D sentence of `@m9-07` (the scenario "The wizard says what a response stream is" no longer waits: reworded neutrally 2026-11-16), and the dead-letter part of the gate scenario.
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

## How the spike is run (added at step 1, 2026-11-16)
- **Broker image:** the literal `rabbitmq:4-management` (no image constant exists; every live test writes the literal). `brokerWithPlugins(image, "rabbitmq_stream", "rabbitmq_stream_management")`, `MgmtApi` (`publish`, `peek`, `declareQueue`) and `awaitUntil` of `M8SpikeSupport.kt` are reused; the test-only `amqp-client` (5.37.0, approved 2026-11-09) and `RealBrokerM8SpikeDeliveryTest` show the reject and confirm pattern. The first test of each class asserts that `rabbitmq_version` starts with `4.`; the results file records the exact RabbitMQ and Erlang version, the architecture and the date.
- **Throwaway brokers only.** One container per class; each row of a table uses a queue it creates itself (a fresh name), so one row cannot influence the next. The test may consume, reject and delete on its own throwaway container with the test-only `amqp-client`; that is test code only, never in `src/main`, and it does not touch the rule that the app's own process never gets, consumes or purges queue messages (the app's `ManagementQueueMessageInspector` requeue peek is used where the test wants to see queue content as the app would).
- **Statistics lag:** counts come from the management API by bounded polling (`awaitUntil`, up to 5 s lag); never a bare sleep. "Nothing arrived" always follows a positive control.
- **Resources:** one container, light. Helpers stay private to the new test files (prefix `M9SpikeDl...`), fixtures are named `s-c1-...`, `s-d1-...` in `data-rabbit/src/test/resources/fixtures/m9-00/`.
- **Every item ends in one of three states** in the results file: measured (test method named), not measurable (why), measured with a surprise (what differs from the spec and which brief or scenario it contradicts).

## What the follow-on tasks may rely on (the contract of this spike)
- `M9-09` may rely on: the arrival table per cause (S-C1: expired, rejected, delivery limit, `max-length` drop), the policy-against-argument rule (S-C2, and with it A4), whether at-least-once can be set by policy and what it needs (S-C3, S-C4), what publishers see (S-C5), the missing dead-letter exchange (S-C6), the stream target (S-C7), the classic queue (S-C8).
- `M9-08` may rely on: S-D2 (does a `dead-letter-exchange` policy that applies to a stream take effect, or is it only accepted).
- `M9-05` and the check may rely on: S-C6 (a policy that names a missing exchange).

### The one broker sentence of the response-stream note (M9-07 follow-up, for the OWNER's wording)
`M9-07` shipped `RESPONSE_STREAM_NOTE` worded neutrally ("A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them."). The mockup had one more sentence about the broker: "RabbitMQ does not move messages into it by itself: your applications write to it." It may be added after this spike, ONLY if these three facts are measured as the sentence says (this task lists them; it does not write the wording, the owner does, and no code or text changes in this task):
1. **S-D1** a stream declared with `x-dead-letter-exchange`: the status and what the stream's `arguments` show (accepted and ignored, accepted and stored, or refused). The sentence is safe only if the answer is "ignored or refused", not "works".
2. **S-D2** a `dead-letter-exchange` policy that applies to streams: accepted or refused, and whether the stream's `effective_policy_definition` shows the key. If it takes effect on a stream, the sentence is wrong.
3. **S-D3** a stream filled beyond a tiny retention with such a policy: nothing arrives at the dead-letter exchange, with a positive control after it, stated as observed on this version, not as a proof.
If S-D1 to S-D3 do not all agree with the sentence, the note stays neutral and the architect tells the owner what the broker really does.

## Product questions
None. (The sentence wording is the owner's, but it is a follow-up after the run, not a decision this task needs.)

## Status
- Step 1 (architect, 2026-11-16, round 0, branch `m9-00b-spike-dead-lettering-and-streams`, base main `652c42a`): brief checked against the spec ("Facts to verify first" S-C and S-D, A4, Q12), the M9-07 note, and the code (`M8SpikeSupport.kt`, `RealBrokerM8SpikeDeliveryTest`, `RabbitContainers.kt`). Brief CHANGED: results go to `M9-00b-spike-results.md` (one file per spike); section "How the spike is run" (image literal, version recording, throwaway brokers, test-only consume and reject, fresh queue per row, bounded polling, private helpers, fixture names); section "What the follow-on tasks may rely on" including the three facts S-D1 to S-D3 that the one broker sentence of the response-stream note needs (listed for the owner, wording not touched); `M9-07` no longer listed as waiting. No scenario or spec change before the run. No open product question. Next: step 2 (test developer).
- Step 2 (test developer, 2026-11-16, round 0, branch `m9-00b-spike-dead-lettering-and-streams`): DONE. `main` (24ebd09) merged in first. Live spike written and run on `rabbitmq:4-management` = RabbitMQ 4.4.0, Erlang 29.1.2, arm64: `RealBrokerM9SpikeDeadLetterTest` (13 tests, 524 s, pass) and `RealBrokerM9SpikeStreamDeadLetterTest` (7 tests, 60 s, pass), helper `M9SpikeDlSupport.kt`, fixture `s-c7-stream-message.json`. Every item is measured (results in `docs/tasks/M9-00b-spike-results.md`, "Read first" lists the surprises: at-least-once accepted but not in effect without `reject-publish`, a held dead letter retried only after about 175 s, an argument beats a policy while `effective_policy_definition` shows the policy, quorum `reject-publish` holds N + 1, `x-opt-deaths` shown as `Object[]` by the app's reader). S-D1 refused, S-D2 accepted and not in effect, S-D3 nothing arrived: all three agree with the response-stream sentence. No product code, spec or scenario changed. Next: step 3 (architect: spec rows S-C and S-D, briefs `M9-07`, `M9-09`, scenarios).
