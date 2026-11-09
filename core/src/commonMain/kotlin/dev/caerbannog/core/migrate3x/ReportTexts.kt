package dev.caerbannog.core.migrate3x

/** The message of a finding (what is wrong) and its reason in plain words. */
internal data class RuleText(val message: String, val why: String)

/**
 * Every fixed text of the report in one place, so tests and screens can compare them. Templates insert only item names, argument
 * names and policy keys; never a value from the document that could be a secret.
 */
internal object ReportTexts {
    const val TICK_ORDERING = "Ordering by priority may change"
    const val TICK_KNOWN_PASSWORD = "This user has a publicly known password"

    private const val IMPORT_SKIPS = "an import of the raw file would skip the queue without an error (HTTP 204)"

    // ---- policies ----

    fun haPolicyKey(keys: List<String>) = RuleText(
        "RabbitMQ 4 rejects these keys when the policy is created or imported (not \"ignored\"): ${keys.joinToString()}.",
        "Mirroring does not exist on 4.x. To keep replication, move the matching queues to quorum queues.",
    )

    val queueMasterLocatorPolicyKey = RuleText(
        "A policy with the key queue-master-locator is rejected as a deprecated argument on 4.x.",
        "The key is deprecated on 4.x, so the policy cannot be created or imported with it.",
    )

    fun operatorPolicyKeyRejected(keys: List<String>) = RuleText(
        "An operator policy on 4.x accepts only the limits; the key ${keys.joinToString()} is rejected there.",
        "A key that is not a limit has to be set in a normal policy.",
    )

    fun operatorPolicyKeyManual(keys: List<String>) = Proposal.Manual(
        "Set it as a normal policy",
        "Create a normal policy for the queues that need ${keys.joinToString()}; an operator policy keeps only the limits.",
        recommended = false,
    )

    val policyQueueMode = RuleText(
        "The key queue-mode is accepted on 4.x.",
        "Lazy mode has no effect on a quorum queue.",
    )

    fun policyMaxInMemory(keys: List<String>) = RuleText(
        "The keys ${keys.joinToString()} are accepted by 4.x; the effect is not verified.",
        "The app has not measured what these keys do on 4.x.",
    )

    fun notCoveredPolicyKey(keys: List<String>) = RuleText(
        "Federation is not migrated by this app (keys ${keys.joinToString()}).",
        "A policy with federation keys needs the federation set up on the 4.x broker.",
    )

    val federationManual = Proposal.Manual(
        "Set up federation by hand",
        "Enable the federation plugin on the 4.x broker, then create the upstream and the policy there.",
        recommended = true,
    )

    val policyPatternUnreadable = RuleText(
        "The app could not read the pattern of this policy, so it did not match queues against it.",
        "The pattern is not a regular expression this app understands; the mirroring check for queues ignores the policy.",
    )

    // ---- queues ----

    val queueNotDurable = RuleText(
        "A non-durable queue: RabbitMQ 4 does not allow it. An import of the raw file would skip it without an error (HTTP 204).",
        "The 4.x broker answers 204 and does not create the queue, so it would be missing without any message.",
    )

    val queueNotDurableManual = Proposal.Manual(
        "Declare it durable or let the application declare it",
        "Declare the queue durable, or let the application declare it when it starts.",
        recommended = true,
    )

    val queueMasterLocatorArgument = RuleText(
        "The argument x-queue-master-locator: RabbitMQ 4 does not allow it. It is rejected on classic and quorum queues, and $IMPORT_SKIPS.",
        "The argument is deprecated on 4.x; it has to be removed before the queue is declared.",
    )

    fun quorumRejectedSetting(setting: String) = RuleText(
        "A quorum queue rejects this setting: $setting.",
        "Converting the queue to a quorum queue only works without this setting, so converting needs it removed.",
    )

    fun classicRejectedArgument(argument: String) = RuleText(
        "The argument $argument: a classic queue on 4.x rejects it.",
        "The queue cannot be declared on 4.x with this argument as it is.",
    )

    fun quorumMaxInMemory(argument: String) = RuleText(
        "A quorum queue accepts the argument $argument; the effect is not verified.",
        "The app has not measured what the argument does on a quorum queue.",
    )

    fun classicNotReplicated(mirroringPolicy: String?, hasPriority: Boolean): RuleText {
        val mirrored = mirroringPolicy?.let { " The mirroring policy \"$it\" gives it mirrors on 3.x; it loses its mirrors." }.orEmpty()
        val priority = if (hasPriority) " The queue has x-max-priority, so it is proposed to stay classic (declarable on 4.x, but not replicated)." else ""
        return RuleText(
            "Classic queues are no longer replicated on 4.x.$mirrored$priority",
            "Quorum queues are the replicated type on 4.x.",
        )
    }

    fun classicToQuorumOrdering(hasPriority: Boolean): RuleText {
        val kept = if (hasPriority) " A queue with x-max-priority that is kept classic is not replicated." else ""
        return RuleText(
            "A quorum queue on 4.x always orders messages by priority; messages that carry a priority may be delivered in a different order " +
                "than in a classic queue (without x-max-priority a classic queue is first in, first out).$kept",
            "This was measured on a 4.x broker.",
        )
    }

    fun queueTypeUnknown(type: String) = RuleText(
        "The app does not know this queue type ($type).",
        "It cannot say what this queue needs on 4.x.",
    )

    val queueTypeUnknownManual = Proposal.Manual("Check the queue type by hand", "Find out whether the 4.x broker supports this queue type.", recommended = true)

    fun exchangeTypeNotBuiltin(type: String) = RuleText(
        "This exchange type ($type) may need a plugin that is not enabled on the 4.x broker; the app does not check.",
        "Only direct, fanout, topic and headers are built in.",
    )

    val exchangePluginManual = Proposal.Manual("Enable the plugin on the 4.x broker", "Enable the plugin that provides this exchange type before the exchange is declared.", recommended = true)

    // ---- message loss ----

    private const val CLASSIC_INDEX_TAIL =
        "RabbitMQ 4.3 and later cannot read it: an in-place upgrade would silently empty this queue (messages lost, no error in the management API)."

    val classicIndexLive = RuleText(
        "This queue uses the old classic queue index (version 1). $CLASSIC_INDEX_TAIL",
        classicIndexWhy("The broker reported index version 1 for this queue."),
    )

    val classicIndexUnknown = RuleText(
        "The index version could not be read; treated as version 1. The queue may use the old classic queue index (version 1). $CLASSIC_INDEX_TAIL",
        classicIndexWhy("The broker did not report the index version, even after retries."),
    )

    val classicIndexFileStates = RuleText(
        "This queue uses the old classic queue index (version 1), as the file states. $CLASSIC_INDEX_TAIL",
        classicIndexWhy("The file sets x-queue-version to 1."),
    )

    val classicIndexFileSilent = RuleText(
        "The file does not carry the index version, so this queue may use the old classic queue index (version 1). $CLASSIC_INDEX_TAIL",
        classicIndexWhy("A definitions file does not say which index a queue uses; a live connection can."),
    )

    private fun classicIndexWhy(reason: String) =
        "$reason The app cannot detect the loss after an upgrade: the management API shows nothing and only the broker log shows it."

    val classicIndexRecommended = Proposal.Manual(
        "Migrate to a new 4.x broker",
        "Do not upgrade in place: migrate the topology to a new 4.x broker and move the messages with a Shovel (the Shovel path does not read the old index).",
        recommended = true,
    )

    val classicIndexPolicyFix = Proposal.Manual(
        "Secondary, by hand: set the queue-version policy",
        "Set the policy `queue-version: 2`, restart the 3.x node gracefully and check that the queue shows version 2 (the policy alone on a running node is not proof).",
        recommended = false,
    )

    val classicIndexUpgradeThrough = Proposal.Manual(
        "Secondary, by hand: upgrade through RabbitMQ 4.0, 4.1 or 4.2 first",
        "Upgrade through RabbitMQ 4.0, 4.1 or 4.2 first, then to the later version, and check the queue after each step.",
        recommended = false,
    )

    // ---- parameters and live facts ----

    fun notCoveredParameter(component: String) = RuleText(
        "A $component parameter is not migrated by this app; it is listed by component and name only.",
        "Parameters of plugins can hold credentials, so the app reads no value.",
    )

    val notCoveredParameterManual = Proposal.Manual("Recreate it by hand", "Create the parameter on the 4.x broker yourself.", recommended = true)

    val notCoveredGlobalParameter = RuleText(
        "This global parameter is not migrated.",
        "Global parameters belong to the broker, not to the topology.",
    )

    val mirroringInUse = RuleText(
        "The broker reports classic queue mirroring as in use.",
        "Mirroring is removed on 4.x; queues that use it lose their mirrors.",
    )

    fun deprecatedFeatureInUse(feature: String) = RuleText(
        "The broker reports this deprecated feature as in use: $feature.",
        "A 4.x broker may deny a deprecated feature by default.",
    )

    val deprecatedFeatureManual = Proposal.Manual("Check the clients that use it", "Find the clients that use this feature and change them before they connect to 4.x.", recommended = true)

    // ---- global checks ----

    val clientsGlobalQos = RuleText(
        "Check your clients: basic.qos(global=true) is denied by default on 4.x.",
        "The app cannot detect it from definitions.",
    )

    val clientsTransientQueues = RuleText(
        "Check your clients: transient, non-exclusive queues are refused by default on 4.x.",
        "The app cannot detect it from definitions.",
    )

    val clientsAmqpAddressV1 = RuleText(
        "Check your clients: AMQP 1.0 address format v1 is denied by default on 4.x.",
        "The app cannot detect it from definitions.",
    )

    val classicToQuorumUnmeasured = RuleText(
        "Redelivery, the delivery limit and exclusive consumers may behave differently on a quorum queue; the app has not measured them (unverified).",
        "These differences are not measured yet, so the app cannot say what changes.",
    )

    // ---- access ----

    val accessDeclareVhost = RuleText(
        "This virtual host belongs to the access migration, not to a topology migration.",
        "The access migration declares it on the 4.x broker.",
    )

    fun accessDeclareUser(hasPasswordHash: Boolean) = RuleText(
        "This user belongs to the access migration, not to a topology migration.",
        if (hasPasswordHash) "The password hash is carried over, never shown." else "The user is declared without a password.",
    )

    val accessSetPermissions = RuleText(
        "These permissions belong to the access migration, not to a topology migration.",
        "The access migration sets them on the 4.x broker.",
    )

    val accessSetTopicPermissions = RuleText(
        "These topic permissions belong to the access migration, not to a topology migration.",
        "The access migration sets them on the 4.x broker.",
    )

    val userGuest = RuleText(
        "The user guest has a publicly known password.",
        "It is skipped by default; accepting it needs an explicit confirmation.",
    )

    val userAdministratorTag = RuleText(
        "The migration gives this user the administrator tag on 4.x.",
        "An administrator can do everything on the broker.",
    )

    val userHashWithoutAlgorithm = RuleText(
        "The password hash has no hashing algorithm.",
        "Without the hashing algorithm the login would fail.",
    )

    fun userHashAlgorithmUnsupported(algorithm: String) = RuleText(
        "RabbitMQ 4 rejects this algorithm: $algorithm.",
        "The user cannot be declared with this hash.",
    )

    val newPasswordManual = Proposal.Manual(
        "Set a new password on the broker",
        "Set a new password for this user on the 4.x broker, outside the app. The app offers no password field.",
        recommended = true,
    )

    val userNoPasswordHash = RuleText(
        "There is no password hash in the export (for example external authentication); the app does not invent a password.",
        "The user is declared without a password.",
    )

    fun accessNotInFile(vhost: String) = RuleText(
        "The file holds no virtual hosts, users, permissions or topic permissions, so the access migration is empty. " +
            "The virtual host $vhost must already exist on the 4.x broker.",
        "A per-virtual-host export carries topology only.",
    )

    fun accessNotInFileManual(vhost: String) = Proposal.Manual(
        "Create the access data on the 4.x broker",
        "Make sure the virtual host $vhost, its users and permissions exist on the 4.x broker.",
        recommended = true,
    )
}
