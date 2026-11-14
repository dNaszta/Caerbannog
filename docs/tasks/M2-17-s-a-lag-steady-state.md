# S-A live test: assert what the broker guarantees about the lag, not a value it varies

Milestone: M2 (hardening of the M2-06a tests)    Depends on: M2-06 (merged)    Size: S (test-only)

## Goal
`RealBrokerClientsTest` scenario S-A is true on a real broker in its steady state, not only in the first statistics window, and it still protects what the lag ruling promises: the app shows the broker's `offset_lag` exactly (nothing added, nothing taken away, never an invented 0), and "Caught up" is reachable on a real broker (a consumer that has read everything on a stream where nobody stored an offset reads 0).

## Why
`docs/spec.md` "Publishers and consumers tab" and the Consumers row of "Management API facts" (both corrected with this brief); the lag ruling of 2026-11-05 in `docs/tasks/M2-06-publishers-consumers.md` (Status); `tests/bdd/features/04_publishers_consumers.feature`. S-A failed in a CI-like run with "consumer 'null' reports offset_lag 2, expected 0..1" and passes only when its samples land in the first statistics window (the clients test developer's report of 2026-11-14).

## Decision (architect, 2026-11-15)
**The test over-asserted; the screen's rule does not change.** The architect ran the finding independently (throwaway Java probe against a `rabbitmq:4-management` container, the same set-up as S-A, the management consumers endpoint read every second for 30 s) and the live test itself once:

| Set-up (100 messages) | First window (about 0 to 5 s) | Steady state (from the second statistics refresh, about 5 s on) |
| --- | --- | --- |
| Four consumers as in S-A (unnamed, plain, auto-storing 10 times, other application storing 3 times), named producer | `offset` 100, `offset_lag` 0 for three consumers; the other application's consumer 101 and 1 | all four `offset` 99, `offset_lag` 2, unchanged for 25 s |
| Unnamed and plain consumers only, unnamed producer (12 s) | 100 and 0 | 99 and 0 |
| Same, named producer (12 s) | 100 and 0 | 99 and 0 |
| Unnamed, plain and one auto-storing consumer (10 stores), unnamed producer (12 s) | 100 and 0 | all three 99 and **4** (the unnamed and the plain consumer too) |

Reading: the lag is a property of the stream's tail, not of the consumer. Once the broker has run its second statistics refresh, every consumer that has read to the end shows the same number, the count of offsets after the last message, and stored offsets of **any** application occupy offsets. A consumer that never stores reads the same 2 or 4 as one that does. The first window is only a not yet settled snapshot (`offset` is the next offset, lag 0). The ruling's facts (1) "unnamed and non-storing consumers settle at 0" and (3) "they stay 0 although another application stored" were measured in that first window only; (2) "at most the number of its own stores plus 1" is wrong for a non-storing consumer. This is what the spec already said about the value ("varies between runs, not derivable from the API"); the test pinned a value the spec said not to pin.

What the ruling actually promises (and this task keeps pinned): (a) the number is the broker's, exactly; (b) "Caught up" only at 0. (b) is pinned at unit level (`StreamClientsLagInvariantTest`, `StreamClientsViewModelRowsTest`, `ConsumerStatus`), (a) at fixture level (`ManagementJsonTest`) but nowhere against a real broker, which is what S-A is for.

**Screen behaviour:** the rule and the numbers on screen do not change. Two things the owner should know and one wording that needs the owner (not decided here, brief `M2-17b-lag-hint-wording.md`, status blocked):
- On a stream where any application stores offsets (named consumers do, that is what the name is for) the tab shows "N behind" for every consumer, also when all have read everything; "Caught up" shows on streams without stored offsets. That is the ruling working as written (never claim exactness), but it reaches further than the ruling's text suggested.
- The tooltip says "a consumer that stores its offset can read a few behind", which is true but suggests a consumer that never stores is exempt.
- The number cannot be corrected on our side: the management `messages` count includes the tracking entries (spec "Message count"), so data messages and tracking offsets cannot be told apart from the API. The ruling's "no subtraction" stands, now with a stronger reason.

## Scope
In (test-only, `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RealBrokerClientsTest.kt`, scenario S-A; the test is the work, so "red first" is shown by the mutation runs in Acceptance):
1. Rename the test so the name says what it asserts, for example `S-A the lag is the brokers number: 0 on a stream without stored offsets, within 0 and the stores plus 1 on a stream with them, and the catalog adds and subtracts nothing` (the traceability row in `tests/bdd/README.md` is updated by the test developer with it).
2. **Stream with stores (the existing set-up, unchanged: four consumers, named producer, 100 messages, settled by `rows.size == 4 && names present && offset >= 99`, the bounded 20 s wait for a lag of at least 1 stays "recorded, not required").**
   - Replace the per-consumer bound by one stream-wide bound for every row of every sample: `0 <= offset_lag <= totalStreamStores + 1`, where `totalStreamStores` is the existing sum (10 + 3 = 13, so 14; the +1 is the ruling's slack). The failure message names the consumer and says that every application's stored offsets count.
   - Delete the exact-0 assertions for the unnamed and the plain consumer ("never stores an offset ... must not borrow the lag of another application's stores"), and the sentence in the comments that says the same. The "(3) borrow" claim is false by the broker's design.
   - **Add the pass-through check:** read the same endpoint a second way inside the test (`ManagementHttp` and the consumers URL of `ManagementRequests`, parsed with kotlinx.serialization straight from the body, rows of this stream keyed by `properties.name`, the unnamed one under a fixed key) and wait by value (`awaitUntil`, the existing 40 s bound) until a catalog read and a raw read agree on `offset` and `offset_lag` of every consumer. Do this twice: for the settled rows and again after the bounded wait for a lag of at least 1 (in the finding the raw value is 2 by then, so a mapper that returned 0 or subtracted would not agree). Do **not** require a non-zero value anywhere. The failure message prints both maps.
   - Keep the `SPIKE-A` print lines and add the raw rows to them; add one print of the steady state (the rows after the bounded wait) so the next reader sees the numbers.
3. **Second stream without any stores (new, in the same test method or a second method `S-A2`, the developer's choice):** one named producer (as above), one unnamed consumer and one named consumer, both with `noTrackingStrategy()`, each reads the 100 messages. Settle by `rows.size == 2 && names present && offset >= 99`, then sample the catalog every 500 ms for 12 s (two statistics intervals of 5 s plus margin, a bounded observation, not a wait for a state; say so in a comment) and assert in **every** sample, first window and steady state alike: both rows `offset_lag == 0`. This replaces the old exact-0 claim with the one the broker really gives and is what shows that "Caught up" exists on a real broker.
   - Stop condition: run this part three times before pinning it. If any steady-state sample of a stream with no stores reads a lag above 0, stop and tell the architect: "Caught up" would be unreachable and the ruling needs another look.
4. Comments: the class comment and the method comment drop "settle at lag 0 ... nobody borrows another application's lag" and say what is now pinned and why (first window versus steady state, stream-wide lag).

Out (do not change):
- Any production code, `StreamClientsTexts`, the view model, `ManagementJson` (see the idea below), the `04` `@fake-broker` scenarios, the unit tests that pin "Caught up at 0 only".
- No tolerance constant, no "settle at 99" helper that depends on the broker's 100-to-99 flip of `offset` (the settled predicate stays `>= 99`, true in both windows), no required `> 0` anywhere (the broker's value is not derivable).
- The other tests of `RealBrokerClientsTest` (S-B, S-C, the counts tests, the visibility tests) and the shape of the view model test there (one screen at a time, kept by `M2-16`).

## Acceptance
- BDD (written with this brief, `@broker`, bound to S-A): `tests/bdd/features/04_publishers_consumers.feature` / "A consumer that has read everything on a stream where nobody stored an offset is caught up" (part 3) and / "The number of messages behind is the number the broker reports" (part 2, pass-through).
- BDD, unchanged and green: `04` / "A consumer that stored its offsets can read a few behind without being behind" and the lag outline (`@fake-broker`).
- Live: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerClientsTest*'` green five times in a row (a clean broker each time, timing varies); S-A also green on a loaded machine (broker at 0.3 CPU) once.
- Step 4 (test developer), by mutation on a real broker, source restored with `git checkout` each time: (a) `ManagementJson.parseConsumers` returns `offsetLag = 0` always: S-A must go red in the pass-through check; (b) it returns `max(0, lag - 2)`: red; (c) it returns `lag + 1`: red; (d) the 12 s observation window cut to 2 s: the test still passes (documents that part 3 is only as sharp as its window; no action).

## Design notes
Module `data-rabbit`, test code only, one broker per class (Testcontainers `rabbitmq:4-management` as today). `ManagementHttp` and `ManagementRequests` are internal to the module and visible to its tests (as in `ManagementHttpClosedConnectionTest`). The raw read exists only to compare against the catalog: it must not reuse `ManagementJson` for the comparison.

## UX
None.

## MCP
None (test-only). The MCP stream tools show the same `offset_lag` through the same catalog; no change in what they return.

## Open points
- **Owner, wording (does not block this task):** `docs/tasks/M2-17b-lag-hint-wording.md`.
- **Idea, no brief yet (`M2-17c`):** `ManagementJson.parseConsumers` maps a missing or non-numeric `offset_lag` to 0 (`row.long("offset_lag") ?: 0`, also `offset`, `consumed`, `credits`), so a settled row for which a broker sent no lag would read "Caught up", which is the invented 0 the ruling forbids. No known bug: real 4.x brokers always send the field (M2-02 spike) and rows with an empty `connection_details` are shown as "updating". A fix would treat a row without `offset_lag` or `offset` as settling. Size S, core and data-rabbit; do it when the area is next touched or when a broker version omits the field.
- Not verified by the architect: more than one run of the three no-store and store variants (table above: one run each, plus the test developer's 8 of 8 for the four-consumer case); the lag with several producers or a super stream; clusters (spec: everything measured on one node and one version).

## Definition of done
The test changed as listed, green five times, mutations (a) to (c) red, no production file changed, the `tests/bdd/README.md` row updated, the spec text left as corrected by this brief.

## Status
- Step 1 (architect, 2026-11-15, branch `arch-http-retry-and-lag-s-a`): decision made (the test over-asserted; screen rule unchanged), brief written; spec Consumers row and tab paragraph corrected, `04` header and two `@broker` scenarios written, lag ruling note added to `M2-06-publishers-consumers.md`. Not started. Owner question: only the wording in `M2-17b`.
