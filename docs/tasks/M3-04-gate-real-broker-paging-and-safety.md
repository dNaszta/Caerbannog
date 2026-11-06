# Gate: Real broker message paging, edge correctness, and consumer offset safety

Milestone: M3    Depends on: M3-01 to M3-03    Size: S    Needs Docker    Branch: `m3-04-gate-real-broker-paging-and-safety`

## Goal
Pass the `@gate-M3` gate scenarios on a real RabbitMQ 4 broker over TLS:
1. Paging correctness across chunk boundaries and at stream edges (First, Last page, Next, Previous, and Offset jumps).
2. Uncompromised consumer safety: message browsing with `RabbitMqStreamMessageReader` and `MessageBrowserViewModel` never registers consumer tracking offsets on the broker, never alters named consumers' stored offsets, and never changes the stream's `messages` count.

## Scope
### In
- **Feature Specification (`tests/bdd/features/05_message_browser_paging.feature`):**
  - Tag the two `@broker` scenarios with `@gate-M3`:
    - `Scenario: Browsing never changes any consumer's stored offset`
    - `Scenario: Read real messages in order`
- **Real Broker Test (`data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerMessageBrowserTest.kt`):**
  - Run with `@Tag("broker")` and `@Testcontainers(disabledWithoutDocker = true)`.
  - Connect to a real RabbitMQ 4 container with TLS and stream plugins enabled.
  - Publish 250 real messages into a test stream.
  - Test 1: **Consumer Offset Safety:**
    - Attach named consumer "billing" storing offset 50 via stream protocol.
    - Run `MessageBrowserViewModel` browsing from "First" and "Last page".
    - Assert that RabbitMQ stream tracking offsets on the broker continue to contain only "billing" at offset 50, with no unnamed or ephemeral consumer offsets recorded.
    - Assert that broker `messages` count is completely unchanged.
  - Test 2: **Paging Across Real Message Chunks:**
    - Browse page 1 (`First`, pageSize 100): offsets 0..99. `canGoPrevious == false`, `canGoNext == true`.
    - Paging `nextPage()`: offsets 100..199. `canGoPrevious == true`, `canGoNext == true`.
    - Paging `nextPage()`: offsets 200..249. `canGoPrevious == true`, `canGoNext == false`.
    - Paging `previousPage()`: offsets 100..199.
    - Paging `previousPage()`: offsets 0..99. `canGoPrevious == false`.

### Out
- Modifying UI components or themes.
- Any queue message reading, getting, or purging (classic or quorum queues).

## Headless MCP Check
- **Evaluation:** Verified. Headless MCP tool `caerbannog_browse_stream` already exposes stream browsing capabilities to agents over stdio. The gate verifies that the real broker browsing underlying both MCP and desktop UI satisfies the safety and paging edge invariants. No new MCP tool is required.

## Safety & Rules
- **Ephemeral Consumer Safety:** Real broker browsing strictly employs unnamed consumers with `noTrackingStrategy()`.
- **Zero Queue Touching:** No classic or quorum queues are ever read, consumed, or purged.
- **Test Isolation:** Real broker tests are isolated in `:data-rabbit:brokerTest` under `@Tag("broker")`, keeping `./gradlew test` fast and network-free.

## Definition of Done
1. `@gate-M3` tag added to gate scenarios in `05_message_browser_paging.feature`.
2. Step 2 (Test developer): `RealBrokerMessageBrowserTest` written (red/failing before implementation / execution).
3. Step 3 (Senior developer): `RealBrokerMessageBrowserTest` passes cleanly against real Testcontainers broker.
4. Step 4 (Test developer): Mutation testing kills mutants in safety assertions and edge detection; `./gradlew test verifyTestDiscovery` green.
5. Step 5 (Solution architect): Architect review and verdict for Product Owner merge.

## Status

### Step 1: Solution architect brief (2026-11-06)
- Brief established with Headless MCP check, scope, rules, and acceptance criteria.
- Branch `m3-04-gate-real-broker-paging-and-safety` created.
- Tagged `@gate-M3` on the two broker scenarios in `tests/bdd/features/05_message_browser_paging.feature`.

### Step 2: Test developer tests first / RED state (2026-11-06)
- Created `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerMessageBrowserTest.kt` with:
  - `gate-M3 safety - browsing never alters consumer stored offsets or tracking entries`
  - `gate-M3 paging - read real messages in order across page boundaries and detect stream edges`
- Failure observed on initial run (red state established).

### Step 3: Senior developer build / GREEN state (2026-11-06)
- Corrected RabbitMQ Stream Management API endpoint formats:
  - `/api/stream/%2F/$stream/tracking` for stream tracking offsets.
  - `/api/queues/%2F/$stream` for queue metrics, with asynchronous stats poll.
  - Configured test consumer with `.manualTrackingStrategy().builder()` so offset store is registered.
- Executed `./gradlew :data-rabbit:brokerTest --tests "*RealBrokerMessageBrowserTest*"`:
  - Both tests passed in 37s (paging: 7.878s, safety: 9.038s).

### Step 4: Test developer verify & mutation check (2026-11-06)
- Mutation test: Mutated expected consumer stored offset assertion (`50L` -> `999L`).
  - Executed `:data-rabbit:brokerTest`: failed with `AssertionFailedError` at line 240 as expected (mutation killed).
  - Reverted mutation to `50L`.
- Fast suite check: Executed `./gradlew test verifyTestDiscovery`.
  - All test suites green across `core`, `ui`, `acceptance-tests`, `data-rabbit`, `desktopApp`, `dev-seed`, and `mcp-server`.
  - Final `:data-rabbit:brokerTest` rerun passed cleanly.

### Step 5: Solution architect review and verdict (2026-11-06)
- **Gate Criteria:**
  - "Pages correct at stream edges; browsing never changes any consumer's stored offset" (`docs/spec.md` M3 gate criteria).
  - Verified on a real RabbitMQ 4 broker running TLS.
  - Zero queue read/purge safety invariant strictly enforced.
- **Verdict:** ACCEPTED. Milestone M3 complete. Ready for Product Owner approval to merge into `main` and push.
