# Show stream message annotations that are lists or maps as readable text (`x-opt-deaths`)

Milestone: M3 (a defect found by the M9-00b spike; scheduled with M9)    Depends on: none    Size: S

OWNER APPROVED running it (2026-11-17, "goes on"); no open product question remains.

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
  - "The property filter matches a list property by its whole rendered text only": DECIDED (owner item 11, 2026-11-17, O16). Given a message whose `x-opt-deaths` renders as `[{queue: orders, reason: rejected, ...}]`, a property filter on `x-opt-deaths` equal to the whole rendered text finds it, and a filter on a part (`rejected`, `queue: orders`) finds nothing. The test developer writes this scenario in step 2 with the other two (tag `@m9-00b-c`, `@fake-broker`, steps in the same commit).
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
- Owner item 11 decided (2026-11-17, O16): "a property filter on a list value matches the whole rendered text only" (the same text the screen shows; no partial match, no per-element match). If the filter has help text, it says so; the architect does not propose new wording unless the help text exists (check in step 1 of the build). `PropertyValueText` is the one rendering used by both the screen and `StreamScanner`, so the filter and the display cannot disagree.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.

## Status
- Step 1 (architect, 2026-11-17, round 0, branch `brief-owner-answers-m9`): the owner approved running the task and decided item 11 ("owner item 11 decided: a property filter on a list value matches the whole rendered text only"). Brief confirmed against the code references of 2026-11-16 (`StreamMessageMapping.kt`, `PayloadFormatter.kt`, `StreamScanner.kt`, `McpServer.kt`: not re-read in this step, the test developer confirms the line numbers in step 2). The filter scenario is now specified (see Acceptance). No scenario of this task is in the feature files yet: the test developer writes the three with their steps in step 2 (no step-less scenario is selected by a runner because none exists). Next: step 2.
