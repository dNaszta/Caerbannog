# Show stream message annotations that are lists or maps as readable text (`x-opt-deaths`)

Milestone: M3 (a defect found by the M9-00b spike; scheduled with M9)    Depends on: none    Size: S

## Goal
A stream message that was dead-lettered into a stream shows `x-opt-deaths` (and any other annotation or property that is a list or a map) as readable text on the Messages screen, in "Copy properties", in the property filter and in the MCP stream message tools, never as `[Ljava.lang.Object;@1a2b3c`.

## Why
M9-00b S-C7: with a stream as the dead-letter target (a recipe of `M9-09`) every dead letter carries `x-opt-deaths`, a list of maps (`queue`, `reason`, `count`, `first-time`, `last-time`, `exchange`, `routing-keys`), and the app prints the Java array's identity. The owner browses exactly these streams to see why a message came. `docs/spec.md` "Measured facts of M9-00b", S-C7.

## Scope
In:
- Where it happens (read at step 3): `data-rabbit/.../StreamMessageMapping.kt` copies every message annotation into `StreamMessage.properties` as the client's raw value (an `Object[]` for a list, nested maps and arrays inside); then `core/.../PayloadFormatter.kt` (line 213 `properties.mapValues { it.value?.toString() }` and the copy text near line 262), `StreamScanner.kt` (line 162, the property filter `message.properties[name]?.toString() == value`), `ui/.../MessageBrowserView.kt` (line 636, content type) and `mcp-server/.../McpServer.kt` (lines about 796 and 830: `put(k, v?.toString() ?: "")` for `properties` and `applicationProperties`) all print `toString()`.
- One pure function in `core` (no `java.*`), for example `PropertyValueText.of(value: Any?): String`: text as is, number and boolean as their text, a list or an array as `[a, b]`, a map as `{key: value, ...}` with nested values rendered the same way, ByteArray as the existing binary treatment if there is one (check `PayloadFormatter`; do not invent a new one). Used at all five places above. Normalising `Array<*>` to `List` in `StreamMessageMapping` is allowed if it keeps `StreamMessage` comparable, but the text rule is in `core` and unit-tested once.
- The Messages detail view shows the value on one line or wrapped; no new component. Long lists are not cut by this task.
Out: a structured viewer for death information, a new screen, any change of what is read from the broker, a new MCP tool, MCP output field types (the values stay strings).

## Acceptance
- BDD (new, to be written by the test developer in step 2 with their steps; scenarios in the streams browser feature and the MCP feature, `@fake-broker`; the runners of those features select `@fake-broker`, so the steps must exist in the same commit):
  - "A dead letter in a stream shows its death information as text": Given a stream message with the annotation `x-opt-deaths` holding one map `queue` "orders", `reason` "rejected", `count` 1 ... When I open the message in the browser Then the properties show `x-opt-deaths` containing `queue: orders` and `reason: rejected`, and no text starts with `[Ljava`.
  - "The MCP stream message tool returns the death information as text": the same message through the read tool: `properties["x-opt-deaths"]` is a string containing `reason: rejected`, not an array identity.
  - "The property filter finds a message by a text inside a list property": decide at step 1 whether the filter compares the whole text or a part (a product question below); until decided no scenario.
- Unit: `PropertyValueTextTest` (null, text, number, boolean, empty list, list of maps, nested list in a map, an `Array<*>`, a map with non-text keys), `StreamMessageMappingTest` with a list annotation (the S-C7 fixture `data-rabbit/src/test/resources/fixtures/m9-00/s-c7-stream-message.json` lists the keys), `PayloadFormatterTest`, the MCP tool test.
- Live: `RealBrokerM9SpikeStreamDeadLetterTest` already makes a real dead letter in a stream; add one assertion with the app's reader that no property value text starts with `[L` (a `@broker`-tagged check, no new class).

## Design notes
`core` holds the rule, `data-rabbit` stays a mapper, `ui` and `mcp-server` call the `core` function. The MCP tool schema is unchanged (string values). No broker call changes; browsing stays read-only (unnamed consumer).

## UX
`docs/ux/` Messages detail: the properties table. State to check: a dead letter with `x-opt-deaths` (one entry and two entries), an empty annotation list. No mockup change; the value is plain text.

## MCP
Evaluated: the two stream message tools (`caerbannog_read_stream_messages` style tools in `McpServer.kt`) have the same raw rendering (lines about 796 and 830 print `toString()`), so they are corrected by this brief. No new tool and no schema change: the output fields stay strings.

## Open points
- Which other property types print badly was not probed (a `ByteArray` property value prints `[B@...`; a `UUID` or a decimal were not tried). The test developer adds a live probe row for application properties of those kinds (a message sent with the stream client) before building the rule; if one prints badly, the same function covers it.
- Product question for the owner (wording only if the filter changes): does the property filter match the whole rendered text of a list value, or nothing? Recommendation: whole text only (the same text the screen shows), documented in the filter help if there is one.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
