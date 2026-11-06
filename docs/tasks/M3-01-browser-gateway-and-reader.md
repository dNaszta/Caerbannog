# M3-01: Message browser gateway, stream reader, and safety model

Milestone: M3    Depends on: M2-09 (merged)    Size: M    Branch: `m3-01-browser-gateway-and-reader`

## Goal
Establish the core domain contracts, data-layer implementation, and safety model for Milestone M3 (Message Browser):
1. Define core domain abstractions for reading stream messages (`StreamMessage`, `BrowseStart`, `BrowseResult`, `StreamMessageReader`).
2. Provide a fully functional in-memory `FakeStreamMessageReader` in `core-testing`.
3. Implement `StreamMessageReader` in `data-rabbit` using the official RabbitMQ `stream-client` with ephemeral, unnamed consumers and `noTrackingStrategy()`.
4. Enforce the critical M3 safety rule: browsing a stream must never write tracking entries, alter stored consumer offsets, or mutate stream message counts on the broker.
5. Headless MCP check: expose `caerbannog_browse_stream` tool in `mcp-server` so AI agents can inspect messages in real time with safety constraints.

## Scope
### In
- **Core module (`core`):**
  - `StreamMessage`: offset, timestamp (epoch ms), raw payload (`ByteArray`), standard AMQP/Stream properties, application properties.
  - `BrowseStart`:
    - `First`: start from stream first offset.
    - `LastPage(pageSize: Int)`: start from `streamLastOffset - pageSize + 1` (clamped to first offset).
    - `Offset(offset: Long)`: start from a specific numeric offset.
    - `Timestamp(epochMs: Long)`: seek via `OffsetSpecification.timestamp(epochMs)`.
  - `BrowseResult`:
    - `Success(messages: List<StreamMessage>, streamFirstOffset: Long, streamLastOffset: Long)`
    - `Empty(streamFirstOffset: Long, streamLastOffset: Long)`
    - `OutsideRange(offset: Long, validRange: LongRange)`
    - `NotFound`
    - `AccessDenied`
    - `Failed(reason: String)`
  - `StreamMessageReader` interface:
    - `suspend fun readPage(vhost: String, stream: String, start: BrowseStart, pageSize: Int): BrowseResult`
  - Expose `val reader: StreamMessageReader` on `HeldConnection` and `StreamSource`.
- **Core testing (`core-testing`):**
  - `FakeStreamMessageReader`: supports in-memory message store per stream, chunk-boundary discard emulation, timestamp binary search, and bounds calculation.
- **Data layer (`data-rabbit`):**
  - `RabbitMqStreamMessageReader`:
    - Reads stream bounds via `queryStreamStats(stream)` (`firstOffset` and last offset derived from `firstOffset + messages - 1`).
    - Uses `Environment.consumerBuilder().stream(stream).noTrackingStrategy()` with an ephemeral unnamed consumer.
    - Sets start offset using `OffsetSpecification.offset(n)`, `OffsetSpecification.first()`, or `OffsetSpecification.timestamp(epochMs)`.
    - Discards messages delivered before requested start offset (chunk boundary handling).
    - Collects up to `pageSize` messages or until an idle timeout (~1.5–2.0 s) expires, then cancels/closes the consumer immediately.
  - Real broker unit / integration tests:
    - `@broker @safety` scenario: browsing never alters consumer tracking entries or message count.
    - Reading messages in order and pagination across chunk boundaries.
- **Headless MCP server (`mcp-server`):**
  - `caerbannog_browse_stream` tool: exposes paged message inspection to AI agents with safety bounds and payload length capping.

### Out
- Compose UI desktop screens (M3-03).
- Full `MessageBrowserViewModel` state machine and user interactions (M3-02).
- Super stream multi-partition browser coordinator (M3-04).
- Modifying broker state or consumer offsets.

## Headless MCP Check
- **Evaluation:** Evaluated and approved. AI developer agents frequently need to inspect messages in streams to verify publish pipelines, debug consumer lag, or troubleshoot payload formats.
- **Tool specification:**
  - Name: `caerbannog_browse_stream`
  - Description: "Browse messages of a RabbitMQ stream page-by-page starting from first, last, offset, or timestamp without disturbing real consumers."
  - Input Schema:
    - `profile` (string, required): Saved connection profile.
    - `stream` (string, required): Stream name.
    - `vhost` (string, optional): Virtual host.
    - `start` (string, optional: "first", "last", or numeric offset / ISO-8601 timestamp; default: "first").
    - `limit` (integer, optional: 1..100, default: 20).
  - Safety constraints: Uses `noTrackingStrategy()`, payload preview truncated at safe length (e.g. 4KB), never reading queue messages.

## Safety & Rules
- **Safety Rule:** Browsing consumers MUST be unnamed and configured with `noTrackingStrategy()`.
- **No Queue Reading:** Never read from classic or quorum queues.
- **Resource Cleanup:** Every browsing consumer must be closed immediately after completing the page or upon timeout.

## Definition of Done
1. Core models (`StreamMessage`, `BrowseStart`, `BrowseResult`, `StreamMessageReader`) and contracts authored.
2. `FakeStreamMessageReader` implemented in `core-testing` and tested.
3. `RabbitMqStreamMessageReader` in `data-rabbit` passes unit and `@broker` safety tests against a real RabbitMQ 4 broker.
4. `caerbannog_browse_stream` tool exposed in `mcp-server` and verified with unit tests.
5. Fast test suite and test discovery guard green.

## Status

### Step 1: Solution architect brief (2026-11-06)
- Brief established with Headless MCP check, scope, rules, and acceptance criteria.
- Branch `m3-01-browser-gateway-and-reader` created and active.

### Step 2: Test developer RED state (2026-11-06)
- Core contract tests written in `StreamMessageReaderContractTest` (10 tests) and `StreamSourceMessageReaderTest`.
- Authored real broker test suite `RealBrokerStreamMessageReaderTest` testing safety rule (unnamed consumer, `noTrackingStrategy()`, zero offset mutations, unchanged `messages` count), in-order reading across page boundaries, and prefix drop when starting inside a chunk.
- Recorded initial RED state against real TLS RabbitMQ 4 container.

### Step 3: Senior developer GREEN state (2026-11-06)
- Implemented `RabbitMqStreamMessageReader` in `data-rabbit`:
  - Ephemeral consumer lifecycle: collects up to `pageSize` messages, shuts down upon completion or idle timeout.
  - Prefix drop handling: drops chunk boundary messages delivered before requested start offset.
  - Bounds resolution with real-time stream protocol `queryStreamStats` and fallback handling for management API statistics lag.
- Wired reader into `RabbitMqHeldConnection` and `RabbitMqConnectionGateway`.
- Implemented `caerbannog_browse_stream` MCP tool in `mcp-server` with input schema validation, payload truncation at safe size (4KB), and comprehensive test coverage in `McpServerTest`.
- Passed all 3 real broker integration tests in `RealBrokerStreamMessageReaderTest` (38.3s).

### Step 4: Test developer verify & mutation check (2026-11-06)
- Mutation test performed: chunk prefix drop logic and safety rule validated; omitting chunk drop or offset check breaks contract tests.
- Full test suite verified green (`./gradlew test verifyTestDiscovery`) in 28s across all modules (`core`, `core-testing`, `data-rabbit`, `ui`, `mcp-server`, `desktopApp`, `acceptance-tests`, `dev-seed`).

### Step 5: Solution architect review (2026-11-06)
- **Architecture & Safety Review:**
  - Browsing consumer strictly uses `noTrackingStrategy()`, ensuring offset tracking tables and broker queue message counts are never altered by browsing.
  - Quorum queues and classic queues remain strictly protected (zero reading/purging).
  - Headless MCP integration adheres to tool standards and caps payload text.
- **Verdict:** READY FOR MERGE into `main` pending Product Owner approval.
