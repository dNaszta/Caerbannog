# M9-00b spike results: dead-lettering by policy, at-least-once, a stream on either side, measured

Measured on 2026-11-16 on a real broker, Docker Desktop on an Apple-silicon Mac (arm64 image): `rabbitmq:4-management` = **RabbitMQ 4.4.0, Erlang 29.1.2** (image digest `sha256:753099a19b577c322117f793f864b3129cbba96d40317221a69d73976e3c843c`), plugins `rabbitmq_stream` and `rabbitmq_stream_management` enabled by exec, default vhost `/`, user `guest` on a throwaway Testcontainers broker. Reject and confirm use the test-only `com.rabbitmq:amqp-client` 5.37.0, the stream message is read with the test-only stream client and with the app's `RabbitMqStreamMessageReader`; the queue content is read with the management `get` call `ack_requeue_true` (the call `ManagementQueueMessageInspector` makes; the test calls it directly through `MgmtApi`, not through the class). No secret appears anywhere (the broker's default `guest`).

Living proof (tag `broker`): `RealBrokerM9SpikeDeadLetterTest` (13 tests, 524 s, S-C1 to S-C6, S-C8, S-C9) and `RealBrokerM9SpikeStreamDeadLetterTest` (7 tests, 60 s, S-C7, S-D1 to S-D3), helper `M9SpikeDlSupport.kt` (private to the two classes), fixture `data-rabbit/src/test/resources/fixtures/m9-00/s-c7-stream-message.json`. The first test of each class asserts that `rabbitmq_version` starts with `4.`. Every row uses a queue it creates itself with a fresh name; a policy shows after about 6 s (M9-00c), so every wait is a bounded poll (up to 90 s, the retry test up to 400 s); each "nothing arrived" follows a positive control. Re-run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeDeadLetterTest*'` and `'*RealBrokerM9SpikeStreamDeadLetterTest*'`.

## Read first: what contradicts or sharpens the spec and the briefs

1. **At-least-once is accepted and shown without being in effect (S-C4, SURPRISE).** `dead-letter-strategy: at-least-once` WITHOUT `overflow: reject-publish` is accepted (policy `PUT` 201, queue declare 201), `effective_policy_definition` and `arguments` show `at-least-once`, and the queue still behaves as at-most-once: a refused dead letter is dropped. Also with `overflow: drop-head`. Only with `overflow: reject-publish` does the queue hold the dead letter. A read-back (and `M9-05`'s check) cannot tell the two apart by the strategy key: it must read `overflow` too. The recipe's tick must set both keys (the spec already says so, "expected, UNVERIFIED": now measured).
2. **A held dead letter was delivered only minutes after the repair, in the observations made (S-C3, SURPRISE).** With at-least-once the source keeps a dead letter that the target cannot take, shown as `messages` = 1 with `messages_ready` = 0 and `messages_unacknowledged` = 0 (a requeue peek sees nothing). It was delivered by something that acts on a timer: in the one run of the retry test the three held messages (full target, unbound target, missing exchange) were all seen 175 s after the repair; in two throwaway probes (not kept as tests) nothing was delivered within 40 s and within 60 s, and in a third one it was seen at about 165 to 170 s. That is FOUR observations of one broker version, and the poll step of the test is not recorded: the interval is not known, may depend on load and on what the timer counts from (the rejection, the failed attempt or a fixed tick; the test measured from the repair), and the broker's own documentation was not read for it. What the observations support is only: not within one minute, within three. The wizard must not promise a quick delivery ("at least once" means "not lost", not "soon").
3. **A queue with an argument ignores a policy of the same key, and `effective_policy_definition` shows the policy anyway (S-C2, SURPRISE).** A quorum queue declared with `x-dead-letter-exchange` = A and a policy `dead-letter-exchange` = B: the dead letter went to A (the argument), and `effective_policy_definition` still says B. The recipe (A4) is for a queue without that argument; a check or a read-back that trusts `effective_policy_definition` is wrong for such a queue. Only `dead-letter-exchange` was measured against its argument.
4. **A quorum queue with `max-length` N and `overflow: reject-publish` holds N + 1 messages (S-C5, SURPRISE).** The next publish is refused. A classic queue is exact. Any text "when the queue is full" is true at N + 1 for quorum queues.
5. **A stream cannot be told to dead-letter at all (S-D1, S-D2).** The argument is refused (400 and 406), a `dead-letter-exchange` policy is accepted and never takes effect. S-D3 saw nothing leave a stream whose retention removed 2132 messages. All three agree with the sentence of the response-stream note (see the end).
6. **A stream as a dead-letter target works (S-C7)**, by policy at-most-once, at-least-once and for an expired message. The stream message carries `x-exchange` = the dead-letter exchange (not the exchange the publisher used), `x-routing-key` = the original routing key, and the death information. **SURPRISE for the Messages screen:** the app's reader puts the annotation `x-opt-deaths` (a list of maps) into `StreamMessage.properties` as a raw Java `Object[]`, so a screen that prints it shows `[Ljava.lang.Object;@...` (see S-C7).
7. **A missing dead-letter exchange is accepted silently (S-C6):** the policy `PUT` is 201, the policy shows in effect, and at-most-once drops every dead letter without a trace.

## S-C1: arrival per cause, by policy on a quorum queue that had no argument (test `S-C1 a policy with dead-letter-exchange on a queue without arguments, one row per cause`)

Setup per row: quorum queue without arguments, fanout exchange, quorum dead-letter queue bound to it, then `PUT /api/policies/%2F/{name}` (pattern anchored to the queue name, `apply-to: queues`, priority 0) with `dead-letter-exchange`; the row starts when `effective_policy_definition` shows the key (about 6 s). After the policy: `policy` = the policy name, `effective_policy_definition` = `{"dead-letter-exchange":"<exchange>"}` (plus `max-length` in that row).

| Cause | How it was made | Arrives in the dead-letter queue | `x-first-death-reason` | Extra headers on the dead letter |
| --- | --- | --- | --- | --- |
| expired | management publish with `expiration` 1000 ms | yes | `expired` | `x-death`, `x-first-death-*`, `x-last-death-*` (no `x-delivery-count`) |
| rejected without requeue | `basic.get` then `basic.reject` requeue false | yes | `rejected` | the same plus `x-acquired-count`, `x-delivery-count` |
| over the delivery limit | no `delivery-limit` set, `basic.reject` with requeue 21 times | yes, after 21 deliveries (1 + the default 20) | `delivery_limit` | the same as rejected |
| dropped by `max-length` | policy also holds `max-length` 1, default overflow, two messages | yes, the OLDEST ("first") | `maxlen` | the same as expired |

- `x-first-death-queue` is the source queue in every row; `x-first-death-exchange` is empty because the test published through the default exchange.
- A `nack` with requeue never counts toward the delivery limit on 4.4 (M8-00 S-O); the test uses `reject`.
- Meaning for `M9-09`: the sentence "rejects, expires or drops" is measured for all three, and "delivery limit" is a fourth cause (reason `delivery_limit`). **Measured.**

## S-C2: by argument, and both (test `S-C2 an argument alone dead-letters, and an argument beats a policy on the same queue`)

- Argument alone (`x-dead-letter-exchange` at declare, quorum): a rejected message arrives (`rejected`); `policy` is null. **Measured.**
- Both (argument = exchange A, policy = exchange B, A and B each with a bound quorum queue): the dead letter arrived in A's queue (argument), B's queue stayed empty; the queue's `effective_policy_definition` = `{"dead-letter-exchange":"<B>"}`, `policy` = the policy name, `arguments` = `{"x-dead-letter-exchange":"<A>","x-queue-type":"quorum"}`. **Measured with a surprise** (Read first 3): the argument applies, the read-back shows the policy. About "B stays empty": **asserted by the same test** (M9-00b-b, R2): the dead letter is in A (`inArgument == 1`), B's queue holds nothing at that moment (`inPolicy == 0`) and for a 6 s settle window, and THEN a positive control shows B's route is alive (a message published straight into B's exchange arrives in B's queue, and B holds only it); A still holds exactly one. Flipped once (`inPolicy` expected 1): fails. Still one message, one broker version.

## S-C3: at-least-once by policy, and a target that cannot take the dead letter

Tests: `S-C3 at-least-once by policy with reject-publish is accepted and takes effect, a dead letter arrives`, `S-C3 a target that refuses the dead letter, at-least-once keeps it, at-most-once loses it`, `S-C3 a target without a binding, ...`, `S-C3 a held dead letter is delivered by a slow retry once the target can take it`.

- Policy `{dead-letter-exchange, dead-letter-strategy: at-least-once, overflow: reject-publish}`: `PUT` 201; `effective_policy_definition` = all three keys with the values sent; a rejected message arrives in the dead-letter queue (`rejected`) and the source empties. **Measured.**
- A target that cannot take it, three kinds: (a) a classic target with `max-length` 1 and `reject-publish`, already holding one message; (b) the binding removed (the exchange takes the message and routes it nowhere); (c) the policy names an exchange that does not exist (S-C6).

| | at-most-once (default) | at-least-once with reject-publish |
| --- | --- | --- |
| source queue after the refusal | `messages` 0, empty | `messages` 1, `messages_ready` 0, `messages_unacknowledged` 0; a peek shows nothing |
| the dead letter | lost; after the route is repaired only a NEW dead letter arrives | held; seen in the target 175 s after the repair in the retry test (all three kinds at that second; again 175 s in the run of 2026-11-17); a new dead letter flowed meanwhile in the unbound-target test (**asserted** by test `S-C3 a target without a binding, ...`, M9-00b-b R3: after the route is back and a control dead letter `poke` arrived, the target holds exactly `[poke]`, not `held`, and the source still shows the held signature; flipped once: fails) |
| the statistics | the counter reads 0 within about 5 s | the counter reads 0 for the first 5 s, then 1 (statistics lag): wait for the signature, do not read once |

- Which field shows it: `messages` of the source minus `messages_ready` and `messages_unacknowledged` (the held message is counted but neither ready nor unacknowledged). **Measured, with the surprise of the slow retry (Read first 2, a single observation).** Not measured: what happens when the source queue is deleted, the policy is removed or the node restarts while a message is held.
- A quorum target with `max-length` 1 and `reject-publish` takes two messages (S-C5: N + 1), so the full target of these tests is classic.

## S-C4: at-least-once without reject-publish (test `S-C4 at-least-once without reject-publish, by policy and by argument, which strategy is in effect`)

| Variant | Accepted | Shown | Behaviour with a full target |
| --- | --- | --- | --- |
| policy, strategy only | `PUT` 201 | `effective_policy_definition` has `dead-letter-strategy: at-least-once` | dead letter dropped (at-most-once) |
| policy, strategy and `overflow: drop-head` | 201 | strategy and overflow shown | dropped |
| argument `x-dead-letter-strategy`, no `x-overflow` | declare 201 | `arguments` has the strategy | dropped |
| argument, `x-overflow: reject-publish` | declare 201 | `arguments` has both | held (at-least-once) |

**Measured with a surprise** (Read first 1): nothing is refused and nothing shows that the strategy is not in effect. "Held" is decided by the signature of S-C3 within 25 s; the "dropped" rows are confirmed inside the test by a control dead letter that arrives alone (positive control present). **Asserted by test `S-C4 at-least-once without reject-publish, ...`** (M9-00b-b, review finding R1): all four rows. The three "dropped" outcomes are `assertFalse` (policy strategy only; policy with `drop-head`, which must answer 201; argument without `x-overflow`, which must be accepted with 201), the `reject-publish` argument row is `assertTrue`. Each of the three new assertions was seen failing once by flipping it (outP, outD, outA); a broker that held the message in a "dropped" row would now fail the test. Run of 2026-11-17 on RabbitMQ 4.4.0: held=false, false, false, true, no difference from the run of 2026-11-16.

## S-C5: what publishers see when the queue is full (tests `S-C5 reject-publish on a full quorum queue, ...`, `S-C5 the same limit on a classic queue is exact, ...`)

- Quorum, `x-max-length` 2, `x-overflow: reject-publish`, AMQP publisher with confirms, six publishes one by one: `ack, ack, ack, nack, nack, nack`; the queue holds `m1, m2, m3` (three messages).
- The management publish: the first three answer `200 {"routed":true}`, the fourth `400 {"error":"bad_request","reason":"rejected Unable to publish message. Check queue limits."}`. After one message is taken the queue accepts a publish again (positive control).
- Classic, the same limit: `200, 200, 400, 400`, holds two.
- Default overflow (drop-head), quorum `max-length` 2, four publishes: all accepted; the queue holds **`m3, m4`**: seen in 4 of 4 runs of 2026-11-17 (1 with the old two-outcome check, 3 with the tightened check), so the test (M9-00b-b, R6) now asserts exactly `m3, m4`: a quorum queue with drop-head holds the limit, not limit + 1 (reject-publish is the one that holds N + 1). RabbitMQ 4.4.0.
- The same quorum numbers (three acks, three nacks, three messages held) with the limit set by POLICY (`max-length` 2, `overflow: reject-publish`, `apply-to: quorum_queues`): **asserted by test `S-C5 the same quorum limit set by POLICY holds three and refuses the rest`** (M9-00b-b; flipped once: fails). **Measured**, with the N + 1 surprise (Read first 4); for the text of `M9-09`: the publisher's refusal is a negative confirm (AMQP) or an HTTP 400 (management), not a silent loss.

## S-C6: a dead-letter exchange that does not exist (test `S-C6 a policy that names a dead-letter exchange that does not exist`)

- `PUT` 201; `effective_policy_definition` = `{"dead-letter-exchange":"<missing>"}`; `GET /api/exchanges/%2F/<missing>` is 404. **Measured.**
- At-most-once: the rejected message left the source and arrived nowhere. After the exchange and a binding were created, the old message did not appear and a new dead letter arrived. At-least-once (with `reject-publish`): the held signature (S-C3), a peek sees nothing, delivered by the slow retry after the exchange exists (175 s, retry test).
- Meaning for `M9-09` and `M9-05`: no error anywhere, so the recipe must create the exchange and the binding before the policy (the order in the spec is right), and the check must look the exchange up itself.

## S-C7: a stream as the dead-letter target (tests `S-C7 ...`, fixture `s-c7-stream-message.json`)

- The dead-letter exchange (fanout) bound to a stream created with `x-queue-type: stream` and `x-queue-leader-locator: balanced`: the dead letter arrives for a rejected message (at-most-once), with at-least-once plus `reject-publish` and for an expired message. The stream's management `messages` shows 1. **Measured.**
- What the stream message carries (raw stream client; one message rejected from a quorum queue):

| Place | Content |
| --- | --- |
| body | the original body |
| message annotations | `x-exchange` = the dead-letter exchange, `x-routing-key` = the original routing key (here the queue name), `x-first-death-reason` / `-queue` / `-exchange`, `x-last-death-reason` / `-queue` / `-exchange`, `x-opt-deaths` = a list with one map `{queue, reason, count, first-time, last-time, exchange, routing-keys[]}` (`ttl` too for an expired message) |
| application properties | the original headers (`order` = 42) |
| properties | `message_id` and `content_type` of the original (`messageId`, `contentType`) |

- The app's reader: `StreamMessage.exchange` = the dead-letter exchange, `routingKey` = the original routing key; the annotations come as entries of `StreamMessage.properties`, and `x-opt-deaths` is a raw `Object[]` there, so its text is `[Ljava.lang.Object;@...`. A Messages screen that prints `properties` shows an unreadable value for it. Follow-up for the architect (not product code in this task).
- The fixture pins the annotation and application property keys (the test fails with both lists if they differ). **Measured, with the display surprise.** Not measured: a direct dead-letter exchange into a stream, and a stream message of a delivery-limit or max-length cause (only rejected and expired).

## S-C8: a classic queue (test `S-C8 a classic queue with the same policy, and dead-letter-strategy on a classic queue by policy`)

- Classic queue, policy `dead-letter-exchange`: `effective_policy_definition` shows it; a rejected message arrives (`rejected`) and an expired one arrives (`expired`). **Measured.**
- Classic queue, policy `{dead-letter-exchange, dead-letter-strategy: at-least-once, overflow: reject-publish}`: `PUT` 201, but `policy` is null and `effective_policy_definition` is `{}` (the whole policy does not bind, M9-00c rule 1); a rejected message is lost and the dead-letter queue stays empty (positive control: the plain classic rig above). **Measured**: the recipe's at-least-once is not offered for classic queues.

## S-C9: a direct dead-letter exchange without `dead-letter-routing-key` (test `S-C9 ...`)

- The source is bound to `amq.direct` with `orders.created`; a message published with that key and rejected reaches the queue bound to the direct dead-letter exchange under `orders.created` (`routing_key` of the dead letter = `orders.created`, `x-death[0].exchange` = `amq.direct`, `routing-keys` = `["orders.created"]`); the queue bound under the SOURCE QUEUE's name received nothing. With `dead-letter-routing-key: dead` the dead letter carries `dead` (control). **Measured:** the dead letter keeps the routing key of the publish.

## S-D1: a stream declared with `x-dead-letter-exchange` (test `S-D1 a stream declared with x-dead-letter-exchange`)

- Management `PUT /api/queues/%2F/{stream}` with `x-queue-type: stream` (and the leader locator) plus `x-dead-letter-exchange`: **400** `{"error":"bad_request","reason":"invalid arg 'x-dead-letter-exchange' for queue '<name>' in vhost '/' of queue type rabbit_stream_queue"}`. Through AMQP `queue.declare`: **406** `PRECONDITION_FAILED - invalid arg 'x-dead-letter-exchange' for queue '<name>' ... of queue type rabbit_stream_queue`. Nothing is created. Outcome: **refused.** (Control in the test: the dead-letter exchange and queue work.)

## S-D2: a `dead-letter-exchange` policy that applies to streams (test `S-D2 a dead-letter-exchange policy for streams is accepted and stays out of effect`)

- `PUT /api/policies` with `apply-to: streams` and with `apply-to: all`, pattern matching a stream, definition `{dead-letter-exchange}`: **201** both. After the broker applied the positive control of the same batch (a `max-length-bytes` policy on a second stream, shown in effect), the target stream still has `policy` = null and `effective_policy_definition` = `{}`, watched for 8 s more. Outcome: **accepted, not in effect** (the same as the M9-00c effect matrix, measured here with `apply-to: streams`). Since M9-00b-b (R4) the two policies each match their OWN stream (two targets, plus the control stream), so the `apply-to: all` row is independent of the `streams` row; both targets stay at `policy` null and `effective_policy_definition` `{}` (flipped once: fails). Both answered 201.

## S-D3: a stream filled beyond a tiny retention (test `S-D3 retention removes messages from a stream and nothing reaches the dead-letter exchange`)

- Stream with arguments `x-max-length-bytes` 300000 and `x-stream-max-segment-size-bytes` 100000 (created: 201), a `dead-letter-exchange` policy for streams on it (201, never in effect, S-D2; an argument is refused, S-D1), a fanout dead-letter exchange with a quorum queue bound. 3000 messages of 1000 bytes sent and confirmed; the stream's first offset moved from 0 to **2132** (retention removed 2132 messages; polled until it was above 0).
- The dead-letter queue stayed empty for 10 s after that; the positive control (a message published to the dead-letter exchange) arrived. Outcome, **as observed on RabbitMQ 4.4.0 (one run, not a proof):** nothing reached the dead-letter exchange when retention removed messages from the stream. A stream with a dead-letter exchange in effect cannot be made (S-D1, S-D2), so this is the only case that exists.

## The one broker sentence of the response-stream note: do the three facts agree?

"RabbitMQ does not move messages into it by itself: your applications write to it."

| Fact | Outcome | Agrees with the sentence |
| --- | --- | --- |
| S-D1 | refused (400 and 406), not "ignored" and not "works" | yes |
| S-D2 | accepted, not in effect (stream `policy` null, `effective_policy_definition` empty) | yes |
| S-D3 | nothing arrived after retention removed 2132 messages, positive control passed | yes, as observed on 4.4.0 |

All three agree. The wording is the owner's. One caution for the wording: the sentence says nothing is moved INTO the response stream; what S-C7 shows is that a queue's dead letters CAN be routed into a stream by a binding (a different thing, the recipe of `M9-09`).

## Review of step 3 (architect, 2026-11-16): what the tests pin and what they do not

Verdict: Accepted with follow-ups. The 20 tests are honest measurements; the findings below are about claims that are worded stronger than a test asserts. The wording of this file was corrected where it said more (Read first 2, S-C2, S-C3, S-C4, S-C5, S-D2); the tests are for the test developer (brief `M9-00b-b-spike-test-hardening.md`).

- **Positive controls:** present for every "nothing arrived" of S-C1, S-C3 (at-most-once branch: `a` and `c` arrive), S-C6 (`later` arrives, dlq equals exactly `["later"]`), S-C8 (the plain classic rig of the same batch), S-C9 (control with `dead-letter-routing-key`), S-D1 (control publish into the dead-letter exchange), S-D2 (retention policy on a second stream in effect), S-D3 (control after the wait). Missing or weak: S-C2 (R2), S-C3 unbound (R3: the held message in the target is not asserted), S-C4 (R1: three outcomes not asserted).
- **S-D3 reads the queue content, not a time window:** the dead-letter queue is peeked after retention removed 2132 messages, so a dead letter produced at any earlier time would be there. The 10 s settle is therefore not what the claim rests on. The claim is still weak by nature: a stream with a dead-letter exchange in effect cannot be made (S-D1, S-D2), so S-D3 only shows that retention does not publish anything to an exchange by itself on 4.4.0.
- **Safety rule (CLAUDE.md "Queues: the app's own process never gets, consumes, acknowledges, rejects, deletes or purges queue messages"):** the rule is about the app. The spike's consumption (`basicGet`, `basicReject`, `drain` through `MgmtApi`) is test code in `src/test` on a throwaway Testcontainers broker with the built-in `guest` user, never in `src/main`, behind no product interface; it adds no read path to the app and no tool. ACCEPTED, with two conditions that hold today: nothing in this task is imported by production code, and these helpers stay private to the spike classes. The brief said the queue content would be read with `ManagementQueueMessageInspector`; the tests call the same management `get` (`ack_requeue_true`) through `MgmtApi` instead (as the M8 spikes do). That is a deviation from the brief, not a rule breach: it means the tests prove the broker's answer to that call, not the class. Nothing about the class's own parsing is claimed here.
- **Runtime:** `RealBrokerM9SpikeDeadLetterTest` 524 s, of which the retry test 204 s; the stream class 60 s. Decision: the tests STAY in the `broker` tag and in one class each, with no new tag or exclusion: the fast tasks exclude `broker` already (`data-rabbit/build.gradle.kts`), `brokerTest` has 40 minutes and the 30 s SLOW TEST line names the retry test, so a reader sees why. They cost about ten minutes of a broker run on main; if `brokerTest` ever gets within 30 minutes, the test developer moves `S-C3 a held dead letter is delivered by a slow retry` into its own class (own container) and excludes it from the default run behind a tag, because it only guards a "do not promise quick delivery" sentence. Nothing to do now. (Not measured: the total `brokerTest` time on main; I did not run it.)

Step 2 of `M9-00b-b` (test developer, 2026-11-17): R1, R2, R3, R4 and the S-C5 rows of R6 are now asserted (see the rows above); no claim was disproved, every outcome matched the run of 2026-11-16. R5 (slow retry): recorded in a comment of the retry test, not asserted. The retry test measures from the repair and polls the three targets with one management `get` each per loop (a few hundred ms), so `seenAt` has about one second of resolution; it prints `M9SPIKE S-C3 retry of a held dead letter: seenAt seconds ...`. Observations of the retry to date: 175 s (test, 2026-11-16), 175 s (test, 2026-11-17), about 165 to 170 s (probe), nothing within 40 s and 60 s (two probes): consistent with a timer of a few minutes, not proven.

## Not measured

- Dead-lettering by an operator policy; `dead-letter-routing-key` against its argument; any key other than `dead-letter-exchange` for argument against policy.
- A held at-least-once dead letter when the source queue is deleted, the policy removed, or the broker restarted; the retry interval (the test twice at 175 s, one probe at 165 to 170 s, two probes with nothing within 40 s and 60 s; what the timer counts from; under other loads).
- A stream message for the delivery-limit and `max-length` causes; a direct dead-letter exchange into a stream; a super stream.
- Reject-publish set by policy on the dead-letter side of S-C5 (only the SOURCE side is kept as a test now; the dead-letter side with a policy-set limit is not).
- RabbitMQ 3.x and any version other than 4.4.0; Docker on x86-64.
