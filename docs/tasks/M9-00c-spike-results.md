# M9-00c spike results: policy keys, one policy per queue, declare equivalence, names, measured

Measured on 2026-11-16 on a real broker, Docker Desktop on an Apple-silicon Mac (arm64 image): `rabbitmq:4-management` = **RabbitMQ 4.4.0, Erlang 29.1.2** (image digest `sha256:753099a19b577c322117f793f864b3129cbba96d40317221a69d73976e3c843c`), plugins `rabbitmq_stream` and `rabbitmq_stream_management` enabled by exec, plain HTTP (throwaway container, guest user). The tag `4-management` floats, so a newer 4.x may answer differently; the tests fail on purpose when it does (each class starts by asserting `rabbitmq_version` begins with `4.`).

Living proof (tag `broker`): `RealBrokerM9SpikePolicyKeysTest` (12 tests, S-F), `RealBrokerM9SpikeDeclareEquivalenceTest` (9 tests, S-G), `RealBrokerM9SpikeNamesTest` (4 tests, S-H), helper `M9SpikeCSupport.kt`, shared constants `PolicyKeyMatrix` and `DeclareEquivalenceTable` (core-testing, data only). Run one class at a time: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikePolicyKeysTest*'`. Raw answers: `data-rabbit/src/test/resources/fixtures/m9-00/` (`s-f1-...`, `s-f2a-...`, `s-g4-...`, `s-h-...`). Status: all 25 pass. End states: every item below is **measured** unless marked SURPRISE or NOT MEASURABLE.

## Read first: what contradicts or sharpens the spec, the briefs and the code

1. **A policy takes effect on a queue only if EVERY key of its definition is a key that queue's type honours.** One key the type ignores and the policy is bound to nothing: the queue's `policy` stays empty, even for the valid keys in the same definition (S-F1b). Example: `{max-length, max-age}` on a quorum queue: nothing applies; `{max-length, delivery-limit}` on a classic queue: nothing; `{max-length-bytes, max-length}` on a stream: nothing. The PUT of such a policy still answers 201 (S-F1a). SURPRISE for `M9-08`: "offers only settings that work" must be decided per policy (all keys), not per key, and a policy for `apply-to: all` must be split per type.
2. **Only ONE policy applies to a queue; nothing is merged** (S-F2a). Highest priority wins; the keys of the lower one are lost (A4 is confirmed, drop "UNVERIFIED"). A higher-priority policy whose keys the type ignores (rule 1) or whose `apply-to` does not match does NOT hide a lower one. With equal priority exactly one applies and **which one is not predictable** (12 probes: neither last-written nor first-by-name wins); the plan must warn for equal priority as "one of them, unpredictable" (`M9-08`, `M9-09`).
3. **A policy takes about 6 s to show on a queue** (all types, quorum included); a first look after 4 to 9 s is often empty. Every wait must be a bounded poll (up to 90 s in the tests).
4. **A repeated declare compares only the arguments the queue type honours; any other argument is ignored** and a 204 does not store it (S-G1, S-G2). That is a measured fact, and it is wider than `DeclaredResourceMatcher` assumes today (it requires every DECLARED argument to be present and equal and ignores what the broker has in addition): the broker also ignores arguments the DECLARE adds or changes when its type does not honour them. See S-G1 for the sets (they hold for the 20 candidate arguments the test tried, not for every argument RabbitMQ knows). `M9-05b` uses these sets for ONE gap of the matcher (an honoured argument on the broker that the declare lacks reads as the same today and the broker answers 400); a declared argument that the type ignores still makes the apply stop at its read-back, so the check keeps saying "different" for it (see the spec rule).
5. **A stream created by the product (stream manager) carries the argument `x-queue-leader-locator: balanced`** (the stream client's default; `{"x-queue-leader-locator":"balanced","x-queue-type":"stream"}`). A queue `PUT` of type stream without it is a 400 `inequivalent arg 'x-queue-leader-locator'`. Any check or read-back that builds the arguments of a stream must expect it (S-G3).
6. **Retention set by a policy on a stream does not appear in the stream's `arguments`** (S-F1d): the stream list reads `arguments.x-max-age` and shows an empty Retention column for such a stream. Follow-up for the stream list (read `effective_policy_definition` too) or a note in the wizard (`M9-08` Q4).
7. **`GET /api/queues/{vhost}/.` and `.../..` (and the same for exchanges) answer `200` with a JSON LIST** (the vhost listing), not 404: the broker normalises the dot segments away, `%2E` or raw alike, and so for the empty name. A check that treats `200` as "exists" wrongly finds a queue named `.` or `..`. Declaring either through the management API is `405`, but the stream protocol CREATES streams named `.` and `..` (Success), and then the management API cannot see or address them (S-H). The fake answering "Absent" is wrong for both answers.
8. **The stream protocol has no 255-byte limit**: a stream of 256 bytes (ASCII or multi-byte) is created, and a policy or an exchange of 256 bytes is accepted (longer names were not measured), while a queue of 256 bytes is a 400. A stream of 256 bytes is read back fine by the management GET (S-H). So "names longer than 255 bytes are refused" is true for queues only.

## S-F1a: accept matrix (`PolicyKeyMatrix`, test `S-F1a every key is accepted for every apply-to...`)

- **Method:** `PUT /api/policies/%2F/{name}` with one key and one sample value, for 16 key/value rows (overflow with its three values) and the six `apply-to` (`queues`, `quorum_queues`, `classic_queues`, `streams`, `exchanges`, `all`), pattern that matches nothing: 96 requests.
- **Observed:** all 96 answered **201**. The key does not have to fit the `apply-to` (`max-age` on `exchanges`, `max-length` on `streams`: 201). Control: an unknown key (`no-such-key`) is `400 ... are not recognised policy settings`.
- **Meaning:** the PUT status tells nothing about whether a setting works; use S-F1b.

## S-F1b: effect matrix (`PolicyKeyMatrix.effect`, tests `S-F1b ...`)

A queue per (type, key), one policy per queue with only that key, `apply-to: all`; the key shows in `effective_policy_definition` or it does not.

| Key | quorum | classic | stream |
| --- | --- | --- | --- |
| `max-length` | yes | yes | no |
| `max-length-bytes` | yes | yes | yes |
| `overflow` (`drop-head`, `reject-publish`, `reject-publish-dlx`) | yes, all three | yes, all three | no |
| `message-ttl`, `expires` | yes | yes | no |
| `dead-letter-exchange`, `dead-letter-routing-key` | yes | yes | no |
| `dead-letter-strategy` | yes | no | no |
| `delivery-limit` | yes | no | no |
| `queue-leader-locator` | yes | yes | yes |
| `target-group-size` | yes | no | no |
| `max-age`, `stream-max-segment-size-bytes` | no | no | yes |
| `alternate-exchange` | yes | yes | yes |

- "no" means: `policy` and `effective_policy_definition` of the queue stay empty (positive control in the same batch: the "yes" rows show after about 6 s, then 8 more seconds are watched).
- **Exchanges (SURPRISE):** an exchange's GET has `policy` set for EVERY key above (even `max-age`) but no `effective_policy_definition` field at all, so the effect on an exchange cannot be read back; only `alternate-exchange` is meant for exchanges. Test `S-F1b on an exchange ...`.
- **Whole-policy rule:** see "Read first" 1 (tests `S-F1b a policy with one key the queue type ignores takes no effect at all`: five definitions, three bound to nothing, two fully in effect).

## S-F1c: wrong values (`PolicyKeyMatrix.wrongTypeRows`, 22 rows, test `S-F1c ...`)

- **Refused with 400 `Validation failed ... is not a valid ...`, nothing stored (GET of the policy 404):** text or number-as-text for `max-length`, negative or decimal `max-length`, text or boolean `message-ttl`, `expires: 0`, number or unknown value for `overflow`, `max-age` text / without unit (`"7"`) / lower-case unit (`"7d"`) (the `"7D"` of the other tests is valid), text `delivery-limit`, number as `dead-letter-exchange` or `alternate-exchange`, unknown `dead-letter-strategy` or `queue-leader-locator`, `target-group-size: 0`, text `stream-max-segment-size-bytes`. Fixture `s-f1-wrong-type-answer.json`.
- **SURPRISE: `max-age` as a JSON number (`7`) answers `500` with an empty body**, nothing stored. The form must never send it.
- **Valid, stored:** `max-age` `"1Y"` and `"1000s"`; `"7D"` is valid in the other tests. The units `M`, `h` and `m` were NOT tried (the broker documents them; unverified here).
- **Meaning:** measured refusals only for the rows above (for example a negative or decimal number is measured for `max-length` alone, zero for `expires` and `target-group-size`). The form may be stricter than the broker (whole numbers, at least 1 where zero is refused, `max-age` as digits plus `Y`, `D` or `s`, further units after a live row), must send numbers as numbers and `max-age` as text.

## S-F1d: retention by policy on an existing stream (test `S-F1d ...`)

- **Method:** stream created by the stream manager, policy `{max-age: 7D, max-length-bytes: 1000000}` on it, waited for.
- **Observed:** `effective_policy_definition` = `{"max-age":"7D","max-length-bytes":1000000}`; the stream's `arguments` stay `{x-queue-leader-locator: balanced, x-queue-type: stream}` (no `x-max-age`, no `x-max-length-bytes`); `ManagementJson.parseStreamDetail` therefore gives `retention.maxAge = null` and `maxLengthBytes = null`.
- **Verdict:** SURPRISE (see "Read first" 6); a follow-up for the stream list is needed.

## S-F2a: which policy applies (tests `S-F2a ...`, fixture `s-f2a-two-policies-and-operator-policy-queue.json`)

- Different priority, disjoint keys (`max-length` at 1, `message-ttl` at 5): only `message-ttl` is in effect; `max-length` is lost. Same key, priority 5 beats 1 (99 over 10), on quorum and classic.
- Equal priority (6 queues, both write orders): exactly one policy applies (never a merge); the winner is not asserted by the test. An earlier exploratory run of 12 probes (not reproduced by a test) saw neither the write order nor the name order decide (`zz` then `aa` gave `aa` once and `zz` once): treat the winner as unpredictable, not as a proven law.
- A higher-priority policy with a key the type ignores (`max-age` on a quorum queue) does not hide the lower one; neither does a higher-priority policy whose `apply-to` is `streams` hide a `quorum_queues` policy on a quorum queue.
- The queue's `policy` is the name of the one that applies.

## S-F2b: policy and operator policy (test `S-F2b ...`)

They combine: both appear (`policy` and `operator_policy` on the queue); different keys are added; for the same numeric key the SMALLER value wins (operator 5 and policy 10: 5; policy 5 and operator 10: 5). Fixture: `max-length` 5 and `message-ttl` 60000 effective.

## S-G1: repeated queue declare (`DeclareEquivalenceTable`, tests `S-G1 ...`)

Bodies from `MigrationRequests` (the real builder: always `x-queue-type`). Per type (quorum, classic, stream), a queue declared with one limit, then declared again:

| Second declare | Answer (all three types) |
| --- | --- |
| same body | 204 |
| one argument less / one more known argument | 400 `inequivalent arg` |
| another value of a known argument | 400 |
| number sent as text (`10` then `"10"`) | 400 (`received '10' but current is '10'`: the wire type differs) |
| number sent as decimal (`10` then `10.0`) | 400 |
| another `x-queue-type` | 400 |
| `durable` false / `auto_delete` true | 400 (`inequivalent arg 'durable'` / `'auto_delete'`) |
| any argument the type does not honour or a made-up `x-foo`: other value, added, number as text | **204**, nothing stored |
| `x-queue-type` omitted (hand-written body, the builder never does this) | classic 204, quorum and stream 400 |

- **Compared arguments (`queueComparedArguments`), found by adding each candidate to a repeated declare (400 = compared, 204 = ignored):** quorum: `x-message-ttl x-expires x-max-length x-max-length-bytes x-overflow x-dead-letter-exchange x-dead-letter-routing-key x-dead-letter-strategy x-delivery-limit x-queue-leader-locator x-quorum-initial-group-size x-single-active-consumer x-consumer-disconnected-timeout`; classic: `x-message-ttl x-expires x-max-length x-max-length-bytes x-overflow x-dead-letter-exchange x-dead-letter-routing-key x-queue-leader-locator x-single-active-consumer x-max-priority x-queue-mode x-queue-version`; stream: `x-max-length-bytes x-queue-leader-locator x-initial-cluster-size x-max-age x-stream-max-segment-size-bytes x-stream-filter-size-bytes`. The sets are the result of the 20 candidates tried (the table's own list); an argument that was not tried (for example another 4.x queue argument such as a quorum target group size) is NOT classified. The "honoured when a first declare accepts it" idea was NOT measured (correction 2026-11-16, M9-05b: a FIRST declare of `x-max-priority`, `x-queue-mode` or `x-queue-version` on a quorum queue is refused with 400 `invalid arg`; only a repeated declare of it is 204 and ignored, and only a made-up argument such as `x-foo` can be stored uncompared). (`x-dead-letter-routing-key` is only declarable next to a dead-letter exchange; tested as a change of value for quorum and classic.)
- Policy-set limits are not in `arguments` and do not take part: a queue under a policy `max-length: 7` is repeated with 204 by its own arguments; adding `x-max-length: 7` as an argument is a 400 (test `S-G4 a limit that a policy sets ...`).

## S-G2: repeated exchange declare (tests `S-G2 ...`)

- Same body 204. 400 for another `type`, `durable` false, `auto_delete` true, `internal` true, `alternate-exchange` removed, changed, sent as a number, or added (when the first declare had none). `alternate-exchange` is the only argument compared.
- **SURPRISE: every other argument is ignored**: an added `x-foo`, `10` against `"10"`, `10` against `10.0` all answer 204, and the stored arguments do not change (the GET keeps the first ones). A first declare of an unknown exchange type is 400 (`unknown exchange type`).

## S-G3: streams (`DeclareEquivalenceTable.streamAnswers`, test `S-G3 ...`)

- Stream manager `createStream` on a new name: `Success`; again on the same stream: `Success` (idempotent, no error); on the name of a quorum or a classic queue: `Refused` (`17 (PRECONDITION_FAILED)`); on the name of an exchange: `Success` (a stream and an exchange do not share a namespace).
- Queue `PUT` on the name of a stream: quorum or classic type: `400 inequivalent arg 'x-queue-type'`; stream type: **400 `inequivalent arg 'x-queue-leader-locator'`** (see "Read first" 5). An exchange `PUT` on a stream's name: 201. A stream-type queue `PUT` on a quorum queue's name: 400 `inequivalent arg 'x-queue-type'`.

## S-G4: does a GET return what the broker compares (tests `S-G4 ...`, fixtures `s-g4-queue-get.json`, `s-g4-exchange-get.json`)

- **Yes, everything.** Queue GET has `durable`, `auto_delete`, `type`, `exclusive`, `arguments` (with `x-queue-type`); numbers keep their wire form (`10` is a JSON number, `10.0` reads back as `10.0`, `"10"` as a string), so number-as-text and number-as-decimal are visible. Exchange GET has `type`, `durable`, `auto_delete`, `internal`, `arguments`.
- **What a read cannot see: nothing that the broker compares.** The reverse is the trap: the GET also shows arguments the broker ignores, so a check that compares ALL arguments reports "different" where the broker answers 204. What the check does with it is the architect's rule in `docs/spec.md` "Check against the broker" (the check predicts the apply: the broker's 204 or 400 AND the read-back that follows). A stream made by the product also has `x-queue-leader-locator: balanced` in its arguments.

## S-H: names (test `S-H ...`, fixture `s-h-get-dotdot-answers-a-list.json`)

| Name | queue (PUT) | exchange (PUT) | policy (PUT) | stream (manager) |
| --- | --- | --- | --- | --- |
| `amq.test`, `amq.`, `amq.gen-x` | 401 `Access refused.` | 401 `Access refused.` | 201 (no restriction) | Refused (`17 (PRECONDITION_FAILED)`) |
| `amq` (no dot) | 201 | 201 | 201 | Success |
| 255 bytes (ASCII and multi-byte) | 201 | 201 | 201 | Success |
| 256 bytes (ASCII and multi-byte) | **400 `queue should be less than 255 bytes, actually was 256`** | 201 | 201 | **Success** |
| a space, a slash | 201 | 201 | 201 | Success |
| `.` and `..` | **405** (empty body) | 405 | 405 | **Success** |

- The status for a reserved name is `401` with `Access refused.` (the test asserts the status and the text; the reason code name was not asserted), not 400. The message says "less than 255" but 255 is accepted.
- **Real GET answers the check meets** (`GET /api/queues/%2F/{name}` and `/api/exchanges/%2F/{name}`):
  - `.` : queue **200 list**, exchange **200 list**; `..`: queue **200 list**, exchange **200 list** (the vhost listing, 109 and 35 entries on a used broker); written `%2E`, `%2E%2E` or raw, and for the empty name, the same.
  - an absent ordinary name (incl. `amq.test`, 256 bytes, space, slash): **404**.
  - a stream named `.` or `..` that exists: also 200 list: the app cannot tell it from the vhost listing. Only the GET was measured; a DELETE through the management API was not tried (the test removes such streams through the stream protocol).
- Streams of 255 and 256 bytes, `amq` and names with a space or slash are read back by the management GET as `type: stream`.

## Architect review of the spike (step 3, 2026-11-16, round 0)

Verdict ACCEPTED WITH FOLLOW-UPS. Every item is pinned by a live row with a positive control, except what the lines above now mark as not pinned (the 12-probe tie run, the units `M h m`, names above 256 bytes, DELETE of `.`/`..`, the arguments outside the 20 candidates). Open points for the test developer (none blocks): (T1) both tables could assert that every row of the constant was visited (today a row nobody sends is never compared; the ids are all used, checked by hand); (T2) `M9-05b` adds the live rows it needs for the arguments outside the 20 candidates it wants to classify and for the name `...`; (T3) the three-dots name was not measured (the parser allows it, the path becomes `%2E%2E%2E`).

What the check does today with a `200` and a JSON list (verified in code): `ManagementDeclareCheck.find` sends `GET /api/queues/%2F/%2E` (`MigrationRequests.encode` writes `%2E` per dot, the path allow-list refuses only the raw segments `.` and `..`), the broker answers `200` with the vhost list, `DeclaredResourceMatcher.matches` finds no object and the step reads **"Exists with different settings"** for a queue, exchange or stream; a delete step reads `Same` (so "Will be deleted"). Both are wrong for a name that does not exist. The brief of `M9-05` (decision 3) and its step 5 note claimed `Unreadable(NOT_A_REQUEST)`: that is wrong; `DeclareCheckGetOnlyTest` proves only that the path is encoded. The case is unreachable from the app (the forms, `PlanValidation` and the file parser refuse `.` and `..`), so it is a hardening: `M9-05b` makes a `200` whose body is a JSON list on an item path `Unreadable` (fixed reason), for every item kind but a binding. `FakeDeclareCheck` is scripted and holds no table, so it answers nothing for `.`; the test double that answers Absent for any unknown name is `FakeManagementBroker` (core-testing): it must answer the vhost list for `.` and `..` on a queue and an exchange path (task `M9-05b`).

## Not measured

- Operator policies in the wizard, clusters (out of scope). TLS (the facts are about names and arguments, not transport). Names above 256 bytes for streams (only 255 and 256 were asked). The equal-priority winner: not predictable from names or write order; its real rule (probably an internal map order) was not looked for. `x-dead-letter-routing-key` as a "more" row on its own (needs the exchange argument; measured as a changed value). Exclusive queues (the management API cannot declare one).
