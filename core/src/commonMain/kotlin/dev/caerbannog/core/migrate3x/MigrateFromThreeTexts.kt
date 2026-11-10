package dev.caerbannog.core.migrate3x

/** The fixed words of the Migrate from 3.x screen (owner decision 5: as in `docs/ux/Migrate3x.dc.html`). Never built from file content. */
object MigrateFromThreeTexts {
    const val HASH_HIDDEN: String = "hash hidden"
    const val NO_PASSWORD_HASH: String = "no password hash"
    const val FILE_UNREADABLE: String = "The file could not be read"
    const val FILE_TOO_LARGE: String = "The file is larger than 64 MB"
    const val SAVE_FAILED_PREFIX: String = "The report could not be saved"
    const val REPORT_COPIED: String = "Report copied as text"
    const val ACCESS_EMPTY: String = "The file holds no users, virtual hosts or permissions, so the access migration is empty."

    const val LOSS_DETECTION: String =
        "These queues use the old classic queue index (version 1), which is the 3.13 default. RabbitMQ 4.3 and later cannot read it. " +
            "The loss cannot be detected after the upgrade. A definitions file cannot tell the index version, so this list is every " +
            "classic queue without \"x-queue-version: 2\"."
    const val LOSS_FIX_NEW_BROKER_TITLE: String = "Recommended: do not upgrade in place"
    const val LOSS_FIX_NEW_BROKER_TEXT: String =
        "Build a new 4.x broker, apply the migration this screen generates, then move the messages with a Shovel. " +
            "A Shovel does not read the old index."
    const val LOSS_FIX_POLICY_TITLE: String = "By hand: set the policy \"queue-version: 2\""
    const val LOSS_FIX_POLICY_TEXT: String =
        "Restart the node gracefully, then check that every queue shows version 2. Not offered by this app."
    const val LOSS_FIX_UPGRADE_THROUGH_TITLE: String = "By hand: upgrade through 4.0, 4.1 or 4.2 first"
    const val LOSS_FIX_UPGRADE_THROUGH_TEXT: String = "Then upgrade to the later version and check the queues after each step. Not offered by this app."

    /** M8-06b STUB (ruling 3): the one place that builds the text of a save problem; the composables only draw it. */
    fun problemText(problem: SaveProblem): String = ""

    /** "1,204" for 1204: the thousands separator the mockup uses. */
    fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

    /** "1 queue", "3 queues". */
    fun plural(n: Int, singular: String, plural: String = singular + "s"): String = "${count(n)} ${if (n == 1) singular else plural}"

    fun itemCount(n: Int): String = plural(n, "item")

    fun showAll(n: Int): String = "Show all ${count(n)}"

    fun previewNote(shown: Int, total: Int): String = "(first $shown of ${count(total)})"

    fun lossTitle(queues: Int): String =
        "An in-place upgrade to RabbitMQ 4.3 or later would silently empty ${plural(queues, "classic queue")}"

    /** The title of a group: what its rule is about, in words (the severity is shown beside it). */
    fun ruleTitle(rule: String): String = ruleTitles[rule] ?: rule

    private val ruleTitles: Map<String, String> = mapOf(
        "ha-policy-key" to "Mirroring policy keys are rejected on 4.x",
        "queue-master-locator-policy-key" to "The policy key queue-master-locator is rejected on 4.x",
        "operator-policy-key-rejected" to "Operator policy keys that 4.x rejects",
        "policy-queue-mode" to "Policy key queue-mode has no effect on quorum queues",
        "policy-max-in-memory" to "Policy keys whose effect on 4.x is not verified",
        "not-covered-policy-key" to "Policy keys this app does not migrate (federation)",
        "policy-pattern-unreadable" to "Policy pattern the app could not evaluate",
        "queue-not-durable" to "Non-durable queues are skipped by a 4.x import",
        "queue-master-locator-argument" to "Queue argument x-queue-master-locator is rejected on 4.x",
        "quorum-rejected-setting" to "Settings a quorum queue rejects",
        "classic-rejected-argument" to "Arguments a classic queue rejects on 4.x",
        "quorum-max-in-memory" to "Quorum queue arguments whose effect is not verified",
        "classic-not-replicated" to "Classic queues are no longer replicated on 4.x",
        "classic-to-quorum-ordering" to "Classic to quorum: ordering by priority may change",
        "queue-type-unknown" to "Queue types the app does not know",
        "classic-index-v1" to "Classic queue with the old index (also listed in the block above)",
        "exchange-type-not-builtin" to "Exchange types that may need a plugin",
        "not-covered-parameter" to "Parameters this app does not migrate",
        "not-covered-global-parameter" to "Global parameters this app does not migrate",
        "mirroring-in-use" to "Mirroring is in use on the broker",
        "deprecated-feature-in-use" to "Deprecated features in use on the broker",
        "clients-global-qos" to "Check your clients: basic.qos with global=true",
        "clients-transient-queues" to "Check your clients: transient, non-exclusive queues",
        "clients-amqp-address-v1" to "Check your clients: AMQP 1.0 address format v1",
        "classic-to-quorum-unmeasured" to "Classic to quorum: behaviour that is not measured",
        "access-declare-vhost" to "Virtual hosts (handled by the access migration)",
        "access-declare-user" to "Users (handled by the access migration)",
        "access-set-permissions" to "Permissions (handled by the access migration)",
        "access-set-topic-permissions" to "Topic permissions (handled by the access migration)",
        "user-guest" to "The user guest has a publicly known password",
        "user-administrator-tag" to "Users that get the administrator tag",
        "user-hash-without-algorithm" to "Password hashes without a hashing algorithm",
        "user-hash-algorithm-unsupported" to "Hashing algorithms that 4.x rejects",
        "user-no-password-hash" to "Users without a password hash",
        "access-not-in-file" to "A per-virtual-host file holds no access data",
    )
}
