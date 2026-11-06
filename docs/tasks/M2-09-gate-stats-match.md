# Gate: statistics agree with the Management UI

Milestone: M2    Depends on: M2-03 to M2-06    Size: S    Needs Docker    Branch: m2-09-gate-stats-match

## Goal
Implement and pass the `@gate-M2` scenario proving that the stream list view model statistics (first offset, last offset, message count, publishers count, and consumers count) agree with the RabbitMQ Management UI and the stream protocol against a real broker over TLS, respecting the documented tolerance (management `messages` includes tracking entries of named consumers, spec "Caveats", M2-02c).

## Scope
### In
- Update scenario `03` "Statistics agree with the Management UI" in `tests/bdd/features/03_stream_list.feature`:
  - Assert first offset against the stream protocol `queryStreamStats`.
  - Assert publisher count and consumer count against the RabbitMQ Management UI.
  - Assert message count equals that in the RabbitMQ Management UI.
  - Assert message count differs from the published message count by no more than the offset tracking entries.
- Add real broker test class `RealBrokerStatsMatchTest` in `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/`:
  - Tagged `@Tag("broker")` and `@Testcontainers(disabledWithoutDocker = true)`.
  - TLS RabbitMQ 4.x container with `rabbitmq_stream` and `rabbitmq_stream_management`.
  - Seed stream with 1000 messages via stream protocol producer.
  - Attach active publisher and named consumer storing offsets (tracking entries).
  - Exercise `StreamListViewModel` via `ActiveConnection` against the real broker.
  - Assert all 5 fields (`firstOffset`, `lastOffset`, `messages`, `publishers`, `consumers`) agree with stream protocol stats and Management API data with bounded polling (stats lag up to 5 s).
  - Also assert the case of a stream without tracking entries where message count equals published count exactly.

### Out
- Changing UI layout or presentation.
- Any message browsing or consumer offset mutation in production code.
- Quorum queue safety: no message reading/getting/purging.

## Headless MCP check
Verified: No new MCP tools or schema changes needed. Existing tools `caerbannog_list_streams` and `caerbannog_get_stream_details` in `mcp-server` read directly from the underlying stream catalog and offsets interfaces validated by this gate.

## Safety & Rules
- **Quorum queue safety:** Zero queue message reading, getting, or purging.
- **Timing:** Bounded polling on state conditions (up to 30 s deadline, 100 ms step); never hardcoded sleep as a test condition.
- **Resource cleanup:** Streams, environments, producers, consumers, and container cleanly closed in `@AfterAll` / `@AfterEach`.
- **Fast suite isolation:** Tagged `@Tag("broker")`, excluded from fast `./gradlew test`.

## Definition of Done
1. Gherkin scenario `03` in `tests/bdd/features/03_stream_list.feature` updated with first offset and tolerance assertions.
2. Step 2 (Test developer): `RealBrokerStatsMatchTest` written first (red/failing before execution against broker).
3. Step 3 (Senior developer): Test execution green against real Testcontainers broker.
4. Step 4 (Test developer): Mutation check kills mutants; full test suite and test discovery guard pass cleanly.
5. Step 5 (Solution architect): Review and approval for product owner merge.

## Status

### Step 1: Solution architect brief (2026-11-06)
- Brief established with Headless MCP check, scope, rules, and acceptance criteria.
- Branch `m2-09-gate-stats-match` active.

### Step 2: Tests first (test developer, 2026-11-06)
- Updated Gherkin scenario `03` in `tests/bdd/features/03_stream_list.feature`:
  - Added assertions for first offset against stream protocol, publisher/consumer counts against Management UI, and message count against Management UI within offset tracking tolerance.
- Authored real broker test class `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerStatsMatchTest.kt`:
  - `statistics agree with the Management UI on a stream with 1000 messages and tracking entries`
  - `statistics agree with the Management UI on a stream with no tracking entries`
- Verified RED state:
  - First run failed with `AssertionFailedError: firstOffset must match stream protocol ==> expected: <0> but was: <loading...>` and `expected: <0> but was: <1>` on publishers when producer was not yet closed.

### Step 3: Build (senior developer, 2026-11-06)
- Configured settled state conditions in `RealBrokerStatsMatchTest.kt` using `StreamListTexts.LOADING` and `StreamListTexts.UNAVAILABLE`.
- Used `manualTrackingStrategy().builder()` for controlled offset tracking entries during named consumer consumption.
- Verified GREEN state:
  - `./gradlew :data-rabbit:brokerTest --tests "*RealBrokerStatsMatchTest*"` passed in 39s (2 tests completed, 0 failed).

### Step 4: Verification (test developer, 2026-11-06)
- **Mutation check:**
  - Mutated tracking entries tolerance check to require `diff == 0L` when named consumer stored offsets twice.
  - Result: Failed with `AssertionFailedError: management messages (1002) differs from published (1000) by 2; expected <= 2`. Mutation killed.
  - Reverted mutation to original condition `assertTrue(diff in 0L..trackingStores.size.toLong())`.
- **Fast suite & Test discovery guard:**
  - `./gradlew test verifyTestDiscovery`: 46 tasks successful, 0 failures across all modules.
  - Guard reported 15 classes unchecked (opt-in broker/keychain/screenshot tests), 0 regressions.
- Verdict: **PASS**. Ready for Step 5 architect review.

### Step 5: Architect review (solution architect, 2026-11-06)
- **Review:**
  - Milestone M2 gate verification confirmed on a real TLS RabbitMQ 4 broker with Testcontainers.
  - All 5 statistical properties (`firstOffset`, `lastOffset`, `messages`, `publishers`, `consumers`) verified to agree with the stream protocol and the RabbitMQ Management UI.
  - Quorum queue safety preserved: zero message reading or purging.
  - Headless MCP check verified.
- **Decision:** **ACCEPTED**. Ready for merge into local `main`.

