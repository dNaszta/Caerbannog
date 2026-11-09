# Compatibility analyzer (pure core rules, severity, report)

Milestone: M8    Depends on: M8-00 (done), M8-01 (merged)    Size: L

## Goal
`CompatibilityAnalyzer.analyze(document, live)` turns a parsed 3.x `DefinitionsDocument` (plus optional facts read from a live 3.x broker) into a `CompatibilityReport`: every incompatibility with 4.x as a `Finding` (severity, rule id, item, reason, proposed 4.x equivalent, whether an explicit tick is needed, evidence), the count of items that need no change, a stable order with the message-loss findings first (P16), a JSON export (`caerbannog.compat-report/1`) and a plain-text rendering. No secret can reach the report.

## Why
Spec "Compatibility report" and decisions P2, P11, P12, P15, P16, P17 in "Migrating from RabbitMQ 3.x (M8)". The owner's biggest need: know what in the 3.x topology does not work on 4.x. The report is the input of the review and generator (`M8-05`, `M8-12`), the UI (`M8-06`) and the MCP report tool (`M8-10`).

## Scope
In (all in module `core`, `commonMain`, package `dev.caerbannog.core.migrate3x`, no JVM-only types, no I/O, no clock, no logging, no new dependency; JSON is built with the kotlinx tree API already used by `M8-01`):
1. The report model, the rules, the analyzer, the JSON export, the plain-text rendering (below).
2. Test builders in `core-testing` (a small `DefinitionsDocuments` builder for hand-made documents, so scenarios do not need JSON text) and the fake live facts.
3. Tags and suite: scenarios tagged `@m8-03` in `16` and `18` (done in step 1), a new Cucumber suite `RunMigrate3xAnalyzerAcceptanceTest` (selects `16_migrate_from_3x.feature` and `18_migrate_access_from_3x.feature`, glue `dev.caerbannog.acceptance.migrate3x`, filter `@m8-03 and @fake-broker`), row `@m8-03` in `tests/bdd/README.md` (done).
Out: the live fetch and `storage_version` scan with retry (`M8-02`: it fills `LiveSourceFacts`), the review, tick enforcement and migration output (`M8-05`, `M8-12`), UI (`M8-06`), MCP tool (`M8-10`), parsing (`M8-01`). `DefinitionsReader` is NOT touched, so the optional cleanup "nullable vhost instead of the `""` placeholder" (M8-01 review, minor 5) is NOT done here (not needed; it stays an idea).

## Model (exact; names are the contract with `M8-05`, `M8-06`, `M8-10`)
Real signatures of the merged M8-01 code the analyzer reads: `DefinitionsDocument(sourceVersion: String?, virtualHosts, queues, exchanges, bindings, policies, operatorPolicies, users, permissions, topicPermissions, otherParameters: List<UnparsedParameter>, origin: DefinitionsOrigin = FullExport)`; `QueueDefinition(vhost, name, durable, autoDelete, type: QueueKind, arguments: Map<String, ArgumentValue>)` with `QueueKind` = `Classic | Quorum | Stream | Other(name)`; `PolicyDefinition` and `OperatorPolicyDefinition(vhost, name, pattern, applyTo, priority, definition: Map<String, ArgumentValue>)`; `UserDefinition(name, passwordHash: SecretValue?, hashingAlgorithm: String?, tags, limits)`; `PermissionDefinition(vhost, user, configure, read, write)`; `TopicPermissionDefinition(vhost, user, exchange, write, read)`; `UnparsedParameter(vhost: String?, component, name)`; `ArgumentValue` = `Text | Whole | Decimal | Flag | Items | Table | Nothing`. The analyzer never calls `SecretValue.expose()` or `matches()`; it only asks `passwordHash != null`.

```kotlin
enum class Severity { Blocker, Warning, Info }                       // JSON: "blocker" | "warning" | "info"
enum class FindingClass { MessageLoss, Incompatibility, BehaviourChange, NotCovered, ClientCheck, Access }
enum class ItemKind { Queue, Exchange, Binding, Policy, OperatorPolicy, VirtualHost, User, Permission, TopicPermission, Parameter, Global }
enum class AppliesWhen { Always, ConvertedToQuorum, KeptClassic }   // a finding that only matters for one outcome of a queue
enum class EvidenceSource { Definitions, LiveQueueStats, LiveDeprecatedFeatures, KnownBrokerBehaviour }
enum class Acknowledgement { OrderingByPriorityMayChange, PubliclyKnownPassword }  // texts below
enum class DefaultDecision { Accept, Skip }                          // what the review starts with

sealed interface Proposal {                                           // M8-05 turns these into actions without parsing text
    data class ConvertQueueType(val target: QueueKind, val acknowledgement: Acknowledgement?) : Proposal  // quorum: OrderingByPriorityMayChange
    data object KeepClassic : Proposal
    data class RemoveArgument(val argument: String) : Proposal
    data class SetArgument(val argument: String, val value: ArgumentValue) : Proposal
    data object ClearAutoDelete : Proposal
    data object MakeDurable : Proposal
    data class DropPolicyKeys(val keys: List<String>) : Proposal
    data class DeclareVirtualHost(val name: String) : Proposal
    data class DeclareUser(val name: String, val tags: List<String>, val hashingAlgorithm: String?, val hasPasswordHash: Boolean, val acknowledgement: Acknowledgement?) : Proposal
    data class SetPermissions(val vhost: String, val user: String) : Proposal          // patterns are read from the document by M8-12
    data class SetTopicPermissions(val vhost: String, val user: String, val exchange: String) : Proposal
    data class Manual(val title: String, val text: String, val recommended: Boolean) : Proposal  // no action; steps for the user
    data object None : Proposal
}

data class Finding(
    val id: String,               // unique and stable inside a report: "<rule>|<vhost or ->|<kind>|<name or ->|<about joined by ,>"; M8-05 keys decisions by it
    val rule: String,             // stable rule id from the table
    val severity: Severity,
    val findingClass: FindingClass,
    val kind: ItemKind,
    val vhost: String?,           // null for global and for broker-level items (vhost, user)
    val name: String?,            // queue, policy, user ... name; never a secret; null for Global
    val about: List<String>,      // the argument names or policy keys the finding is about, sorted; empty when not applicable
    val appliesWhen: AppliesWhen,
    val message: String,          // what is wrong (fixed template, only item names and argument names are inserted)
    val why: String,              // the reason in plain words
    val proposal: Proposal,       // the default proposal
    val alternatives: List<Proposal>,
    val defaultDecision: DefaultDecision,
    val verified: Boolean,        // true only when the rule has spike evidence (see RuleCatalog)
    val evidence: EvidenceSource,
)

data class ReportSummary(val topologyItems: Int, val compatibleItems: Int, val needDecision: Int, val blockers: Int, val warnings: Int, val infos: Int, val messageLoss: Int)
data class ReportSource(val name: String?, val sourceVersion: String?, val live: Boolean, val singleVirtualHost: String?)
data class CompatibilityReport(val source: ReportSource, val summary: ReportSummary, val findings: List<Finding>)

/** Optional facts only a live 3.x connection can give. M8-02 fills it; the file-only case passes null. */
data class QueueRef(val vhost: String, val name: String)
enum class ClassicIndexVersion { V1, V2, Unknown }                    // Unknown: the value never appeared after the scan's retries
data class LiveSourceFacts(
    val connectionName: String,
    val brokerVersion: String?,                                       // rabbitmq_version of GET /api/overview
    val classicIndexVersions: Map<QueueRef, ClassicIndexVersion> = emptyMap(),   // a classic queue missing from the map counts as Unknown
    val deprecatedFeaturesInUse: Set<String> = emptySet(),            // names from GET /api/deprecated-features/used
)

object CompatibilityAnalyzer { fun analyze(document: DefinitionsDocument, live: LiveSourceFacts? = null): CompatibilityReport }
object CompatibilityReportJson { const val SCHEMA = "caerbannog.compat-report/1"; fun encode(report: CompatibilityReport): String }
object CompatibilityReportText { fun render(report: CompatibilityReport): String }
```
The rule catalog: `data class RuleInfo(val id: String, val verified: Boolean, val evidence: String)` and `object RuleCatalog { val all: List<RuleInfo> }`. `evidence` names the spike section and fixture (for example `"M8-00 S-C queue-declaration-matrix-4.x.json"`) or `"none"` for an unverified rule. `Finding.verified` is read from the catalog, never typed at the emit site. The analyzer is built from small rule classes (`interface Rule { val info: RuleInfo; fun check(context: AnalysisContext): List<Finding> }`) grouped in files `QueueRules.kt`, `PolicyRules.kt`, `ClassicIndexRule.kt`, `AccessRules.kt`, `GlobalRules.kt`, `ParameterRules.kt`; `AnalysisContext` holds the document, the live facts, the per-queue winning policy (see Mirroring) and helpers. `CompatibilityAnalyzer` is a thin loop over `DefaultRules.all`.

Fixed texts: tick `OrderingByPriorityMayChange` = "Ordering by priority may change"; `PubliclyKnownPassword` = "This user has a publicly known password". Texts live in one `ReportTexts.kt` as constants/templates so tests can compare them.

## Behaviour rules (exact)
1. **Order (deterministic, no clock, no random).** Sort key: findings with `findingClass == MessageLoss` first (P16, spec: "always the first item"); then `vhost` (null first), `kind` (enum order above), `name` (null first), `rule`, `about` joined. Same input in any list order gives an identical report (test). `id` is unique inside a report (test).
2. **Summary.** `topologyItems` = queues + exchanges + bindings + policies + operator policies. An item "needs a decision" when at least one finding of severity Blocker or Warning with `appliesWhen == Always` names it (kind, vhost and name equal; no rule names a binding in this task, so bindings are only counted as compatible). `compatibleItems = topologyItems - needDecision`. Info findings, conditional findings (`ConvertedToQuorum`, `KeptClassic`), access items, parameters and global findings do not change these counts. `messageLoss` counts findings of class MessageLoss. A document with the quorum queue `events`, the exchange `events.x` and one binding gives `topologyItems = 3, compatibleItems = 3` and no Blocker or Warning (scenario).
3. **Queue type.** `QueueKind.Classic` covers a queue with no `type` and no `x-queue-type`. Rules below speak of classic, quorum, stream; `Other(name)` gets `queue-type-unknown` only.
4. **Mirroring (which policy mirrors a queue).** For a queue, the candidate policies are those in the same vhost whose `applyTo` is `all` or `queues` and whose `pattern` matches the queue name with `Regex(pattern).containsMatchIn(name)` (RabbitMQ patterns are unanchored). The winner is the highest `priority`; a tie is broken by the policy name ascending (RabbitMQ's tie behaviour is unverified; the choice only makes the report deterministic). The queue is "mirrored" when the winner has an `ha-mode` key in its `definition`. A pattern that does not compile is never a match and produces the Info finding `policy-pattern-unreadable` (verified = false). Operator policies never mirror. Test against the full fixture: `classic-mirrored` is mirrored by `ha-all`, `classic-plain` by `ha-exactly`, no other queue is mirrored.
5. **Version of the source.** `classic-index-v1` is skipped when `document.sourceVersion` parses (leading digits before the first dot) to a major number of 4 or more (a 4.x export has no old index). An absent or unparseable version does not skip it. No other rule looks at the version.
6. **Secrets.** Findings, proposals, texts, JSON and `toString` carry user name, tags, hashing algorithm and `hasPasswordHash` only. No `SecretValue`, no parameter value, no URI. A test with the shovel fixture (sentinels `SENTINEL-HASH-svc`, `SENTINEL-PASSWORD`) asserts neither string occurs in `report.toString()`, the JSON and the text.
7. **Verified flag.** `verified = false` is shown as "unverified" in the text rendering and as `"verified": false` in the JSON. A test fails if an emitted rule id is missing from `RuleCatalog`, if a verified rule has `evidence == "none"`, or if an unverified rule names a fixture.

## Rule table
Severity, class, scope (`appliesWhen`), evidence. Item column says what a finding is about. "Q" = queue, "P" = policy, "OP" = operator policy. `about` is the argument or key list. Messages are templates in `ReportTexts.kt`; the quoted text is the required content, wording around it is free but fixed.

| Rule id | Item | Condition | Sev, class, scope | Default proposal / alternatives | Verified, evidence | Message must say |
| --- | --- | --- | --- | --- | --- | --- |
| `ha-policy-key` | P, OP | `definition` has any of `ha-mode`, `ha-params`, `ha-sync-mode`, `ha-sync-batch-size`, `ha-promote-on-shutdown`, `ha-promote-on-failure`; `about` = the keys present | Blocker, Incompatibility, Always | `DropPolicyKeys(keys)` / none | yes, S-E `ha-*` rejected on 4.4 (policy and operator) | RabbitMQ 4 rejects these keys when the policy is created or imported (not "ignored"); to keep replication move the matching queues to quorum queues |
| `queue-master-locator-policy-key` | P, OP | key `queue-master-locator` | Blocker, Incompatibility, Always | `DropPolicyKeys` / none | yes, S-E | rejected as a deprecated argument on 4.x |
| `operator-policy-key-rejected` | OP | key in {`queue-mode`, `dead-letter-exchange`, `dead-letter-routing-key`, `alternate-exchange`, `queue-leader-locator`} | Blocker, Incompatibility, Always | `DropPolicyKeys` / `Manual` (set it as a normal policy) | yes, S-E | an operator policy on 4.x accepts only the limits; this key is rejected there |
| `policy-queue-mode` | P | key `queue-mode` | Info, BehaviourChange, Always | `None` / none | yes, S-E | accepted on 4.x; lazy mode has no effect on a quorum queue |
| `policy-max-in-memory` | P | keys `max-in-memory-length` or `max-in-memory-bytes` | Warning, BehaviourChange, Always | `None` | NO (effect unverified) | accepted by 4.x; the effect is not verified |
| `not-covered-policy-key` | P | any key starting with `federation-` | Warning, NotCovered, Always | `Manual` (set up federation by hand on 4.x) | yes, S-B (federation parameter rejected when the plugin is off) | federation is not migrated by this app |
| `policy-pattern-unreadable` | P, OP | `pattern` does not compile as a regular expression | Info, BehaviourChange, Always | `None` | NO | the app could not read the pattern, so it did not match queues against it |
| `queue-not-durable` | Q (any type) | `durable == false` | Blocker, Incompatibility, Always | `Manual` (default: "declare it durable or let the application declare it") / `MakeDurable` | yes, S-B and S-D (skipped silently with HTTP 204) | RabbitMQ 4 does not allow it; an import of the raw file would skip it without an error (both phrases are tested) |
| `queue-master-locator-argument` | Q (any type) | argument `x-queue-master-locator` present | Blocker, Incompatibility, Always | `RemoveArgument("x-queue-master-locator")` / none | yes, S-B, S-C, S-D | "RabbitMQ 4 does not allow it" and "rejected on classic and quorum queues" (both phrases), and that an import of the raw file would skip the queue without an error |
| `quorum-rejected-setting` | Q classic | `x-max-priority`, `x-queue-mode` (any value), `x-queue-version` (any value) present, or `autoDelete == true`; one finding per setting, `about` = [name] (`auto_delete` for the property) | Blocker, Incompatibility, ConvertedToQuorum | `RemoveArgument(name)` (`ClearAutoDelete` for the property) / none | yes, S-C | a quorum queue rejects this setting, so converting needs it removed |
| `classic-rejected-argument` | Q classic | `x-queue-version` equal to `Whole(1)`, or `x-max-in-memory-length` / `x-max-in-memory-bytes` present; one finding per argument | Blocker, Incompatibility, KeptClassic | `SetArgument("x-queue-version", Whole(2))` for the version, `RemoveArgument` for the others / none | yes, S-D | a classic queue on 4.x rejects it |
| `quorum-max-in-memory` | Q classic | `x-max-in-memory-length` / `-bytes` present | Warning, BehaviourChange, ConvertedToQuorum | `None` | NO (accepted on quorum, effect unverified) | accepted by a quorum queue, the effect is not verified |
| `classic-not-replicated` | Q classic, durable only (a non-durable queue gets `queue-not-durable` instead) | every durable classic queue; if mirrored (rule 4) the message names the mirroring policy | Warning, BehaviourChange, Always | Without `x-max-priority`: default `ConvertQueueType(Quorum, OrderingByPriorityMayChange)`, alternative `KeepClassic`. With `x-max-priority` (any value): default `KeepClassic`, alternative `ConvertQueueType(Quorum, OrderingByPriorityMayChange)` (P15). Default decision `Accept` | yes, owner-verified fact (mirroring removed in 4.0) and S-D | classic queues are no longer replicated on 4.x; a mirrored queue loses its mirrors; quorum queues are the replicated type. For a queue with `x-max-priority`: it is proposed to stay classic (declarable on 4.x, not replicated) |
| `classic-to-quorum-ordering` | Q classic (durable or not) | every classic queue, durable or not | Warning, BehaviourChange, ConvertedToQuorum | `None` (the tick is on the conversion proposal) | yes, S-G | a quorum queue on 4.x always orders messages by priority; messages that carry a priority may be delivered in a different order than in a classic queue (without `x-max-priority` a classic queue is FIFO). For a queue with `x-max-priority` the text adds that the queue is also unreplicated if kept classic; only the measured ordering fact is claimed |
| `queue-type-unknown` | Q `Other(name)` | type is not classic, quorum, stream | Warning, Incompatibility, Always | `Manual` | NO | the app does not know this queue type |
| `classic-index-v1` | Q classic, durable | see "Message loss" below | Blocker or Warning, MessageLoss, Always | see below | yes, S-N | see below |
| `exchange-type-not-builtin` | exchange | `type` not in {`direct`, `fanout`, `topic`, `headers`} | Warning, Incompatibility, Always | `Manual` (enable the plugin on the 4.x broker) | NO | this exchange type may need a plugin that is not enabled on the 4.x broker; the app does not check |
| `not-covered-parameter` | parameter | every `UnparsedParameter` with a vhost (shovel, federation-upstream, any plugin) | Warning, NotCovered, Always | `Manual` | yes, S-B (a parameter of a plugin that is off is rejected on import) | listed by component and name only; not migrated; no value is read |
| `not-covered-global-parameter` | Global | every `UnparsedParameter` with `vhost == null` | Info, NotCovered, Always | `None` | yes, S-A | not migrated |
| `mirroring-in-use` | Global | live facts: `classic_queue_mirroring` in `deprecatedFeaturesInUse` | Warning, BehaviourChange, Always | `None` | yes, S-G fixture `deprecated-features-used-3.13.json`; evidence `LiveDeprecatedFeatures` | the broker reports classic queue mirroring as in use |
| `deprecated-feature-in-use` | Global | live facts: any other name in `deprecatedFeaturesInUse`; one finding per name, `about` = [name] | Warning, ClientCheck, Always | `Manual` | verified only for the names in the 4.4 denied list {`queue_master_locator`, `global_qos`, `transient_nonexcl_queues`, `amqp_address_v1`, `amqp_filter_set_bug`}, else NO; evidence `LiveDeprecatedFeatures` | the broker reports this deprecated feature as in use |
| `clients-global-qos` | Global | always, once | Info, ClientCheck, Always | `None` | yes, S-I (`global_qos` denied by default on 4.4), evidence `KnownBrokerBehaviour` | check your clients: `basic.qos(global=true)`; not detectable from definitions |
| `clients-transient-queues` | Global | always, once | Info, ClientCheck, Always | `None` | yes, S-I | check your clients: transient, non-exclusive queues are refused by default |
| `clients-amqp-address-v1` | Global | always, once | Info, ClientCheck, Always | `None` | yes, S-I | check your clients: AMQP 1.0 address format v1 |
| `classic-to-quorum-unmeasured` | Global | at least one classic queue | Info, BehaviourChange, Always | `None` | NO (`none`) | redelivery, the delivery limit and exclusive consumers may behave differently on a quorum queue; the app has not measured them ("unverified") |
| `access-declare-vhost` | VirtualHost | each `virtualHosts` entry | Info, Access, Always | `DeclareVirtualHost(name)` | yes, S-A | belongs to the access migration, not to a topology migration |
| `access-declare-user` | User | each user with a hash and an allowed algorithm, or without a hash; not `guest`; | Info, Access, Always | `DeclareUser(name, tags, algorithm, hasPasswordHash, null)` | yes, S-M | the hash is carried over, never shown |
| `access-set-permissions` | Permission | each permission | Info, Access, Always | `SetPermissions(vhost, user)` | yes, S-A | belongs to the access migration |
| `access-set-topic-permissions` | TopicPermission | each topic permission | Info, Access, Always | `SetTopicPermissions(vhost, user, exchange)` | yes, S-A | belongs to the access migration |
| `user-guest` | User | name is `guest` (replaces `access-declare-user` for it) | Warning, Access, Always | `DeclareUser(..., acknowledgement = PubliclyKnownPassword)`; `defaultDecision = Skip` (P11) | yes, spec P11 (the 4.x loopback rule is NOT measured, S-P, and is not claimed) | has a publicly known password; skipped by default; accepting it needs an explicit confirmation |
| `user-administrator-tag` | User | tags contain `administrator` | Warning, Access, Always | `None` | yes, spec | the migration gives this user the administrator tag on 4.x |
| `user-hash-without-algorithm` | User | `passwordHash != null` and `hashingAlgorithm == null` | Blocker, Access, Always | `Manual` (set a new password on the broker, outside the app); no `DeclareUser` | yes, S-M (login fails without it) | without the hashing algorithm the login would fail |
| `user-hash-algorithm-unsupported` | User | `hashingAlgorithm` not in {`rabbit_password_hashing_sha256`, `rabbit_password_hashing_sha512`, `rabbit_password_hashing_md5`} (bcrypt, unknown names, anything else); `passwordHash` present | Blocker, Access, Always | `Manual` (set a new password on the broker outside the app); no `DeclareUser`; the app offers no password field (P12) | yes, S-M | RabbitMQ 4 rejects this algorithm; `about` = [algorithm name] (an algorithm name is not a secret) |
| `user-no-password-hash` | User | `passwordHash == null` | Info, Access, Always | `DeclareUser(.., hasPasswordHash = false)` | yes, S-A | no password hash in the export (for example external authentication); the app does not invent a password |
| `access-not-in-file` | Global | `origin` is `SingleVirtualHost(name)` | Info, Access, Always | `Manual` | yes, P17 | the file holds no virtual hosts, users, permissions or topic permissions, so the access migration is empty, and the virtual host `name` must already exist on the 4.x broker |

Notes: a user that has both a guest name and a unsupported algorithm gets `user-guest` and the Blocker (both are true). `access-declare-user` is not emitted for a user that has a Blocker rule (`user-hash-*`). The three `clients-*` and `classic-to-quorum-unmeasured` findings are emitted once per report (the last only when a classic queue exists). Any key, argument or setting not named in this table produces NO finding (the app does not claim what it did not measure).

### Message loss (`classic-index-v1`, S-N, P16)
Applies to durable classic queues of a source whose version is not 4+ (rule 5). `about` is empty for the live case and `["x-queue-version"]` when the argument decided.
- **Live facts present:** index version V1 -> Blocker; `Unknown` (never appeared, or the queue is missing from the map) -> Blocker whose message says "the index version could not be read; treated as version 1"; V2 -> no finding (even when the file says otherwise); `evidence = LiveQueueStats`.
- **File only (live null):** `x-queue-version == Whole(1)` -> Blocker (the file states it); no `x-queue-version` argument -> Warning with the text "the file does not carry the index version"; `x-queue-version == Whole(2)` -> no finding; `evidence = Definitions`.
- Class `MessageLoss`, `appliesWhen = Always`, `defaultDecision = Accept`.
- Message (required content): "uses the old classic queue index (version 1). RabbitMQ 4.3 and later cannot read it: an in-place upgrade would silently empty this queue (messages lost, no error in the management API)." `why` also says the app cannot detect the loss after an upgrade and that only the broker log shows it.
- Proposals: default `Manual(recommended = true)` "Do not upgrade in place: migrate the topology to a new 4.x broker and move the messages with a Shovel (the Shovel path does not read the old index)". Alternatives (both `Manual`, `recommended = false`, labelled secondary and done by hand): "Set the policy `queue-version: 2`, restart the 3.x node gracefully and check that the queue shows version 2 (the policy alone on a running node is not proof)"; "Upgrade through RabbitMQ 4.0, 4.1 or 4.2 first". No proposal anywhere offers an in-place upgrade as a safe or recommended step (a test scans all proposal texts for the word "in place" only inside warnings, and asserts exactly one `recommended = true` Manual per message-loss finding).

## JSON and text
JSON (`CompatibilityReportJson.encode`, tree API, fixed key order, pretty-printed): `{"schema":"caerbannog.compat-report/1","source":{name,sourceVersion,live,singleVirtualHost},"summary":{...},"findings":[{id,rule,severity,class,kind,vhost,name,about,appliesWhen,message,why,proposal,alternatives,defaultDecision,verified,evidence}]}` with `proposal` as `{"type":"convertQueueType","target":"quorum","acknowledgement":"Ordering by priority may change"}` and the equivalents (`dropPolicyKeys`, `removeArgument`, `setArgument` (value as text or number), `declareUser` with name, tags, algorithm, `hasPasswordHash`, `manual` with title, text, recommended, ...). Class and enum values are lower camel case. Enum names are not translated. No password hash, no URI, no parameter value can occur (the types hold none). Text (`CompatibilityReportText.render`): one block per finding in report order: `[BLOCKER|WARNING|INFO] <rule> <kind> <vhost>/<name>`, message, why, proposal, "unverified" when not verified, the tick text when a proposal needs one; first line is the summary ("37 of 52 items are compatible as they are, 15 need a decision").

## Acceptance
BDD (scenarios already tagged `@m8-03` in step 1; suite `RunMigrate3xAnalyzerAcceptanceTest`, filter `@m8-03 and @fake-broker`), all must pass:
- `16`: "Analyse a definitions file exported from RabbitMQ 3.x" (@smoke); "Every finding says what, how serious, why and what to do on 4.x"; "Mirroring settings of a policy are reported as rejected on 4.x"; "Every mirroring policy key is a blocker" (outline, 7 rows); "A live 3.x broker that uses mirroring is flagged by its deprecated feature list" (fake `LiveSourceFacts`); "A mirrored classic queue is proposed as a quorum queue"; "A queue entry without a type is a classic queue"; "Queue settings that a quorum queue rejects block that conversion" (outline, 4 rows; the `x-queue-master-locator` row moved to the next outline, step 1 edit); "Transient queues and master-locator queues are blockers because a 4.x import skips them silently" (outline); "Settings a quorum queue accepts convert as they are" (outline, 6 rows); "Converting a classic queue to quorum warns that ordering follows message priority"; "A queue with x-max-priority is proposed to stay classic"; "Classic to quorum differences the spike has not measured are shown as unverified" (the `@needs-spike` tag was removed in step 1: the finding `classic-to-quorum-unmeasured` makes it passable; M8-13 will turn it into a measured rule later); "A classic queue with the old index is a message-loss blocker"; "A classic queue with the new index is not flagged"; "The message-loss findings come first in the report"; "A definitions file cannot tell the index version, so every classic queue is warned about"; "The report says the loss cannot be detected after an upgrade"; "Client-visible changes are listed as checks, not as definition findings"; "Operator policies are read from the parameters"; "Items that need no change are not listed as problems"; "Users, virtual hosts and permissions become proposals for the access migration"; "Parameters the app does not migrate are listed as not covered"; "Export the report"; "Secrets in a 3.x export never reach the report".
- `18`: "guest is proposed for skipping"; "A user whose hash algorithm 4.x does not accept is a manual step" (outline, 2 rows); "The user is always declared with the hashing algorithm" (the blocker half); "A user without a password hash is reported"; "Administrator tags are called out". The Background of `18` ("the 4.x connection new-eu is the held connection", "a definitions file ... virtual host /orders, user svc with tags management and permissions") needs minimal glue.
- Step binding: "I analyse the file" is the M8-01 step extended: parse, then analyze when the result is `Parsed` (the M8-01 outline "A file that is not a definitions export is rejected" keeps passing: `Rejected` means no report). Steps that say "I analyse \"legacy-eu\"" run the analyzer on the document plus fake `LiveSourceFacts` (`connectionName = "legacy-eu"`); M8-02 rebinds them to the live fetch without editing the scenarios. The scenario "The live scan retries a storage version that is not there yet" is `M8-02`'s (the retry is a scan behaviour); the analyzer half (Unknown gives a blocker) is a unit test here.
- Scenario edits made in step 1: tags `@m8-03`; the quorum outline lost the `x-queue-master-locator` row and gained "the proposal for that blocker is to remove <setting> when the queue is converted"; `@needs-spike` removed from the unverified-differences scenario; `tests/bdd/README.md` tag row.

Unit tests (`core/src/jvmTest/kotlin/dev/caerbannog/core/migrate3x/`, JUnit 5; a test per rule row with a hand-built document, name `<RuleId>Test` or grouped by file):
- One test per rule id in the table: it fires on its condition with exactly the stated severity, class, `appliesWhen`, proposal, alternatives, `defaultDecision`, `verified`, `evidence`; and does NOT fire on the neighbouring non-condition (for example `x-queue-version: 2` on a classic queue gives `quorum-rejected-setting` but not `classic-rejected-argument`; `x-queue-version: 1` gives both and `classic-index-v1` in the file case).
- Full 3.13 fixture (`DefinitionsFixtures.read("definitions-3.13-full.json")`): golden list of `(rule, kind, vhost, name)` for the queues `classic-mirrored`, `classic-plain`, `classic-transient`, `classic-autodel`, `classic-lazy`, `classic-locator`, `classic-prio`, `quorum-q`, `v1-q`... the test developer derives the full golden list from the rule table and the architect reviews it in the step 2 handover; spot checks required: `ha-all` and `ha-exactly` give `ha-policy-key`, `ha-exactly` also `queue-master-locator-policy-key`; `op-limit` gives no finding; `classic-mirrored` is mirrored by `ha-all`; `classic-locator` gives `queue-master-locator-argument`; `quorum-q` no finding; `up1` gives `not-covered-parameter`; `cluster_name`, `internal_cluster_id` give `not-covered-global-parameter`; `guest` gives `user-guest`; `ex-delayed` (if its type is not built in) gives `exchange-type-not-builtin`.
- Mirroring helper: unanchored match, priority winner, tie by name, apply-to `exchanges` does not mirror, other vhost does not mirror, bad regex gives `policy-pattern-unreadable` and no match, operator policy never mirrors.
- Message loss: live V1, V2, Unknown, missing from map; file with `x-queue-version` 1, 2, absent; non-durable classic is skipped; quorum and stream are skipped; source version 4.x skips the rule, `null` and `"abc"` do not; exactly one recommended proposal; alternatives are secondary; no proposal text offers an in-place upgrade as safe.
- Summary counts and `compatibleItems` rule (rule 2) on the three-item document. No rule emits a binding finding in this task, so a binding is only counted (and always compatible).
- Determinism: shuffled lists give an equal report (`==`); `id` unique; two runs equal; message-loss findings first, then the sort key.
- Secrets: sentinel test of rule 6 on `toString`, JSON and text, for the shovel fixture and the full fixture; reflection guard: no report type has a property of type `SecretValue` (the only `ArgumentValue` is `SetArgument.value`, a queue argument value, not a secret).
- Catalog guard (rule 7): every `Finding.rule` ever emitted by the tests is in `RuleCatalog.all`; verified rules name evidence; unverified name `"none"`; `RuleCatalog` ids are unique and every id in the rule table of this brief exists.
- JSON: parses back with the tree API; schema string; every finding has severity, rule, kind, name, proposal; golden file for a small report (`core-testing` fixture `report-expected.json`); text rendering golden for one blocker, one warning, one info, one unverified, one tick.
- Per-vhost: a `SingleVirtualHost` document gives `access-not-in-file` and no user or vhost findings; the `queues` get the assigned vhost.
- Large input: the 60,000-queue document of M8-01's large test analyses within the default heap, no time assertion.
Mutation checks the test developer records: (a) drop the `MessageLoss`-first ordering: the order test and the scenario go red; (b) make `classic-index-v1` fire for V2: "new index is not flagged" red; (c) put the user hash text into a `Manual` text or a finding message: the sentinel test and "Secrets ... never reach the report" go red; (d) change `ha-policy-key` to Warning: the outline goes red; (e) count a conditional finding in `needDecision`: the summary test goes red; (f) emit a rule without a catalog entry: the catalog guard goes red.

## Design notes
Pure functions, no I/O, `core` only; immutable data and sealed `Proposal`; the rule engine is a list of small rule objects (strategy, not a visitor); `AnalysisContext` computes the mirroring winner once per queue. Do not word anything as fact that `M8-00` did not measure (the Tanzu start-up claim; the 4.x `guest` loopback rule; the effect of `x-max-in-memory-*`; exclusive queues, redelivery, delivery limit, global QoS). `core` stays free of `java.*`. `SecretValue` is not used by this package's report types. The analyzer must not call `expose()` or `matches()` (test: a fake `SecretValue` cannot be spied on, so a source grep guard in the test suite asserts the new files do not contain `expose(`).

## UX
None here (`M8-06`). The text and JSON renderings are the contract the UI shows.

## MCP
None here. `M8-10` exposes the report (read-only, no secrets) by calling `CompatibilityReportJson.encode`; MCP evaluation: the report is a pure function of the definitions, so one tool `caerbannog_analyze_3x_definitions` (M8-10) is the only exposure; no tool for this task.

## Open points
No blocking product decision. Defaults chosen by the architect that the owner may veto (each is one rule or one proposal line):
1. Every classic queue without `x-max-priority` is proposed as a quorum queue (not only mirrored ones), because 4.x classic queues are not replicated and the old brief and P15 say so; the tick covers the ordering change. Alternative: propose quorum only for mirrored queues and keep the rest classic by default.
2. `not-covered-parameter` and `not-covered-policy-key` are Warnings (a feature in use stops working), the global parameters (`cluster_name` and so on) are Info.
3. `exchange-type-not-builtin` is a Warning with `verified = false` (plugin exchange types such as `x-delayed-message` are not measured).
4. A definitions file that states `x-queue-version: 1` is a message-loss Blocker, not only a Warning (the file states the version; spec line updated).
Spec edits in step 1: the sentence "(and any classic queue kept as is)" is removed from "Review and the generated migration" (the tick belongs to the conversion to quorum, P15); the file-only message-loss rule now says explicit version 1 is a blocker and a never-appearing live version is a blocker reported as unknown.
UNVERIFIED and shown as such: exclusive queues, redelivery, delivery limit, global QoS, `x-max-in-memory-*` effect on quorum, the `guest` loopback rule, tie behaviour of equal policy priorities, plugin exchange types, regular expression dialect differences between Erlang and Kotlin (`containsMatchIn` is used; an unreadable pattern is reported).

## Spike findings (M8-00, 2026-11-09)
Rules established by the spike (full table in the spec "Facts" and `M8-00-spike-results.md` S-B to S-E, S-G, S-H, S-I): every `ha-*` policy key and `queue-master-locator` is a blocker (REJECTED on 4.x, not "no effect"); transient or non-durable queues and `x-queue-master-locator` queues are blockers because a 4.x import skips them silently with HTTP 204; for a quorum target `x-max-priority`, `x-queue-mode`, `x-queue-version`, auto-delete are blockers of that conversion and the rest convert as is; `x-max-in-memory-*` warning; EVERY classic to quorum conversion gets the ordering warning (P15); keeping a classic queue is valid on 4.4 except transient and master-locator; operator policies from `parameters`; users: algorithm outside sha256, sha512, md5 is a blocker (`M8-12`); global "check your clients" info items; the message-loss rule `classic-index-v1`; the live signal `deprecated-features/used`. The table above is this list made exact.

## Definition of done
Tests first and passing (the `@m8-03` scenarios and the unit tests), every shipped rule traced to a fixture or flagged unverified, no new dependency, no build change, the spec "UNVERIFIED" list unchanged except what the rules above list, mutation checks recorded, `verifyTestDiscovery` green, no change to `DefinitionsReader`.

## Status
- Step 1 (architect, confirm and sharpen the brief): DONE 2026-11-09. Round 0. Branch `m8-03-compatibility-analyzer` (worktree `.claude/worktrees/m8-03`, base 7d541d2).
- Changed in step 1: this brief rewritten against the merged M8-01 code (exact model, entry point, live-facts input with defaults for the file case, complete rule table, sort order, counting rule, mirroring helper, message-loss handling, JSON and text, acceptance and mutation list); `tests/bdd/features/16_migrate_from_3x.feature` (tag `@m8-03` on 25 scenarios, `@needs-spike` removed from the unverified-differences scenario, quorum outline row and step adjusted); `tests/bdd/features/18_migrate_access_from_3x.feature` (tag `@m8-03` on 5 scenarios); `tests/bdd/README.md` (tag row); `docs/spec.md` (two clarifications, see Open points).
- Contradictions found and resolved: (1) the old acceptance said "reported as having no effect" for mirroring keys, the scenario and the spike say rejected on 4.x; (2) the old acceptance still listed `@needs-spike` scenarios that the spike had already closed; (3) the spec asked for a tick on "any classic queue kept as is", P15 says conversion to quorum only: spec corrected; (4) the file-only message-loss rule did not say what an explicit `x-queue-version: 1` and a live version that never appears give: decided (blocker); (5) "Input is a DefinitionsDocument only" conflicted with the live facts (`storage_version`, deprecated features): input is the document plus optional `LiveSourceFacts`.
- Open product decisions: none blocking; four architect defaults for the owner to veto (Open points).

- Step 2 (test developer, tests first): DONE 2026-11-09. Round 0. Red for the right reason. Stubs: `core/src/commonMain/.../migrate3x/M803TestStubs.kt` (the brief's types and signatures; `analyze` returns an empty report, `encode` returns `{}`, `render` returns `""`, `RuleCatalog.all` is empty); the senior developer replaces the whole file and deletes it.
- Tests written: unit (`core/src/jvmTest/.../migrate3x/`): `PolicyRulesTest`, `QueueRulesTest`, `ClassicIndexRuleTest`, `AccessRulesTest`, `GlobalRulesTest`, `MirroringTest`, `ReportOrderAndSummaryTest`, `FullFixtureGoldenTest`, `ReportSecretsTest`, `RuleCatalogTest` (includes a kitchen-sink input that must emit all 35 rule ids), `ReportJsonTest`, `ReportTextTest`, `ReportLargeInputTest` (+ `AnalyzerTestSupport`, `ReportFixtures`); builders `DefinitionsDocuments` (`core-testing`), fixtures `core-testing/.../fixtures/m8-03/` (`report-expected.json`, `full-fixture-golden.txt`). Acceptance: `RunMigrate3xAnalyzerAcceptanceTest` (46 scenarios: 40 in `16`, 6 in `18` after outline expansion), glue `Migrate3xAnalyzerSteps`, `Migrate3xWorld` (shared scenario state + reset hook), `Migrate3xDefinitionsSteps` (state moved to the world, "I analyse the file" now parse then analyse).
- Red run: `:core:jvmTest --tests '*migrate3x*'` 299 tests, 187 red (all the new ones that need a finding, assertion failures "expected exactly one '<rule>' finding but got 0"; the 109 M8-01 tests green); `RunMigrate3xAnalyzerAcceptanceTest` 46 of 46 scenarios red (assertions, no undefined or ambiguous step); `RunMigrate3xDefinitionsAcceptanceTest` (M8-01) green, unaffected. Negative tests assert first that the analyzer produced a report (`assertAnalyzed`: `clients-global-qos` is present), so they are red on the stub too.
- Golden list of the full 3.13.7 fixture (`full-fixture-golden.txt`; one line `rule|kind|vhost|name|about`, report order; derived by the test developer from the rule table; ARCHITECT REVIEW REQUESTED). 62 findings: 9 blockers, 36 warnings, 17 infos, 10 message loss; summary `topologyItems 24, compatibleItems 10, needDecision 14`. Only the `classic-mirrored` and `classic-plain` queues are mirrored (by `ha-all`, `ha-exactly`). Derivation notes: non-durable classic queues get `queue-not-durable` and `classic-to-quorum-ordering` but not `classic-not-replicated` or `classic-index-v1` (see question 1); every durable classic queue gets the file-only `classic-index-v1` warning (no `x-queue-version`).
```
classic-index-v1|Queue|/|classic-dlx|-
classic-index-v1|Queue|/|classic-lazy|-
classic-index-v1|Queue|/|classic-locator|-
classic-index-v1|Queue|/|classic-maxlen-bytes|-
classic-index-v1|Queue|/|classic-mirrored|-
classic-index-v1|Queue|/|classic-plain|-
classic-index-v1|Queue|/|classic-prio|-
classic-index-v1|Queue|/|classic-sac|-
classic-index-v1|Queue|/|classic-ttl|-
classic-index-v1|Queue|v1|v1-q|-
access-declare-vhost|VirtualHost|-|/|-
access-declare-vhost|VirtualHost|-|v1|-
access-declare-user|User|-|adm|-
user-administrator-tag|User|-|adm|-
user-administrator-tag|User|-|guest|-
user-guest|User|-|guest|-
access-declare-user|User|-|mon|-
access-declare-user|User|-|svc|-
classic-to-quorum-unmeasured|Global|-|-|-
clients-amqp-address-v1|Global|-|-|-
clients-global-qos|Global|-|-|-
clients-transient-queues|Global|-|-|-
not-covered-global-parameter|Global|-|-|cluster_name
not-covered-global-parameter|Global|-|-|internal_cluster_id
classic-to-quorum-ordering|Queue|/|classic-autodel|-
queue-not-durable|Queue|/|classic-autodel|-
quorum-rejected-setting|Queue|/|classic-autodel|auto_delete
classic-not-replicated|Queue|/|classic-dlx|-
classic-to-quorum-ordering|Queue|/|classic-dlx|-
classic-not-replicated|Queue|/|classic-lazy|-
classic-to-quorum-ordering|Queue|/|classic-lazy|-
quorum-rejected-setting|Queue|/|classic-lazy|x-queue-mode
classic-not-replicated|Queue|/|classic-locator|-
classic-to-quorum-ordering|Queue|/|classic-locator|-
queue-master-locator-argument|Queue|/|classic-locator|x-queue-master-locator
classic-not-replicated|Queue|/|classic-maxlen-bytes|-
classic-to-quorum-ordering|Queue|/|classic-maxlen-bytes|-
classic-not-replicated|Queue|/|classic-mirrored|-
classic-to-quorum-ordering|Queue|/|classic-mirrored|-
classic-not-replicated|Queue|/|classic-plain|-
classic-to-quorum-ordering|Queue|/|classic-plain|-
classic-not-replicated|Queue|/|classic-prio|-
classic-to-quorum-ordering|Queue|/|classic-prio|-
quorum-rejected-setting|Queue|/|classic-prio|x-max-priority
classic-not-replicated|Queue|/|classic-sac|-
classic-to-quorum-ordering|Queue|/|classic-sac|-
classic-to-quorum-ordering|Queue|/|classic-transient|-
queue-not-durable|Queue|/|classic-transient|-
classic-not-replicated|Queue|/|classic-ttl|-
classic-to-quorum-ordering|Queue|/|classic-ttl|-
ha-policy-key|Policy|/|ha-all|ha-mode,ha-promote-on-shutdown,ha-sync-mode
ha-policy-key|Policy|/|ha-exactly|ha-mode,ha-params
policy-queue-mode|Policy|/|ha-exactly|queue-mode
queue-master-locator-policy-key|Policy|/|ha-exactly|queue-master-locator
access-set-permissions|Permission|/|guest|-
access-set-permissions|Permission|/|svc|-
access-set-topic-permissions|TopicPermission|/|svc|ex-delayed
not-covered-parameter|Parameter|/|up1|federation-upstream
classic-not-replicated|Queue|v1|v1-q|-
classic-to-quorum-ordering|Queue|v1|v1-q|-
access-set-permissions|Permission|v1|guest|-
access-set-permissions|Permission|v1|svc|-
```
- Questions for the architect (the tests assume the answer in brackets): (1) `classic-not-replicated` item column says "Q classic, durable", its condition says "every classic queue", `classic-to-quorum-ordering` says "every classic queue": [durable only for not-replicated and index-v1; ordering, rejected-setting and rejected-argument for every classic queue]. (2) `name` and `about` of the items the table leaves open: [Permission and TopicPermission: `name` = user, topic permission `about` = [exchange]; `not-covered-parameter`: kind Parameter, `name` = parameter name, `about` = [component]; `not-covered-global-parameter`: `name` null, `about` = [parameter name]; VirtualHost `name` = the vhost, `vhost` null]. (3) `queue-master-locator-argument` message: the table says "rejected on classic and quorum queues", the scenario says "RabbitMQ 4 does not allow it" for both rows [tests require both phrases]. (4) The ordering message: the brief says "always orders messages by priority", the scenario says "orders messages by priority" [tests require the shorter phrase]. (5) `defaultDecision`: only `user-guest` is Skip, every other finding Accept [asserted]. (6) `ReportSource.name` for a file report is not specified [only `live = false` is asserted]. (7) JSON field names of the proposal types other than convertQueueType, manual and declareUser are not in the brief [only the `"type"` value, lower camel case of the class name, is asserted; declareUser: `name`, `tags`, `hasPasswordHash`, `acknowledgement`]. (8) The summary rule names a binding `source->destination:routingKey`, but no rule emits a binding finding, so that naming cannot be tested [binding is only checked as a counted compatible item]. (9) The mirroring helper has no signature in the brief: it is tested through the analyzer (the `classic-not-replicated` message names the mirroring policy). (10) "no in-place upgrade as safe": asserted as every proposal text that says "in place" or "in-place" also says "not" or "never".
- Mutation checks a to f: recorded in step 4 (need the implementation); the tests that must go red are `ReportOrderAndSummaryTest` + "The message-loss findings come first" (a), "A classic queue with the new index is not flagged" + `ClassicIndexRuleTest` (b), `ReportSecretsTest` + "Secrets in a 3.x export never reach the report" (c), the mirroring-key outline + `PolicyRulesTest` (d), `ReportOrderAndSummaryTest` summary tests (e), `RuleCatalogTest` kitchen sink (f).
- Not run in step 2: `verifyTestDiscovery` (filtered runs make it fail by design), the full suite, the UI tests, `@broker`.
- Step 2 answers (architect, 2026-11-09), brief, scenarios and tests agree after these:
  1. `classic-not-replicated` and `classic-index-v1`: durable classic queues only (a non-durable queue gets `queue-not-durable`); `classic-to-quorum-ordering`, `quorum-rejected-setting`, `classic-rejected-argument`, `quorum-max-in-memory`: every classic queue, durable or not. The table is fixed. CONFIRMS the tests; no test changes.
  2. Item naming: Permission `name` = user, `vhost` = the vhost, `about` empty; TopicPermission the same with `about` = [exchange]; `not-covered-parameter`: kind Parameter, `name` = parameter name, `about` = [component]; `not-covered-global-parameter`: kind Global, `name` null, `vhost` null, `about` = [parameter name]; VirtualHost: `name` = the vhost name, `vhost` null; User: `name` = user, `vhost` null. CONFIRMS the tests.
  3. `queue-master-locator-argument` message contains BOTH phrases "RabbitMQ 4 does not allow it" and "rejected on classic and quorum queues". Table fixed. CONFIRMS the tests.
  4. The ordering text "a quorum queue on 4.x always orders messages by priority" contains the shorter scenario phrase "orders messages by priority" as a substring, so both agree; the tests (shorter phrase, substring match) stay. No change.
  5. Only `user-guest` has `defaultDecision = Skip`; every other finding `Accept`. CONFIRMS the test.
  6. `ReportSource.name` is the live connection name for a live report and `null` for a file report (the UI shows the file name itself; the report does not carry a path). JSON `source.name` is null then. Add an assertion `name == null` for the file case and `name == "legacy-eu"` for the live case: SMALL TEST ADDITION in `ReportJsonTest` or `ReportOrderAndSummaryTest` (not a change of an existing assertion).
  7. JSON proposal shapes (type = lower camel case of the class name): `convertQueueType {target, acknowledgement|null}`; `keepClassic {}`; `removeArgument {argument}`; `setArgument {argument, value}` (value: JSON string for Text, number for Whole or Decimal, boolean for Flag, null otherwise); `clearAutoDelete {}`; `makeDurable {}`; `dropPolicyKeys {keys}`; `declareVirtualHost {name}`; `declareUser {name, tags, hashingAlgorithm|null, hasPasswordHash, acknowledgement|null}`; `setPermissions {vhost, user}`; `setTopicPermissions {vhost, user, exchange}`; `manual {title, text, recommended}`; `none {}`. Optional extra tests for the unasserted shapes, not required.
  8. DROPPED the binding naming sentence (rule 2 and the unit-test bullet): no rule emits a binding finding here; a binding is only counted and compatible. CONFIRMS the test.
  9. The mirroring helper stays internal (any signature, for example `internal fun winningPolicy(...)`); tested through `classic-not-replicated` (message names the mirroring policy). CONFIRMS the test.
  10. "No in-place upgrade as safe" = every proposal text containing "in place" or "in-place" also contains "not" or "never" (brief wording unified). CONFIRMS the test.
- Golden review (architect, 2026-11-09): VERDICT: matches the rule table exactly, nothing to add or remove. Checked against the fixture: 24 topology items (13 queues, 4 exchanges, 3 bindings, 3 policies, 1 operator policy); needDecision 14 (9 durable classic queues with `classic-not-replicated`, `v1-q` in vhost v1, the 2 non-durable queues, `ha-all`, `ha-exactly`); compatible 10 (`quorum-q`, 4 exchanges, 3 bindings, `limits`, `op-limit`); 9 blockers (2 `queue-not-durable`, 3 `quorum-rejected-setting`, 1 `queue-master-locator-argument`, 2 `ha-policy-key`, 1 `queue-master-locator-policy-key`); 10 `classic-index-v1` warnings (class MessageLoss) first; mirrored queues only `classic-mirrored` (`ha-all`) and `classic-plain` (`ha-exactly`); `guest` has the administrator tag so it gets `user-administrator-tag` and `user-guest` and no `access-declare-user`; 4 permissions and 1 topic permission; the global findings; `op-limit` absent from parameters; sort order (message loss, vhost null first, kind enum order, name, rule, about) holds line by line. No wrong severity or class seen.
- The four architect defaults (Open points) are still my recommendation.
- Step 2 additions (test developer, round 0, after the architect answers): `ReportJsonTest` gained `every proposal type has exactly the documented JSON shape` (all 13 types, 18 cases incl. null acknowledgement, null algorithm, setArgument value kinds) and `source name is null for a file report and the connection name for a live report` (model and JSON). Red on the stub; no existing assertion changed.
