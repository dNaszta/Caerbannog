# M9-00a spike results: MQTT routes, request and response flow, headers bindings, measured

Measured on 2026-11-16 on a real broker, Docker Desktop on an Apple-silicon Mac (arm64 image, id `sha256:753099a1...`): `rabbitmq:4-management` = **RabbitMQ 4.4.0** (Erlang 29.1.2; `GET /api/overview` `rabbitmq_version` `4.4.0`, `product_version` `4.4.0`). The tag floats; the first test of every class asserts that the version starts with `4.`, and every assertion below fails on purpose when a later 4.x answers differently. Plugins `rabbitmq_stream`, `rabbitmq_stream_management`, `rabbitmq_mqtt` (S-E: none needed). Throwaway Testcontainers brokers, default `guest` user, plain ports (MQTT 1883, stream 5552, management 15672); no profile of the app, no secret. MQTT client: Eclipse Paho MQTT 5 (1.2.5); one raw MQTT 3.1.1 socket where a client library would hide the answer. The app's own code is used in one place only, `RabbitMqStreamMessageReader` (for `x-routing-key`, `x-exchange`).

Living proof (tag `broker`), run one class at a time, helper `M9SpikeMqttSupport.kt`:

| Class | Tests | Result | Duration |
| --- | --- | --- | --- |
| `RealBrokerM9SpikeMqttRouteTest` (S-A1 to S-A4, S-B1) | 8 | all pass | 84.6 s |
| `RealBrokerM9SpikeMqttRequestResponseTest` (S-A5 to S-A8) | 5 | all pass | 26.5 s |
| `RealBrokerM9SpikeHeadersBindingTest` (S-E1 to S-E5) | 6 | all pass | 4.8 s |
| `RealBrokerM9SpikeMqttExchangeTest` (S-B2, S-B1 on a custom exchange) | 4 | all pass | 11.0 s |

Raw answers: `data-rabbit/src/test/resources/fixtures/m9-00/` (`s-a1-...`, `s-a3-...`, `s-a6-...`, `s-a7-...`, `s-b2-...conf`, `s-e1-...` to `s-e4-...`; broker answers of throwaway containers only, queue names carry a random tag).

## Read first: results that differ from the spec, the brief or an expectation

1. **S-A3, a dot in a level is NOT a round trip (surprise).** `sensors/v1.2/temp` is accepted and the key is `sensors.v1.2.temp` (the dot is kept), and a subscriber of the literal filter `sensors/v1.2/temp` never receives its own message (measured: that subscriber is subscribed and connected; its receipt of the control message is printed in the test's note but not asserted, review R2). A key is made of dot-separated words, so `sensors.v1.2.temp` has four words and is also the key of the four-level topic `sensors/v1/2/temp`: a topic with a dot and a topic with a slash in its place are the same key. NOT measured (corrected at review, R1): which topic text an MQTT subscriber of `#` receives for that message. The column "key back to topic" in the S-A3 table is computed by the test (`replace('.', '/')`), it is not read from the broker; "the broker turns every dot into a slash" is the likely cause of the non-delivery, not an observation. The form must refuse a level with a `.`: the aliasing is certain from the key's structure, and the missing delivery is measured.
2. **S-A3, a `*` inside a level (surprise).** `sensors/a*b/temp` is accepted, the key is `sensors.a*b.temp`, a hand-written stream binding with that key matches. But the broker hands the topic back to MQTT subscribers as `a+b` (an asterisk becomes a plus). Measured on a raw MQTT 3.1.1 socket (the broker's PUBLISH carries `m9a3raw/a+b`, asserted). The Paho 5 literal subscriber of that topic ended the test disconnected (`subscriberStillConnected=false`, asserted) and the broker log says "peer closed TCP connection"; that the client closes the connection BECAUSE the topic name holds a `+` is the likely cause (a strict client on an invalid topic name), not separately proven. The form must refuse `*` in a level. A level that is exactly `*` was not measured (S-A3 below).
3. **S-A3, `$` topics.** An MQTT subscriber of `#` DOES receive `$SYS/...` (MQTT 5 section 4.7.2 says a `#` filter must not match a topic that starts with `$`; RabbitMQ 4.4.0 does not apply that), and a binding of `#` on `amq.topic` receives it too. Not a spec statement of ours, but the sample-value dialog must not assume the `$` rule.
4. **S-A7, the response topic is translated (surprise).** An MQTT 5 response topic `devices/d42/reply` lands in a stream as `reply-to` = `/exchanges/amq.topic/devices.d42.reply` (an AMQP 1.0 address with dots), in a queue as the header `x-reply-to-topic` = `devices.d42.reply`. The original MQTT form is not kept.
5. **S-A7, correlation data differs by destination (surprise).** Stream: the standard property `correlation-id` (binary). Queue: the standard property `correlation_id` only when the bytes are UTF-8 text, else the header `x-correlation-id` with the text `Not UTF-8, base64 is: AQID/w==`.
6. **S-A7, the two stream messages that carried correlation data, a response topic or a content type show `creation-time` and `absolute-expiry-time` as 0** (Long 0, present, as the Java stream client reports them; whether 0 is on the wire or the client's default for an unset value is not distinguished, and the test does not assert it: fixture and printed note only, review R7). The stream message that carried only user properties (S-A6) and the control message show no standard property at all (asserted). Do not show 0 as 1970-01-01: the app's reader already leaves zero out (`StreamMessageMapping.kt:34-35`, `> 0L`).
7. **S-B1, no endpoint tried names the MQTT exchange**, and **S-B2, the custom exchange must exist first and the plugin never declares it.** With `mqtt.exchange = custom.mqtt` and no such exchange, an MQTT QoS 1 publish loses the connection and a subscription is refused (reason code 128). Contradiction with nothing in the spec, but the sentence "Uses the broker's default MQTT exchange amq.topic" is an assumption the app has no way found to check; a route saved on `amq.topic` on such a broker routes nothing for MQTT publishes. Also seen (S-A1 fixture, S-B2 test): the binding of an MQTT subscription's queue (`mqtt-subscription-...`) has the MQTT exchange as its source, so a broker with a live or persistent MQTT subscription shows the name through `GET /api/bindings` (an idea, see `M9-10c`; not a general way: no subscriber, no hint).
8. **S-E2, an invalid `x-match` is refused with 400 and nothing is created**; the value is case sensitive (`ALL` is refused) and must be text (a number gives 400). The four values `all`, `any`, `all-with-x`, `any-with-x` are accepted; there is no value `none`. A binding with NO `x-match` key is accepted (listed without one); the row "none" in the S-E2 table below is that absence, not a value. How a binding without `x-match` routes was not measured.
9. **S-E4, `x-match` with no other header is not harmless:** `all` with only `x-match` matches EVERY message (also one with no headers), `any` with only `x-match` matches nothing. This is the text the brief wants for "A headers binding without a header says what it would match".
10. **S-E3, a repeated POST of an existing binding answers 201 again** (not 204, no 409) and the list does not grow.
11. **Tooling surprise (not a broker fact):** `MqttClient.subscribe(String, int, IMqttMessageListener)` of Paho 1.2.5 overflows the stack (`StackOverflowError`); the array form `subscribe(MqttSubscription[], IMqttMessageListener[])` works. Relevant only if the product ever subscribes.

## S-A1: the broker's own translation (measured), test `S-A1 the broker translates MQTT filters into binding keys on amq topic`

Method: a Paho 5 client per filter, QoS 1 subscribe, then `GET /api/bindings/%2F` and the binding with source `amq.topic` and the client's queue. Fixture `s-a1-bindings-amq-topic-mqtt-subscriptions.json`.

| MQTT filter | Binding key on `amq.topic` |
| --- | --- |
| `sensors/+/temp` | `sensors.*.temp` |
| `sensors/#` | `sensors.#` |
| `#` | `#` |
| `+` | `*` |
| `a/b/c` | `a.b.c` |
| `devices/+/answer` | **`devices.*.answer`** (the expectation of the brief is confirmed) |

Also seen (the queue name pattern is asserted; type, durable and the binding arguments are in the printed notes and the fixture only, not asserted): the subscriber's queue is `mqtt-subscription-<clientId>qos1`, type classic, durable true, auto-delete false (clean start, default session expiry); the binding has `arguments` `x-binding-key` (the same key) and `x-mqtt-subscription-opts` (`qos`, `no-local`, `retain-as-published`, `retain-handling`); `properties_key` percent-encodes the asterisk (`sensors.%2A.temp~ew3Tww`).

## S-A2: hand-written keys against the broker's own subscription (measured), test `S-A2 hand-written keys match ...`

Method: per key one stream and one quorum queue bound on `amq.topic` by `POST /api/bindings` with the key (plus a control key), per filter an MQTT subscriber; 14 probe topics published at QoS 1 by one client, then a control message; every destination read only after the control arrived (streams with an unnamed non-tracking consumer, queues by requeue peek). The stream, the quorum queue and the MQTT subscriber got exactly the same topics in all six rows.

| Filter / key | Matched (of the 14 probes) | Not matched (examples) |
| --- | --- | --- |
| `sensors/+/temp` / `sensors.*.temp` | `sensors/room1/temp` | `sensors/temp`, `sensors/a/b/temp`, `sensors/room1/hum` |
| `sensors/#` / `sensors.#` | `sensors`, `sensors/temp`, `sensors/room1`, `sensors/room1/hum`, `sensors/room1/temp`, `sensors/a/b/temp` (zero levels after `sensors` also match) | `a/b/c`, `x` |
| `#` / `#` | all 14 | none |
| `+` / `*` | `sensors`, `x` (any one-level topic) | everything with a `/` |
| `a/b/c` / `a.b.c` | `a/b/c` | `a/b`, `a/b/c/d` |
| `devices/+/answer` / `devices.*.answer` | `devices/d42/answer` | `devices/answer`, `devices/d42/e7/answer`, `devices/d42/request` |

## S-A3: edge levels (measured, with surprises 1 to 3), test `S-A3 edge levels, ...` and the raw-socket and `$` tests; fixture `s-a3-edge-levels.txt`

Every publish was accepted by the broker (QoS 1 puback) and every subscribe was accepted. The stream message carries `x-routing-key` = the topic with each `/` turned into `.` and nothing else changed, and the app's reader shows the same key.

| Topic | Key seen (`x-routing-key`) | Hand-written key (slash to dot) matches | Literal filter of the same topic receives it | Key back to topic (dot to slash, computed by the test) equals topic |
| --- | --- | --- | --- | --- |
| `sensors/v1.2/temp` | `sensors.v1.2.temp` | yes | **no** | computed, not measured (see surprise 1) |
| `sensors/a*b/temp` | `sensors.a*b.temp` | yes | **no** (the literal subscriber ended disconnected; the topic is `a+b` on the wire, raw socket) | computed |
| `sensors//temp` | `sensors..temp` (empty word) | yes | yes | yes |
| `/sensors/temp` | `.sensors.temp` | yes | yes | yes |
| `sensors/temp/` | `sensors.temp.` | yes | yes | yes |
| `sensors/a b/temp` | `sensors.a b.temp` | yes | yes | yes |
| `$SYS/x` | `$SYS.x` | yes | yes | yes |
| `$foo/bar` | `$foo.bar` | yes | yes | yes |

For `M9-10` the form should refuse: a `.` in a level (aliasing with `/`), a `*` inside a level (comes back as `+`), and, as a product choice, empty levels and a leading or trailing `/` (they work as keys but an empty word is rarely meant). A space and a `$` prefix work. Not measured: a level that is exactly `*` or `+` in a PUBLISH topic (the Paho client refuses `+` and `#` itself, `*` was only tested inside a level); the documentation says `cities/*` and `cities/+` are the same subscription (docs "Topic level separator and wildcards"), not measured here.

## S-A4: QoS and retain (measured), test `S-A4 QoS 0, QoS 1 and retained publishes ...`

QoS 0, QoS 1 and a retained QoS 1 publish all arrive in a stream bound by hand with the translated key, in order, once each; the stream message of the retained publish shows no marker of being retained in its annotations (`x-routing-key`, `x-exchange`; printed in a note, not asserted; its standard and application properties were not looked at). A subscriber that connects later gets the retained message with the retain flag set (positive control: subscription works). QoS 2 was not tried.

## S-A5: can a stream feed an MQTT subscription? (measured: NO), test `S-A5 ...`

A stream bound to `amq.topic` (`m9.a5.<tag>`) and an MQTT client subscribed to `m9/a5/<tag>`: a message put into the stream through the stream protocol is in the stream and never reaches the client; the control publish over MQTT to the same topic reaches both (so the subscription works). Documentation of the installed version (RabbitMQ website source `docs/mqtt.md`, rendered as https://www.rabbitmq.com/docs/mqtt, read 2026-11-16): "Note that MQTT clients never consume from streams directly although they can publish messages to streams." (section "RabbitMQ Management API", Prometheus paragraph), and under "Queue Types" the plugin creates "a classic queue, quorum queue, or MQTT QoS 0 queue per MQTT subscriber"; a stream can only be a bound destination of a publish. No plugin option or subscription feature consumes a stream. The documentation reading shows no feature that does it (absence in the pages read, not a proof for every plugin option). Answer for `M9-07b`: no way found, the planner plans no MQTT delivery from a stream; the graph note stays.

## S-A6: where an MQTT 5 user property lands (measured), test `S-A6 ...`; fixture `s-a6-...txt`

Publish to `devices/d42/answer`, bound by `devices.*.answer`, to a stream and a quorum queue.

| User property | Stream message (stream client) | Quorum queue message (requeue peek) |
| --- | --- | --- |
| `correlation-id` = `corr-1` | application property `correlation-id` = text `corr-1`; standard properties empty | `properties.headers` `{"correlation-id":"corr-1"}`; no `correlation_id` property |
| other key `request-id` = `req-2` | application property `request-id` = text `req-2` | header `request-id` |
| both | both application properties | both headers |

Route in the stream: message annotations `x-routing-key` = `devices.d42.answer`, `x-exchange` = `amq.topic` (the app's reader shows them as `routingKey` and `exchange`, and also lists them under `properties`; the user property shows under `applicationProperties`). So a correlation id as user property `correlation-id` lands where a processing step reading application properties finds it; it is NOT the AMQP `correlation-id` property (that is S-A7).

## S-A7: correlation data, response topic, content type (measured with surprises 4 to 6), test `S-A7 ...`; fixture `s-a7-...txt`

| MQTT 5 field | Stream message | Quorum queue message |
| --- | --- | --- |
| correlation data `corr-bytes-1` (UTF-8) | property `correlation-id` = binary `corr-bytes-1` | property `correlation_id` = `corr-bytes-1` |
| correlation data `01 02 03 ff` (not UTF-8) | property `correlation-id` = binary `0x010203ff` | no `correlation_id`; header `x-correlation-id` = `Not UTF-8, base64 is: AQID/w==` |
| response topic `devices/d42/reply` | property `reply-to` = `/exchanges/amq.topic/devices.d42.reply` | header `x-reply-to-topic` = `devices.d42.reply` |
| content type `application/json` | property `content-type` | property `content_type` |
| user property `trace` | application property | header |

Messages with these fields also carry `creation-time` = 0 and `absolute-expiry-time` = 0 on the stream. Sent together, the correlation data and the response topic are both kept, in the places above.

## S-A8: fan-out (measured), test `S-A8 ...`

A device-style subscription `devices/+/request` (its own classic queue, key `devices.*.request`) and a request stream bound by `devices.*.request`. Three publishes (two over MQTT QoS 1, one by `POST /api/exchanges/%2F/amq.topic/publish` with key `devices.d42.request`, answered `{"routed":true}`) and a control: the device subscription received all four and the stream holds the same four, once each. So one publish reaches both; a publish through the management API reaches an MQTT subscription too.

## S-B1: can the MQTT exchange be read? (measured: NO), tests `S-B1 ...` (default broker) and `S-B1 on this broker too ...` (custom exchange)

`/api/overview` (one mention of mqtt: the listener `protocol: mqtt`, port 1883), `/api/nodes` (the plugin names and versions only), `/api/nodes/{node}` (keys `applications`, `config_files`, `enabled_plugins`), `/api/global-parameters`, `/api/definitions`, `/api/vhosts` as administrator: none of the printed answers names the MQTT exchange (judged by reading the printed notes for the default broker: the test only notes the mentions of `mqtt` and asserts nothing; on the custom broker the absence of `custom.mqtt` IS asserted, zero mentions in overview, nodes, global parameters and vhosts; administrator only, a user without the administrator tag was not tried) (on the custom broker: zero mentions of `custom.mqtt` in overview, nodes, global parameters, vhosts). The only place the value showed was `rabbitmqctl eval 'application:get_env(rabbitmq_mqtt, exchange).'` inside the container (`{ok,<<"amq.topic">>}`), which is not the management API. `amq.topic` itself is an ordinary topic exchange (`GET /api/exchanges/%2F/amq.topic` 200). Answer for `M9-10`: keep the assumption `amq.topic` and say so in the wizard.

## S-B2: `mqtt.exchange = custom.mqtt` (measured), class `RealBrokerM9SpikeMqttExchangeTest`, config `fixtures/m9-00/s-b2-custom-mqtt-exchange.conf`

- The exchange does **not** exist at start (404) and the first MQTT connection does **not** declare it (still 404). A binding on it is refused (404 "no exchange 'custom.mqtt' in vhost '/'").
- Without the exchange: an MQTT QoS 1 publish ends with "Connection lost" (the broker drops the connection), a subscribe answers reason code 128.
- With the exchange declared first (topic, durable): an MQTT publish reaches a stream bound on `custom.mqtt` (stream message `x-exchange` = `custom.mqtt`), and a stream bound on `amq.topic` with the same key gets nothing from MQTT (both streams then received a management-published control message). A subscription is bound on `custom.mqtt` (the binding source).
- So on a broker with a custom MQTT exchange a route saved on `amq.topic` silently routes nothing for MQTT publishes; the app cannot see it (S-B1).

## S-E1: argument types on a headers binding (measured), test `S-E1 ...`; fixture `s-e1-binding-types.json`

`POST /api/bindings/%2F/e/{headers exchange}/q/{queue}` with `{"routing_key":"","arguments":{"x-match":"all","text":"abc","number":10,"negative":-3,"decimal":1.5,"flag":true,"numeric-text":"10"}}` answers `201`. Read back by `GET /api/bindings/%2F/e/{ex}/q/{q}` and by `.../bindings/source`: the arguments are returned with the same JSON types (text stays text, `10` stays a number, `1.5` a decimal, `true` a boolean, `"10"` stays text); the keys come back sorted, not in the order sent; `properties_key` `~jHtONQ`. Only the JSON types seen through the management API are measured. That the broker stores a whole number as an AMQP long is NOT measured: the only hint is its error text for a numeric `x-match` ("type long"). Whether `true` or `1.5` MATCH a published header of the same value was not tried (routing was measured for text and whole numbers only, S-E4).

## S-E2: `x-match` values (measured), test `S-E2 ...`; fixture `s-e2-x-match-statuses.txt`

| Sent | POST | Listed |
| --- | --- | --- |
| `all`, `any`, `all-with-x`, `any-with-x` | 201 | 1 binding, the value shown as sent |
| none (`{"a":1}`) | 201 | 1 binding, no `x-match` shown |
| `bogus` | 400 `Invalid x-match field value <<"bogus">>; expected all, any, all-with-x, or any-with-x` | 0 |
| `ALL` | 400, same text (case sensitive) | 0 |
| `1` (a number) | 400 `Invalid x-match field type long (value 1); expected longstr` | 0 |

## S-E3: two bindings that differ only in arguments (measured), test `S-E3 ...`; fixture `s-e3-two-bindings.json`

Arguments `{x-match:all,a:1}` and `{x-match:all,a:2}` between the same exchange and queue (same empty routing key): both `POST` answer 201, the list has two entries with different `properties_key` (`~4tmbCA` and `~_BJVLg`: the part after `~` is a hash of the arguments). A repeated `POST` of the first answers `201` again and the list still has two entries (no duplicate, no error).

## S-E4: routing by header (measured), test `S-E4 ...`; fixture `s-e4-routing-table.txt`

Publish through `POST /api/exchanges/%2F/{ex}/publish` with `properties.headers`; arrival judged by requeue peek of each queue. Bindings: `all(a=1,b=x)`, `any(a=1,b=x)`, `all(n="10" text)`, `all(n=10 number)`, `all(only x-match)`, `any(only x-match)`. Every publish answered `routed: true` (because of the `all(only x-match)` queue).

| Published headers | Queues that got it (besides `all(only x-match)`, which always does) |
| --- | --- |
| `a=1, b=x` | `all(a=1,b=x)`, `any(a=1,b=x)` |
| `a=1` | `any(a=1,b=x)` |
| `b=x` | `any(a=1,b=x)` |
| `a=2, b=y` | none |
| `n=10` (number) | `all(n=10 number)` only |
| `n="10"` (text) | `all(n="10" text)` only |
| no headers | none |
| `z=1` (unrelated) | none |

So `all` needs every argument, `any` needs one; a number does not match a text argument and the other way round; `any(only x-match)` never matches; `all(only x-match)` always matches.

## S-E5: routing key on fanout and headers exchanges (measured), test `S-E5 ...`

- Fanout: a binding with routing key `ignored-key` is accepted (201) and listed with that key; a publish with the key `a-different-key` is routed to it. The key is ignored.
- Headers: a binding with routing key `binding-key` and `{x-match:all,a:1}`: a publish with matching headers and another routing key is routed; a publish with that routing key but no header is NOT routed. The key is ignored for routing.
- Two bindings on a headers exchange to one queue with the same arguments and routing keys `k1`, `k2` are two list entries (the same hash part after `~` in `properties_key`). Hiding the routing key field in the form (as `M9-01`/`M9-11` plan) avoids these look-alike duplicates.

## What could not be measured, and what was not tried

- QoS 2 publish; a PUBLISH topic whose level is exactly `*`; the documented equality of `cities/*` and `cities/+` as filters (docs only); a retained message on a stream that is read after a restart; clusters; MQTT over TLS (plain port used on purpose, the product default is not under test).
- The quorum-queue "peek" proves where a field lands in the 0-9-1 message; whether the AMQP 1.0 view of a classic queue message differs was not looked at.
- The documentation citation for S-A5 comes from the website source on GitHub (`rabbitmq-website`, `docs/mqtt.md`, branch main, fetched 2026-11-16); it is the page for the current release, not a versioned snapshot of 4.4.0.

## Review at step 3 (architect, 2026-11-16, round 0): what the tests pin and what they do not

Verdict: ACCEPTED WITH FOLLOW-UPS. The positive-control rule of the brief is followed for S-A2, S-A4, S-A5, S-A6, S-A7, S-A8 and S-B2 (every "did not arrive" is judged after a control arrived, by `m9StreamUntilControl`, `m9PeekUntilControl` or `awaitUntil`). Wording that went beyond the measurement was corrected above (R1, R2, R5, R7, E1). Test follow-ups for the test developer are in `M9-00a-b-spike-test-hardening.md`:

| # | Severity | Where | Finding |
| --- | --- | --- | --- |
| R1 | major (corrected in this file) | `RealBrokerM9SpikeMqttRouteTest.kt:186`, results surprise 1 | "key back to topic" and "a subscriber of `#` receives `sensors/v1/2/temp`" were not measured: no MQTT subscriber of a wildcard ran for the dot topic; the column is `keySeen.replace('.', '/')`. |
| R2 | minor | `RealBrokerM9SpikeMqttRouteTest.kt:175-176` | The control message at the literal subscribers is only printed (`m9Note`), not asserted; the "no" for the dot topic (`literalSubscriberGotIt=false`) has no asserted positive control. |
| R3 | minor | `RealBrokerM9SpikeMqttRouteTest.kt:78-82` | Subscriber queue type, durable, auto-delete and the binding arguments of S-A1 are printed, not asserted (only the name pattern is). |
| R4 | minor | `RealBrokerM9SpikeMqttRouteTest.kt:296-312` | S-B1 on the default broker asserts nothing (notes only); the custom-exchange class asserts. |
| R5 | minor | `RealBrokerM9SpikeMqttRouteTest.kt:283` | "Nothing marks it retained" looks at annotations only and does not assert even that. |
| R6 | minor | `RealBrokerM9SpikeHeadersBindingTest.kt:116-121` | `arrivals()` peeks each queue straight after the publish, before the always-matching queue (`all(only x-match)`) has shown the marker; a late enqueue would read as "did not arrive". Wait for the control queue first. S-E5 uses the same helper. |
| R7 | minor | `RealBrokerM9SpikeMqttRequestResponseTest.kt:130` | `creation-time` and `absolute-expiry-time` = 0 are in the fixture only, not asserted. |
| E1 | minor (corrected in this file) | `RealBrokerM9SpikeHeadersBindingTest.kt:57` | "stored as an AMQP long" is not measured; boolean and decimal routing was not tried. |
