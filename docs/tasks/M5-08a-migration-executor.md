# M5-08a: management executor, conflicts, read-back and rerun safety

Milestone: M5    Depends on: M8-04 (merged), M5-07 (merged)    Size: M    Umbrella: `M5-08-real-migration-execution` (decisions, rerun table, scenario ownership)

## Goal
A migration is applied to a real 4.x broker by a real executor: each action is sent, read back, never overwrites anything with other settings, and a rerun after a failure is safe. No UI, no persistence, no folder (those are `M5-08b` and `M5-08c`).

## Why
No real executor exists (umbrella "Why"). Owner decision 2026-11-09: option A and "make rerun safe". CLAUDE.md: only the migration executor may send management PUT or DELETE, with a path allow-list; never `/get`, `/contents`, `/purge`; delete only with the Full control level.

## Scope
In:
1. **`ManagementHttp`** (`data-rabbit`, internal): add `put(url, body)` and `delete(url)`. Do not copy the 60-line catch ladder of `get`/`post`: extract one private `send(block)` that holds the transport rules (TLS per profile, D9a, no credential before the profile was chosen, classification of `SSLException`, timeouts, `IOException`, never a copied body) and let the four verbs use it. A source-grep guard test lists the callers of `put` and `delete` and fails for any other class than the migration executor.
2. **`ManagementTopologyMigrationExecutor`** (implements `TopologyMigrationExecutor`): per action as in the umbrella table. Requests come from `MigrationRequests.requestFor` (M8-04) for declares; deletes and the policy read are built in a sibling `MigrationDeleteRequests` with the same encoding rules (`ManagementRequests.encode`, dot-only segments as `%2E`). Split by concern, composed by the executor: `MigrationWriter` (send and map the status), `ConflictCheck` (policy equality: pattern, apply-to, priority, definition with numeric comparison, key order irrelevant), `ReadBack` (verify; one retry after a delay that is a named constant and an injected `suspend (Long) -> Unit`). Statuses map to success or a FIXED reason: 400 precondition gives "<name> exists with different settings" (the name only when short and plain, as M8-04's echo rule); 401 and 403 "not allowed"; 404 on a delete is success; 5xx and timeouts "the broker did not answer"; the broker's body is never copied. Streams (`declareStream`, `deleteStream`) call the held connection's `StreamManager`; an "already exists" refusal is a no-op; the read-back is the stream list.
3. **Path allow-list.** The executor can only produce requests to `/api/queues/`, `/api/exchanges/`, `/api/bindings/`, `/api/policies/`, `/api/operator-policies/` (and `GET`s of the same for read-back and the policy read); a test asserts it for every action x every hostile name (the 24 hostile names of the M8-04 verify) and that no path contains `/get`, `/contents`, `/purge` or a raw dot segment or slash.
4. **`HeldConnection.migrationExecutor`**: `val migrationExecutor: TopologyMigrationExecutor? get() = null` on the interface (default null, like `manager`), implemented by the real gateway for a connection, exposed through `StreamSource` behind a guard that returns a refusing executor once the connection is no longer the current one (the pattern of `GuardedStreamManager`). A 3.x source keeps null: `M8-02` makes the detection; until then every connection gets one.
5. **Runner (`core`).** `MigrationRunner.dryRun` and `applyPending` take the connection's virtual host and BLOCK every action whose `vhost` differs: reason "The action belongs to virtual host <v>; this connection is for <c>" (the name only when short and plain). The level and permission checks stay. A `Failed` result stops the run, is reported as `ActionFailed(version, action, index, total, reason)` (add `index` and `total`, additive) and the migration is not recorded.
6. **File-level rerun rule (`core` parser).** `MigrationParser.parse` refuses a file that declares and deletes the same item (queue, exchange, stream, policy by kind, vhost and name; binding by vhost, source, destination and routing key): `Invalid("the file declares and deletes \"<name>\"; split it into two migrations")` (generic wording without the name when it is not short and plain). Implemented as one small `RerunSafety` check after the actions are read. If an existing test or fixture has such a file, the architect decides at step 2 (expected: none).
7. **Export (`MigrationsViewModel.exportCurrentTopology`).** Read every queue page (until `QueuePage.page == pageCount`); a failed catalog call (`NotAuthorised`, `Unreachable`, `Unexpected`, `BadPaging`) is shown as the error "The topology could not be read" and NO file is written (today it silently writes an empty migration).
8. **Live test** `RealBrokerM5MigrationTest` (`data-rabbit`, `@Tag("broker")`, Testcontainers `rabbitmq:4-management` like `RealBrokerM8MigrationRequestsTest`): "Apply a migration to a real broker", "Exported topology can be applied to an empty broker" (`@gate-M5`), conflict on a queue with other arguments (400, queue unchanged), policy equal (no PUT) and different (no PUT, unchanged), read-back, delete of an absent resource (404 is a no-op), binding delete with an empty and a non-empty routing key (measures the properties key form), an exchange-to-exchange binding, typed numbers on the wire, apply twice, rerun after a failure at action 2, an action built in code with name `..` answers 405 and creates nothing. Cleans up what it creates; never reads a queue's messages.
Out: persistence and folder (`M5-08b`), desktop wiring and UX (`M5-08c`), MCP (`M5-09`), a dry run that compares with the broker (idea).

## Acceptance
- BDD (`@fake-broker` with the REAL runner and executor over a fake HTTP layer and in-memory repositories; step glue written by the test developer; the suite `RunTopologyMigrationsAcceptanceTest` stops excluding `@m5-08a`): the 14 `@m5-08a` scenarios of `10` listed in the umbrella, plus "Applying a generated migration reads back every declared queue" in `16` (the filter of `RunMigrate3xMigrationModelAcceptanceTest` becomes `@m8-04 or @m5-08a`). Both `@broker @m5-08a` scenarios = `RealBrokerM5MigrationTest`. All older scenarios of `10` and `16` stay green unedited.
- Unit tests, tests first (`data-rabbit`: Ktor mock engine, JUnit 5; `core`: JUnit 5):
  - `ManagementHttpWriteTest`: `put` and `delete` use Basic auth and the profile's transport, classify errors like `get`, never copy a body; the caller guard.
  - `MigrationWriterTest`: per action the exact method, path and body; the status table (200, 201, 204, 400, 401, 403, 404, 409, 5xx, timeout, `SSLException`) to success or a fixed reason; the reason never contains the response body (sentinel in the body).
  - `ConflictCheckTest`: policy absent, equal, different in each field (pattern, apply-to, priority, a definition value `5` versus `"5"`, a missing key, an extra key); numbers compare numerically (`5` equals `5.0`? decide in the test: whole versus decimal with the same value are equal); order irrelevant.
  - `ReadBackTest`: present, missing, mismatching argument (number versus string), retry once then fail, the delay is injected (no sleeps).
  - `MigrationExecutorTest`: the full action x outcome table of the umbrella, including "rerun after a failure at action k" with a scripted fake broker, "no request is a DELETE unless the action is a delete", streams through a fake `StreamManager`.
  - `ExecutorPathAllowListTest`: every action x hostile name gives an allowed path; the guard test for `/get`, `/contents`, `/purge`.
  - `MigrationRunnerVhostTest`: block on another vhost (dry run and apply), allowed on the same, the level and permission checks unchanged, `ActionFailed` carries index and total.
  - `RerunSafetyTest`: declare plus delete of the same queue, exchange, stream, policy, binding is refused; different names or different kinds are fine; the message names the item only when short and plain; an old fixture is unaffected.
  - `MigrationsViewModelExportTest` additions: 150 queues over several pages are all exported; a failing catalog gives the error and no file.
- Mutation checks to record: (a) the executor treats 400 as success; (b) skip the policy read before `PUT`; (c) skip the read-back; (d) the runner ignores the action vhost; (e) the executor issues a `DELETE` after a conflict; (f) `RerunSafety` disabled; (g) the export ignores the failure and writes a file; (h) the path allow-list accepts `/get`. Each must turn a named test red.
- `verifyTestDiscovery` green; the live test run once.

## Design notes
Ports stay in `core` (`TopologyMigrationExecutor`, `MigrationRunner`); the adapter is in `data-rabbit`; no network code in `core` or `ui`. The runner keeps its single apply loop. Failure texts are fixed, nothing is logged, nothing in this format is secret until `M8-12`. The delay for the read-back retry is injected so no test sleeps.

## UX
None here (state texts come from `ActionFailed`; the screen is `M5-08c`).

## MCP
None here (`M5-09`).

## Open points
1. 4.x `DELETE` of a binding: the properties key form (`~` for an empty routing key) is measured by the live test.
2. Whole versus decimal numbers with the same value in a policy comparison: the test pins the rule (recommended: equal when numerically equal).
3. A stream "already exists" refusal text from `StreamManager` is matched by what the real manager returns: the test developer reads `RabbitMqStreamManager` and pins the match.

## Definition of done
Tests first and passing, live test run once, no unrelated change, no new dependency (the existing HTTP client), the spec subsection "Running migrations for real" still true, mutation checks recorded.

## Status
- Step 1 (architect): DONE 2026-11-09. Not started.
