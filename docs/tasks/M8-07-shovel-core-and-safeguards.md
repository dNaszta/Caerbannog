# Shovel forwarding: core interface, planner, service, fake

Milestone: M8    Depends on: M8-00 (S-F, S-J; merged), M8-06 (merged; the 3.x connection and the held 4.x connection exist)    Size: M (not split)

LEAN (owner, 2026-11-11): build only what the workflow and the safety rules need. No extra polish, no speculative hardening. The goal is forwarding from a 3.x queue to a 4.x queue working soon (M8-07, M8-08, M8-09).

## Goal
`core` holds the types, the pure rules and a small service that decide, from a dry run and the permissions, whether forwarding of ONE queue may start, and drive its state; every safeguard of the spec "Forwarding messages with a dynamic Shovel" is enforced in `core` (not only in the UI). `ShovelGateway` has exactly four operations. A fake exists. No network.

## Why
Spec "Forwarding messages with a dynamic Shovel" (safeguards 1 to 10), `docs/tasks/M8-00-spike-results.md` S-F and S-J. This is the one place the app moves messages, so the rules are written and tested before any request exists (M8-08 sends them, M8-09 shows them).

## Scope
In (package `dev.caerbannog.core.forwarding`, module `core`, `commonMain`, no `java.*`):

### Types (file `ForwardingModel.kt`)
```kotlin
enum class SrcDeleteAfter { QUEUE_LENGTH, NEVER }              // default QUEUE_LENGTH (P5); wire names "queue-length" / "never" are M8-08's
enum class AckMode(val wireName: String) { ON_CONFIRM("on-confirm") }   // the only value, v1 (P9); no request field can change it
enum class DestinationQueueType { QUORUM, STREAM, CLASSIC, OTHER }

/** Reuse of migrate3x.SecretValue (plain class, redacting toString, no equals/copy). */
class ShovelSourceCredentials(val username: String, private val password: SecretValue) {
    fun passwordForRequest(): String = password.expose()      // the ONE reader: only M8-08 builds the PUT from it
    override fun toString() = "ShovelSourceCredentials($username, ***)"
}

/** What the dialog shows and the broker running the Shovel reaches. There is NO password field, so no toString/copy can leak one. */
data class ShovelAddress(val tls: Boolean, val username: String, val host: String, val port: Int, val vhost: String) {
    val scheme: String get() = if (tls) "amqps" else "amqp"     // no fallback: the scheme follows the profile switch only (D9a)
    override fun toString() = "$scheme://$username@$host:$port/${percentEncode(vhost)}"   // vhost "/" prints %2F, as it is sent
    companion object {
        /** [credentials] null = the profile user. Default port 5671 with TLS, 5672 without; host and port come from the dialog (P8). */
        fun forProfile(profile: ConnectionProfile, host: String = profile.host, port: Int? = null, credentials: ShovelSourceCredentials? = null): ShovelAddress
    }
}

data class ForwardingSource(
    val connectionName: String,           // the 3.x profile name (read only; the app never writes to it)
    val address: ShovelAddress,           // what the 4.x broker uses to reach it
    val vhost: String, val queue: String,
    val credentials: ShovelSourceCredentials?,   // null: the profile user and the stored profile password (M8-08 reads SecretStore)
)

/** One queue, one request: there is no list form anywhere (safeguard 9). */
data class ForwardingRequest(
    val source: ForwardingSource,
    val destinationConnection: String,    // the held 4.x connection
    val destinationVhost: String, val destinationQueue: String,
    val srcDeleteAfter: SrcDeleteAfter = SrcDeleteAfter.QUEUE_LENGTH,
) {
    val ackMode: AckMode get() = AckMode.ON_CONFIRM
    val shovel: ShovelRef get() = ShovelRef(destinationVhost, ShovelNaming.nameFor(source.queue))
}

data class ShovelRef(val vhost: String, val name: String)
object ShovelNaming { fun nameFor(sourceQueue: String): String = "caerbannog-migrate-$sourceQueue" }   // deterministic; names are percent-encoded by M8-08
```
"Not encrypted" is derived from `source.address.tls` by the planner. The destination TLS flag is not in the request: the destination side is the held 4.x connection and a credential-free local `amqp://` (S-F), so only the source address has a scheme.

### Gateway (file `ShovelGateway.kt`), exactly four operations, bound to the held 4.x connection
```kotlin
interface ShovelGateway {
    suspend fun dryRun(request: ForwardingRequest): DryRunOutcome       // GET only: source probe (one-shot, GET), destination queue, whoami, shovel list
    suspend fun start(request: ForwardingRequest): StartOutcome         // the one PUT of a dynamic shovel
    suspend fun status(shovel: ShovelRef): StatusOutcome                // GET /api/shovels only; never the parameter endpoints
    suspend fun remove(shovel: ShovelRef): RemoveOutcome                // DELETE of the shovel parameter only; never a queue
}
```
KDoc quotes the spec rule (the accepted exception, the "Not allowed, always" list). Nothing else: no get, consume, ack, purge, delete-queue, no list of parameters.

Outcomes (same file; no secret in any field, texts scrubbed by M8-08 before they get here):
```kotlin
data class QueueCounts(val ready: Long, val unacknowledged: Long)
sealed interface DestinationQueue { data object Missing; data class Exists(val type: DestinationQueueType) }
enum class ShovelRunState { STARTING, RUNNING, TERMINATED, OTHER }
data class ShovelStatus(val ref: ShovelRef, val state: ShovelRunState, val reason: String?, val forwarded: Long?, val remaining: Long?) // remaining null = "unlimited"

/** The facts of the dry run; no decision. */
data class DryRunResult(
    val source: QueueCounts,
    val destination: DestinationQueue,
    val shovelPluginsOn: Boolean,          // GET /api/shovels 404 = false
    val userTags: Set<String>,             // GET /api/whoami of the 4.x broker
    val existingShovel: ShovelStatus?,     // null when none (or when the status cannot be read: then the tags rule blocks)
)
sealed interface DryRunOutcome  { data class Done(val result: DryRunResult); data class SourceUnreachable(val connectionName: String); data object SourceNotPermitted; data object DestinationUnreachable; data class Failed(val reason: String) }
sealed interface StartOutcome   { data object Submitted; data class Refused(val reason: String) /* e.g. the AMQP TLS endpoint cannot be used (spec 8) */; data object NotPermitted; data object Unreachable; data class Failed(val reason: String) }
sealed interface StatusOutcome  { data class Listed(val status: ShovelStatus); data object Absent; data object NotPermitted; data object Unreachable; data class Failed(val reason: String) }
sealed interface RemoveOutcome  { data object Removed; data object NotFound; data object NotPermitted; data object Unreachable; data class Failed(val reason: String) }
```

### Planner (file `ForwardingPlanner.kt`, pure, one small rule per safeguard)
```kotlin
data class ForwardingContext(val level: TopologyControlLevel, val topologyUnlocked: Boolean)   // of the held 4.x connection (effective: level AND the configure permission)

sealed interface ForwardingBlock {
    data object TopologyLocked           // level Read-only OR no configure permission: "topology changes are locked on <connection>"
    data object NoPolicymakerTag         // needs policymaker or administrator
    data object CannotReadStatus         // lacks monitoring and administrator: progress and failure could not be seen (DECIDED below)
    data object ShovelPluginsOff         // names the two plugins: rabbitmq_shovel and rabbitmq_shovel_management
    data object DestinationMissing       // "apply the migration that declares <queue> first"
    data class  ShovelExists(val status: ShovelStatus)   // shows its state instead of a new start; never overwritten
}
sealed interface ForwardingWarning {
    data class DestinationNotQuorumOrStream(val type: DestinationQueueType)
    data object KeepsShovelAndCredentials  // only for SrcDeleteAfter.NEVER (P5)
    data class  NotEncrypted(val connectionName: String)   // source address plain (D9a)
}
data class ForwardingPlan(val blocks: List<ForwardingBlock>, val warnings: List<ForwardingWarning>) { val canStart: Boolean get() = blocks.isEmpty() }

object ForwardingPlanner {
    /** Before any request: the level rule only. Used to disable the action without sending anything. */
    fun gate(context: ForwardingContext): List<ForwardingBlock>
    /** gate + the rules over the dry run. Blocks in this fixed order: TopologyLocked, NoPolicymakerTag, CannotReadStatus, ShovelPluginsOff, DestinationMissing, ShovelExists. */
    fun plan(context: ForwardingContext, request: ForwardingRequest, facts: DryRunResult): ForwardingPlan
}
```
Tag rules: create = `"policymaker" in tags || "administrator" in tags`; read status = `"monitoring" in tags || "administrator" in tags` (S-F). Warnings follow the same order; a Missing destination gives the block, never the warning.

### Confirmation (file `ForwardingConfirmation.kt`, plus the texts in `ForwardingTexts.kt`)
```kotlin
data class ForwardingConfirmation(val lines: List<String>, val requiredText: String /* the source queue name, typed */, val notEncrypted: Boolean)
object ForwardingConfirmations { fun of(request: ForwardingRequest, plan: ForwardingPlan): ForwardingConfirmation }
```
The lines are the sentences the spec already decided (safeguard 1, 7 and P14), no new wording: names the source (connection, vhost, queue) and the destination (connection, vhost, queue); the messages are moved, leave the source queue when the destination confirms, the app cannot undo it; the source queue is not deleted; the shovel keeps credentials on the destination broker until it finishes or is removed; the source password is stored there in clear text and any administrator of that broker can read it; with `NEVER` the warning of P5; with a plain address "Not encrypted". The address in the lines is the `ShovelAddress.toString()` (no password). Constants live in `ForwardingTexts` so the guard test in step 4 can scan them.

### Service (file `ForwardingService.kt`): the safeguards in code
```kotlin
class ForwardingService(private val gateway: ShovelGateway, private val context: () -> ForwardingContext) {
    /** "Choose to forward": gate first (a locked connection sends NOTHING), then dryRun and plan. */
    suspend fun prepare(request: ForwardingRequest): Prepared
    /** Refuses unless [typedName] equals the source queue name exactly; runs the dry run AGAIN and refuses on any block (a shovel that appeared meanwhile is never overwritten); then one `gateway.start`. */
    suspend fun start(request: ForwardingRequest, typedName: String): StartResult
    /** One status poll. Applies [ForwardingProgress.next]; on a terminated shovel removes it once (cleanup) and returns Failed. A status read that fails keeps [previous]. */
    suspend fun poll(request: ForwardingRequest, previous: ForwardingState): ForwardingState
    /** The user's Remove. Refuses (no request) below FULL_CONTROL. */
    suspend fun remove(shovel: ShovelRef): RemoveResult
}
sealed interface Prepared { data class Ready(val plan: ForwardingPlan, val facts: DryRunResult, val confirmation: ForwardingConfirmation); data class Blocked(val plan: ForwardingPlan, val facts: DryRunResult?) /* facts null when gate blocked: no request was sent */; data class ProbeFailed(val outcome: DryRunOutcome) }
sealed interface StartResult { data object Started /* = submitted, state Started(0) */; data object NotConfirmed data class Blocked(val plan: ForwardingPlan); data class Refused(val reason: String); data class ProbeFailed(val outcome: DryRunOutcome); data class Failed(val reason: String) }
sealed interface RemoveResult { data object Removed; data object NeedsFullControl; data class Failed(val reason: String) }
```
`ProbeFailed` is returned for the non-`Done` dry run outcomes; nothing starts.

### Progress model (file `ForwardingProgress.kt`, pure; the state M8-09 shows)
```kotlin
sealed interface ForwardingState {
    data class Started(val absentPolls: Int = 0)                     // submitted; the PUT answers 201 without checking (S-F)
    data class Running(val forwarded: Long, val remaining: Long?)    // remaining null = unlimited (NEVER)
    data object Finished                                             // the Shovel removed itself (S-J)
    data class Failed(val reason: String, val cleanedUp: Boolean)
}
object ForwardingProgress { fun next(previous: ForwardingState, polled: StatusOutcome): ForwardingState }
```
Rules (decided): `Listed` with state TERMINATED gives `Failed(reason)`; any other listed state gives `Running(forwarded ?: 0, remaining)`. `Absent` after `Running` gives `Finished`. `Absent` after `Started` stays `Started` for the FIRST absent poll (the shovel may not be listed yet; not measured, M8-08's live test checks it) and gives `Finished` at the second in a row. A read that is `NotPermitted`, `Unreachable` or `Failed` keeps the previous state. `Finished` and `Failed` are final. "Moved about N of M": N = forwarded, M = the source `ready` of the dry run; the screen (M8-09) computes the text. After `Finished` the screen calls `prepare`-style `dryRun` again to count what stayed on the source (M8-09, no new gateway method).

Remove rules: the user's Remove needs `FULL_CONTROL` and `topologyUnlocked` (P4). The cleanup after `Failed` is the one remove allowed at `DECLARE_AND_EDIT` (the level that created the shovel): it removes only the Shovel the app itself just created and leaves no stored password; decided below. Both only ever call `gateway.remove`; there is no queue deletion anywhere.

### Fake (module `core-testing`, `dev.caerbannog.core.forwarding.fakes.FakeShovelGateway`)
Scripted facts: `sourceQueue(connection, vhost, queue, ready, unacked = 0)`, `destinationQueue(vhost, queue, type)` / none, `plugins(on)`, `userTags(vararg)`, `existingShovel(status)`, `appearAfterDryRun(status)` (a shovel that exists from the second dry run on), `statusScript(vararg StatusOutcome)` (one per poll), `startOutcome(StartOutcome)`, `removeOutcome(...)`. It records `calls: List<ShovelCall>` (sealed: `DryRun`, `Start(request)`, `Status(ref)`, `Remove(ref)`), the only way to see what happened. Honest about S-F: `start` on an existing name replaces it (so a test shows the service never lets that happen).

Out: HTTP (`M8-08`), UI, texts on screen and the typed-name field (`M8-09`), the MCP status tool (`M8-10`), anything about the profile's AMQP port field (asked in the dialog, spec 8).

## Acceptance
- BDD: `tests/bdd/features/17_forward_messages_with_shovel.feature`, tag `@m8-07 @fake-broker`; suite `RunForwardingCoreAcceptanceTest` (package `dev.caerbannog.acceptance.forwarding`, selects `17_...feature`, filter `@m8-07 and @fake-broker`; steps in `ForwardingCoreSteps`, world `ForwardingWorld` with `ForwardingService` over `FakeShovelGateway`; the Background binds a `ConnectionProfile` for "legacy-eu" (host `legacy.example`, TLS on) and "new-eu"). 18 existing scenarios are tagged `@m8-07` and 5 new ones are added (see the table). Tags `@m8-08` (3: credentials never shown or logged, never reads the parameters, destination without credentials) and `@m8-09` (6: the five listed, plus the new "The screen says why forwarding is blocked and that a plain connection is not encrypted") belong to the later tasks and are not bound here.

| Scenario (feature 17) | Proves in M8-07 |
| --- | --- |
| A dry run shows what forwarding would do and sends nothing | `prepare` calls only `DryRun`; counts, destination type, plugins, no shovel; `calls` has no `Start` |
| Forwarding is blocked when the destination queue does not exist | `DestinationMissing`; `start` refuses |
| A destination that is not a quorum queue gets a warning | `DestinationNotQuorumOrStream(CLASSIC)` |
| The confirmation names both queues and the consequences | `ForwardingConfirmations.of` lines (source, destination, moved, not deleted, credentials kept); `requiredText` = queue name |
| A wrong typed name starts nothing (new) | `start` with "order" gives `NotConfirmed`, `calls` has no `Start` |
| Starting creates one confirmed dynamic shovel on the 4.x broker | exactly one `Start`, request `ackMode` ON_CONFIRM, queues and shovel name `caerbannog-migrate-orders`, nothing in `calls` names the source connection except the request |
| The app itself never reads or settles queue messages while forwarding | structural test: `ShovelGateway` declares exactly `dryRun`, `start`, `status`, `remove`; `calls` holds only those kinds |
| Removing the shovel never deletes the source queue | `remove` gives one `Remove`; no other call kind exists |
| Removing a shovel needs Full control | at DECLARE_AND_EDIT `remove` gives `NeedsFullControl`, `calls` has no `Remove` |
| Removing a failed shovel does not need Full control (new) | `poll` of a terminated shovel at DECLARE_AND_EDIT calls `Remove` once, state `Failed(reason, cleanedUp = true)` |
| A user without the policymaker tag cannot forward | `NoPolicymakerTag` |
| A broker without the shovel plugins cannot forward | `ShovelPluginsOff` |
| Forwarding is blocked when the level is Read-only | `gate` gives `TopologyLocked` and `prepare` sends NOTHING (`calls` empty) |
| An existing shovel is never overwritten | `ShovelExists(status)` and no `Start` |
| A shovel that appeared after the dry run is not overwritten (new) | the second dry run inside `start` blocks; `calls` has no `Start` |
| A TLS profile never falls back to plain for the shovel | `ShovelAddress.forProfile(tls = true).scheme == "amqps"`; a scripted `Refused("...")` from the gateway gives `StartResult.Refused`, and no second `Start` with a plain address follows |
| A plain profile says it is not encrypted | `NotEncrypted` warning and `ForwardingConfirmation.notEncrypted` |
| A shovel that cannot reach the source is reported as failed | terminated status gives `Failed(reason)` and one `Remove` |
| Forwarding goes from started to running to finished (new) | `ForwardingProgress` Started, Running(40), Finished |
| The confirmation says that administrators of the 4.x broker can read the source password | the line exists in the confirmation |
| A user who can create shovels but not read their status is told | `CannotReadStatus` block |
| By default the profile user is used for the shovel source | `ShovelAddress.forProfile(credentials = null).username` is the profile user |
| The typed source password never appears in the request text (new) | `toString` of request, credentials and address; `copy` of the request still prints `***`; address `amqps://mover@legacy.example:5671/%2F` |

- Unit (core `jvmTest`, new files `ForwardingPlannerTest`, `ForwardingProgressTest`, `ForwardingServiceTest`, `ShovelRedactionTest`, `ShovelGatewayShapeTest`): the planner table (every block alone and in combination, fixed order, canStart), the tag rules (administrator alone allows both), tracker transitions including the one-absent-poll grace, the structural test of `ShovelGateway` by reflection-free means (a fake implementing the interface with exactly four overrides compiles; a test that lists the method names through `ShovelGateway::class.members` on the JVM test), redaction (string template, `toString`, `copy` of `ForwardingRequest`/`ForwardingSource`, no `password` property on `ShovelAddress`, `ShovelSourceCredentials` has no `copy`/`equals`).
- A guard like `MigrateFromThreeTextsGuardTest`: the confirmation text and `ForwardingTexts` never contain `://` followed by a `:` password pattern; scan for `amqp(s)?://[^@\s]*:[^@\s]*@` in every produced line.

## Design notes
- `core` only; no RabbitMQ, no Paho, no `java.*`. `SecretValue` is reused from `core.migrate3x` (import; moving it is not worth a task). `QueueCounts`, `ShovelStatus` and the outcomes are facts, the decisions are in the planner, so M8-08 maps JSON to facts only.
- Dependencies: `TopologyControlLevel` and `ConnectionProfile` from `core.connections`. The effective permission is passed in (`ForwardingContext`), not computed here; M8-09 builds it from the held connection (level and the session's `topologyUnlocked`).
- Fakes: `FakeShovelGateway` only. Everything real is M8-08. The structural tests keep `RealBrokerM7GateTest` and `RealBrokerM8GateTest` untouched (core only; no `data-rabbit` change in this task).
- Strategy for the rules: each safeguard is a small private function in `ForwardingPlanner`; no framework.

## UX
`M8-09` (`docs/ux/Queues.dc.html` or the forwarding dialog drawn in `Migrate3x.dc.html`, to be confirmed in the M8-09 brief). Nothing is drawn in M8-07.

## MCP
None here. Decision (spec MCP evaluation, unchanged): Shovel create and remove are NOT exposed (moves data, typed confirmation, credentials); status read-only comes in `M8-10`. `ShovelGateway` is not given to `:mcp-server`.

## Decisions made in this brief (technical, architect 2026-11-11; recorded in the spec)
1. Absent grace: one absent poll after `Started` is not `Finished` (not measured; M8-08's live test confirms or sets it to zero).
2. A user who can create but not read shovels (`CannotReadStatus`) is BLOCKED, not only told: without status the app could not see a wrong password (the PUT answers 201) nor remove the stored credentials. The spec said "the app says so"; it now also blocks.
3. The cleanup after a failed start may remove the shovel at `DECLARE_AND_EDIT`, while the user's Remove needs `FULL_CONTROL` (P4). Without this the app would leave a clear-text source password on the broker whenever the level is below Full control.
4. The dry run is repeated inside `start` (one more GET round) so a shovel that appeared meanwhile is never overwritten; the PUT replaces an existing name (S-F).
5. The shovel address is shown with the vhost as it is sent (`%2F` for `/`).

## Open points
- Owner, FYI (no wording or screen change, nothing blocks M8-07): decisions 2 and 3 above refine P4 and spec safeguard 6. Recommendation: accept both; if the owner prefers decision 2 as a warning only, the planner moves `CannotReadStatus` to the warnings and `start` still works.
- Not measured, carried to M8-08: the Shovel parameter name length limit and queue names that need percent-encoding in the name; the `amqps` TLS options; the grace of decision 1.

## Test plan limits (recommended)
- Step 2 and 3: `./gradlew :core:jvmTest --tests '*Forwarding*' --tests '*Shovel*'`, `:core-testing:compileKotlin`, and `:acceptance-tests:test --tests '*RunForwardingCoreAcceptanceTest*'`. No `brokerTest`, no ui or desktopApp tests (no change there).
- Step 4: `:core:jvmTest` (whole module, once), `:acceptance-tests:test` (whole), `verifyTestDiscovery`; mutations: remove the typed-name check, skip the second dry run, start while a block exists, add a `copy`/password field to `ShovelAddress`, treat the first absent poll as `Finished`, allow the user's remove at DECLARE_AND_EDIT.
- Full suite once at the end of the task, not per step.

## Definition of done
Tests first and passing (the scenarios tagged `@m8-07`), the safety wording of the spec is quoted in the KDoc of `ShovelGateway`, spec updated (done in step 1), no change outside `core`, `core-testing` and `acceptance-tests`.

## Status
- Step 1 (architect, 2026-11-11, round 0, base `6d5e1da`): brief made exact. Size M kept, not split. 18 scenarios tagged `@m8-07`, 3 `@m8-08`, 5 `@m8-09`, 5 new `@m8-07` scenarios added to `17`. Owner questions: none blocking (one FYI, see Open points).
- Step 2 (test developer, 2026-11-11, round 0): tests first, red, compile stubs marked `M8-07 STUB` (model and gateway types are real; `ShovelAddress.forProfile` and `percentEncode`, `ForwardingPlanner`, `ForwardingConfirmations`, `ForwardingProgress` and `ForwardingService` are stubs; `ForwardingTexts` is empty).
  - Files. core `commonMain/forwarding`: `ForwardingModel.kt`, `ShovelGateway.kt` (no KDoc yet, the developer quotes the spec rule), `ForwardingPlanner.kt`, `ForwardingConfirmation.kt`, `ForwardingTexts.kt`, `ForwardingProgress.kt`, `ForwardingService.kt`. core-testing: `forwarding/fakes/FakeShovelGateway.kt` (scripted; `calls`, `shovels`, `replaced`; replaces an existing shovel on start like the broker; a late shovel appears at the second dry run). core `jvmTest/forwarding`: `ForwardingTestSupport`, `ForwardingPlannerTest`, `ForwardingProgressTest`, `ForwardingServiceTest`, `ForwardingConfirmationTest`, `ForwardingRequestTest`, `ShovelRedactionTest`, `ShovelGatewayShapeTest`. acceptance: `RunForwardingCoreAcceptanceTest`, `ForwardingCoreSteps`, `ForwardingWorld`.
  - Red: core 66 tests, 45 red by assertion (21 green on arrival: the model facts that the brief gives as code, such as credentials redaction, no copy, no password field, ack mode, shovel name, the four-operation shape, the source guard, and some negative planner and progress cases that the empty stubs satisfy). acceptance: 23 of 23 `@m8-07` scenarios red by assertion. `:core-testing:compileKotlin` green.
  - Brief problems for the architect: (1) `StartResult.NotConfirmed` is written `data object NotConfirmed` without fields: built as `data object`. (2) The Background says the user has "the policymaker tag" only, but decision 2 blocks a user without monitoring or administrator; the Background step gives policymaker AND monitoring (the last `CannotReadStatus` scenario has its own Given). The feature text could say "policymaker and monitoring tags". (3) The scenarios "Not encrypted ... and the progress view", "I am told which plugins to enable" and "told that topology changes are locked" name texts of M8-09; here they are checked as blocks, warnings and the confirmation flag (no text exists in M8-07 except the confirmation). (4) The confirmation sentences are not quoted verbatim anywhere, so `ForwardingConfirmationTest` looks for key words (moved, destination confirms, undo, not deleted, keeps credentials, clear text, administrator, finishes, removed, stay for NEVER, Not encrypted). The developer may choose the wording within the spec; tell me if you want exact strings pinned. (5) `poll` with a previous state that is already Finished or Failed: the brief says final but not whether a request is sent; not pinned by a test (the progress rule is). (6) A cleanup at a locked connection is not specified; not tested.
  - Mutations planned for step 4 (one catching class each): (1) drop the typed-name check -> `ForwardingServiceTest`; (2) skip the second dry run in `start` -> `ForwardingServiceTest` (appeared-after-dry-run); (3) start while a block exists -> `ForwardingServiceTest` (start while any block exists); (4) a `copy` or password field on `ShovelAddress`/credentials -> `ShovelRedactionTest`; (5) first absent poll treated as Finished -> `ForwardingProgressTest`; (6) the user's remove allowed at Declare and edit -> `ForwardingServiceTest` (the five listed are covered; the sixth would replace one).
  - Not run: no `brokerTest`, ui, desktopApp, data-rabbit, full suite.
- Step 2b (architect, 2026-11-11): rulings. (1) `NotConfirmed` is a `data object` (brief fixed). (2) Background of `17` now says "the policymaker and monitoring tags". (3) The screen texts move to M8-09: the three `@m8-07` scenarios now say "blocked because the shovel plugins are off", "disabled because topology changes are locked on new-eu" plus "no request is sent to either broker", and "the confirmation says Not encrypted"; one new `@m8-09` scenario carries the three texts ("The screen says why forwarding is blocked and that a plain connection is not encrypted"). `@m8-09` now has 6 scenarios, `@m8-07` still 23. (4) Confirmation wording: KEEP the key-word test (case-insensitive, the list the test developer gave). The content is already decided by the spec (safeguards 1, 7, P14); the exact strings are pinned in M8-09 when the owner sees the dialog next to the mockup. No owner question now. (5) Rulings and tests to add: (a) `poll` with a previous `Finished` or `Failed` returns it and sends NO request (test: `calls` stays empty). (b) The cleanup of a failed shovel needs the same gate as creating (`DECLARE_AND_EDIT` or higher AND `topologyUnlocked`); on a locked connection no `Remove` is sent and the state is `Failed(reason, cleanedUp = false)` (test; the screen then says the shovel and its stored password remain and a Full control user must remove it, M8-09). A cleanup whose `remove` is not `Removed` also gives `cleanedUp = false` (test). (c) A terminated shovel without a reason gives `Failed(ForwardingTexts.NO_REASON, ...)` where `NO_REASON = "The shovel stopped without giving a reason"` (test). (6) The KDoc on `ShovelGateway` that quotes the spec rule is a developer task in step 3 (it is in Definition of done); the step 4 review reads it.
- Step 2b (test developer, 2026-11-11): rulings applied. Steps rebound to the new feature wording (Background "policymaker and monitoring tags", "forwarding is blocked because the shovel plugins are off", "forwarding is disabled because topology changes are locked on ...", "no request is sent to either broker", "the confirmation says "Not encrypted""); the new `@m8-09` scenario is not bound. Ruling 5 tests added: `ForwardingServiceTest` (poll after Finished or Failed sends nothing, cleanup uses the creating gate, a remove other than Removed gives cleanedUp false, no reason gives `ForwardingTexts.NO_REASON`) and `ForwardingProgressTest` (no reason); `NO_REASON` added as a stub constant. Counts: core 71 tests, 49 red by assertion (the "poll after final" test is green on arrival: the stub returns the previous state); acceptance 23 of 23 red by assertion, no undefined or ambiguous step.
