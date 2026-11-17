# M9-14c spike results: what the stream settings do on the broker, measured

Measured on 2026-11-17 on a throwaway Testcontainers broker, Docker Desktop on an Apple-silicon Mac (the test records the host JVM arch `aarch64`; that the image is arm64 is presumed, not recorded): `rabbitmq:4-management` = **RabbitMQ 4.4.0**, Erlang **29.1.2**, management plugin 4.4.0, plugins `rabbitmq_stream`, `rabbitmq_stream_management`. Stream client `com.rabbitmq:stream-client:1.12.0`. The tag floats: a newer 4.x that answers differently fails the class on purpose. Throwaway user `guest` of the container; no secret is in any fixture.

Living proof (tag `broker`): `RealBrokerM9StreamArgumentsTest` (7 tests: S0 to S6), run with `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9StreamArgumentsTest*'` (about 2 minutes: S4 waits up to 60 s for a first offset that must NOT move in one case). Status at step 2a: **7 of 7 pass**. Every measured value is pinned in the class (`PINNED_FACTS`): a changed answer fails its row and prints the new text. Raw answers: `data-rabbit/src/test/resources/fixtures/m9-14c/` (written when `RECORD_M9_14C_FIXTURES` is set); facts: `data-rabbit/build/m9-14c-facts.txt`. Route T = the typed creator methods; route R = the raw `StreamCreator.argument(String, String)` (it exists in 1.12.0). The management read is `GET /api/queues/{vhost}/{name}`. Positive control in every row: a stream made by plain `create()` shows `x-queue-leader-locator: balanced` and `x-queue-type: stream` (asserted at the start of S1 to S6).

## Read first: surprises

1. **The raw route takes the stream protocol's names, NOT the `x-` names, and a wrong name is silently ignored.** `argument("max-age", "7D")` gives `x-max-age: "7D"`; `argument("x-max-age", "7D")` is ACCEPTED and NOT SHOWN (the stream shows only the default arguments; "does nothing" is the reading of that, no behavioural test of an `x-` name was made). The same for `max-length-bytes`, `stream-max-segment-size-bytes`, `queue-leader-locator`. The code that builds the create call must use `max-age`, `max-length-bytes`, `stream-max-segment-size-bytes`, `queue-leader-locator`; a test that only checks "accepted" would pass with the wrong name. Not a stop condition (see S-14c-1), but the brief's "nothing is asserted about the stream protocol's argument names" is now answered.
2. **STOP conditions: none fired.** Route R keeps `7D`; a repeat never altered a stream (7 of 7 unchanged, asserted); `plain create()` and `leaderLocator(BALANCED)` show equal arguments (asserted).
3. **Route T shows `604800s` for 7 days, route R shows `7D`; the broker nevertheless treats them as the same retention** (a repeat with route T `maxAge(7 days)` on a stream made with R `7D` is accepted and changes nothing; how the broker compares, parsed value or other, was not measured). So the matcher, which compares typed text with what the broker shows, must create with route R (text as written) to read back what was declared.
4. **The broker does not check the shape of `x-max-age` at create.** Route R took `7D`, `7d`, `1Y`, `10m`, `0s`, `7W`, `P7D`, `604800s`, `1h`, `7` and the empty text, each shown back verbatim. The shape rule is therefore ours alone (the brief said so); whether a broker later fails on such a value was not measured.
5. **The leader locator is checked, case-sensitively:** `balanced`, `client-local`, `random`, `least-leaders` are taken; `nearest`, `Balanced`, the empty text and `7` are refused with stream response code 17. The typed enum `LeaderLocator` of 1.12.0 offers only `client-local` and `balanced`; `random` and `least-leaders` exist only on route R.
6. **A repeated create answers by comparison:** equal settings are accepted (a no-op), different settings are refused with code 17, and nothing changes in either case.
7. **Retention by policy beats the argument in both directions measured** (S-14c-4); only M9-08b needs it.

## S-14c-1 keys and types: measured, with surprise 1 (end state `surprise`, no stop)

| Procedure | Observed (`arguments` after the create) |
| --- | --- |
| T `maxAge(7 days)` | accepted; `x-max-age` TEXT `604800s` |
| T `maxLengthBytes(1 GB)` | accepted; `x-max-length-bytes` NUMBER `1000000000` |
| T `maxSegmentSizeBytes(100 MB)` | accepted; `x-stream-max-segment-size-bytes` NUMBER `100000000` |
| T `leaderLocator(CLIENT_LOCAL)` / `(BALANCED)` | accepted; `x-queue-leader-locator` TEXT `client-local` / `balanced` |
| R `max-age` `7D` | accepted; `x-max-age` TEXT `7D` (kept) |
| R `max-length-bytes` `1000000000` | accepted; NUMBER `1000000000` (a number although sent as text) |
| R `stream-max-segment-size-bytes` `100000000` | accepted; NUMBER `100000000` |
| R `queue-leader-locator` `client-local` | accepted; TEXT `client-local` |
| R `x-max-age`, `x-max-length-bytes`, `x-stream-max-segment-size-bytes`, `x-queue-leader-locator`, `x-foo` (with the `x-` prefix) | accepted; NOTHING shown (only `balanced` and `stream`): not shown, taken as ignored |
| R `dead-letter-exchange` `dlx`, `queue-type` `stream` or `quorum`, `foo` `bar` | accepted; nothing shown, the stream stays a stream: not shown, taken as ignored |
| R `initial-cluster-size` `1` | accepted; `x-initial-cluster-size` NUMBER `1` (a setting the file does not carry; the closed set of four is the product's choice, the broker takes more) |

Refusals: none in this row (every create answered success; the exception type and code are recorded for refusals in S-14c-2, -3, -6). Sentence the unit tests pin: the stream manager sends `max-age` (text as written), `max-length-bytes` and `stream-max-segment-size-bytes` (numbers as text) and `queue-leader-locator`; the management read shows `x-max-age` as the same text and the other two as numbers.

## S-14c-2 leader locator values: measured

Raw route, name `queue-leader-locator`. Accepted: `balanced` (control), `client-local`, `random`, `least-leaders` (each shown back as sent). Refused, `StreamException` code 17, nothing created (read answers 404): `nearest`, `Balanced`, the empty text, `7`. Typed enum values: `CLIENT_LOCAL=client-local`, `BALANCED=balanced`. Sentence: the locator rule takes exactly `balanced`, `client-local`, `random`, `least-leaders` (case-sensitive) on 4.4.0.

## S-14c-3 repeated create: measured (no stop)

Stream `A` made with R `max-age 7D` and `queue-leader-locator balanced` (control: shown `7D`, `balanced`, `stream`), then repeated:

| Repeat | Answer | `arguments` after |
| --- | --- | --- |
| (a) same settings | accepted | unchanged |
| (b) `max-age 1D` + balanced | refused, code 17 | unchanged |
| (c) no settings at all | refused, code 17 | unchanged |
| (d) only `max-age 7D` (the held `balanced` is the default) | accepted | unchanged |
| (e) `max-age 7D` + `client-local` | refused, code 17 | unchanged |
| (f) `max-age 7D` + balanced + `max-length-bytes` (superset) | refused, code 17 | unchanged |
| (g) route T `maxAge(7 days)` (same retention, other text) | accepted | unchanged (`7D` stays) |

Control: a stream create over an existing quorum queue of the same name answers code 17 and the queue stays a quorum queue. Sentence: a repeat with equal settings (the only subset measured leaves out the locator, which the default fills in, repeat (d); leaving out a held retention is refused, repeat (c)) is Success and changes nothing; any other repeat is refused with code 17 and changes nothing. Not measured: a repeat with settings on a stream that was made WITHOUT settings (one earlier discovery run showed code 17 for route T `maxAge(7 days)` on a plain stream; no test method asserts it, so it is not a claim).

## S-14c-4 retention by argument and by policy: measured (only M9-08b)

Segment size 500,000 as argument in all cases, 2,500 messages of 2,000 bytes (about 5 MB), bounded 60 s wait on `queryStreamStats(name).firstOffset()`.

| Case | `arguments` / `effective_policy_definition` | First offset moved |
| --- | --- | --- |
| control: argument `max-length-bytes` 1,000,000, no policy | `x-max-length-bytes` 1000000 / null | yes (positive control for the harness) |
| 1: argument 1,000,000, policy `max-length-bytes` 10,000,000,000 | argument still 1000000 / `{"max-length-bytes":10000000000}` | NO within 60 s (policy wins: the large policy kept everything) |
| 2: argument 10,000,000,000, policy `max-length-bytes` 1,000,000 | argument 10000000000 / `{"max-length-bytes":1000000}` | yes (policy wins again) |

Sentence: with an argument and a policy both setting the max length, the policy decides, in both directions measured; the arguments show the declared value regardless. Case 1 is "did not move in 60 s while the control moved", which is `measured` here because the positive control proves the harness moves; not a proof of "never". The build does not read `effective_policy_definition`.

## S-14c-5 default locator: measured

Plain `create()` and `leaderLocator(BALANCED)` show EQUAL arguments (`x-queue-leader-locator: balanced`, `x-queue-type: stream`; asserted). So `balanced` is the default on 4.4.0 and writing it always is safe (Q-14c-1); not measured on 4.0 to 4.3 (floating tag, one version).

## S-14c-6 value shapes and bounds: measured, with surprise 4

Route R unless marked T; `shown` is what `arguments` held.

- `x-max-age` (R): all of `7D`, `7d`, `1Y`, `10m`, `0s`, `7W`, `P7D`, `604800s`, `1h`, `7`, the empty text: accepted and shown verbatim as TEXT. T: 7 days shows `604800s`; 1 second `1s`; 1.5 seconds truncated to `1s`; `Duration.ZERO` refused by the client before sending (`IllegalArgumentException`, nothing created).
- `x-max-length-bytes` (R): `0`, `1`, `9223372036854775807`, `-1` accepted, shown as NUMBERS (0 and -1 included); `1.5`, `1GB`, `abc` refused (`StreamException` code -1, nothing created; the value -1 is what the exception carries when no response code exists, I did not check where it is thrown). T with 0, 1, `Long.MAX_VALUE`, -1 shows the same numbers.
- `x-stream-max-segment-size-bytes` (R): `0`, `1`, `3000000000`, `-1` accepted and shown as NUMBERS; `4000000000` refused by the broker (code 17); `1.5` refused (code -1). T: `1` and `3000000000` shown; `0` accepted but NOT shown (the typed route drops it; the raw route shows `0`); `4000000000` refused by the client before sending (`IllegalArgumentException`).

Sentences: the shape rule is text for `x-max-age`, a whole number for the other two (a decimal or a unit text is refused by the stream client side); the broker's own bounds (a segment size above 3,000,000,000 is code 17) are an apply-time refusal; the broker does not judge the `x-max-age` text at create, so only our rule does.

## Not run, not measured

- Only one broker version (4.4.0, aarch64); no 4.0 to 4.3, no 3.x (a 3.x stream is not a target of this task).
- A cluster (leader placement of `random`, `least-leaders`, `client-local`: only the shown text is measured, not where the leader went).
- Whether a stream with an odd `x-max-age` (`''`, `7`, `0s`) works later; whether a policy `max-age` also beats the argument (S-14c-4 measured `max-length-bytes` only); whether the stream protocol's other arguments (`initial-cluster-size` and so on) matter (out of scope).
- TLS stream connection: the class uses the plain stream port on a throwaway container (the product's TLS rule is untouched and not under test here).
