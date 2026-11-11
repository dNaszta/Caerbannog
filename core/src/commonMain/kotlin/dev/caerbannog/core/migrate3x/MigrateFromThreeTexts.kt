package dev.caerbannog.core.migrate3x

/** The fixed words of the Migrate from 3.x screen (owner decision 5: as in `docs/ux/Migrate3x.dc.html`). Never built from file content. */
object MigrateFromThreeTexts {
    /** M8-06c (owner decision 2026-11-10): one line for both sources. */
    const val INFO_LINE: String = "This screen only reads a file or the connected 3.x broker. It sends nothing that changes any broker."
    const val LIVE_NOT_AVAILABLE: String = "Connect to a RabbitMQ 3.x broker to read it live"
    const val SOURCE_FILE: String = "Definitions file"
    const val LIVE_DISABLED: String = "Live connection"
    const val READ_ONLY: String = "read only"
    const val ANALYSE_LIVE: String = "Analyse live connection"
    const val ANALYSE_AGAIN: String = "Analyse again"
    const val TRY_AGAIN: String = "Try again"
    const val LIVE_LOADING_HINT: String = "Reading the broker can take a few seconds per virtual host. Nothing on it is changed."

    fun liveOption(name: String): String = "Live connection: $name"
    fun liveDisconnected(name: String): String = "from $name (disconnected)"
    fun analyseLiveHint(name: String): String = "Reads the definitions of $name with GET requests. Nothing on the broker is changed."
    fun liveLoading(name: String): String = "Reading and analysing $name"

    /** M8-06e item 3: the notice after a saved JSON report; the path comes from the saver (a user's folder and file name), so it is escaped. */
    fun reportSaved(path: String): String = "Report saved to ${escapeForText(path)}"

    /** M8-06c round 1 (R2, finding 4). */
    const val FILE_EXPORT_HINT: String = "In the 3.x management UI: Overview, Export definitions. Or: rabbitmqctl export_definitions file.json"
    const val NOTHING_ANALYSED: String = "Nothing was analysed."
    const val CHOOSE_FILE: String = "Choose definitions file"

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
    /** The loss detection text of a live report: the broker reported the version. */
    const val LOSS_DETECTION_LIVE: String =
        "These queues use the old classic queue index (version 1), which is the 3.13 default, or the broker did not report their index version. " +
            "RabbitMQ 4.3 and later cannot read the old index. The loss cannot be detected after the upgrade. " +
            "This list comes from the broker: queues it reports as version 2 are not listed."
    const val LOSS_FIX_NEW_BROKER_TITLE: String = "Recommended: do not upgrade in place"
    const val LOSS_FIX_NEW_BROKER_TEXT: String =
        "Build a new 4.x broker, apply the migration this screen generates, then move the messages with a Shovel. " +
            "A Shovel does not read the old index."
    const val LOSS_FIX_POLICY_TITLE: String = "By hand: set the policy \"queue-version: 2\""
    const val LOSS_FIX_POLICY_TEXT: String =
        "Restart the node gracefully, then check that every queue shows version 2. Not offered by this app."
    const val LOSS_FIX_UPGRADE_THROUGH_TITLE: String = "By hand: upgrade through 4.0, 4.1 or 4.2 first"
    const val LOSS_FIX_UPGRADE_THROUGH_TEXT: String = "Then upgrade to the later version and check the queues after each step. Not offered by this app."

    const val SKIPPED_GROUP_TITLE: String = "Skipped by you"
    const val UNVERIFIED: String = "unverified"
    const val UNVERIFIED_TOOLTIP: String = "Not measured on a broker yet"
    const val REPLACE_HAS_DELETE: String = "A replacement must not delete anything. Nothing was stored; the item is still undecided."
    const val REPLACE_NOT_STORED: String = "The item cannot be replaced. Nothing was stored; the item is still undecided."
    const val MANUAL_NEEDED: String =
        "This item cannot be accepted as it is. Choose an automatic alternative, skip it, or replace it with your own actions."
    const val CONFIRM_AGAIN: String =
        "Migration files were already saved from this analysis. Generating again writes new files with new versions and leaves the old ones " +
            "as they are. Generate again?"
    const val RESULT_NOTHING_SENT: String =
        "Nothing was sent to any broker. Apply them on a 4.x connection from the Migrations screen; each applies only on a connection " +
            "whose virtual host matches."
    const val NOTHING_WRITTEN: String = "No migration was written: nothing is left to write"
    const val SKIPPED_BY_YOU: String = "skipped by you"
    const val NAME_CANNOT_BE_WRITTEN: String = "its name cannot be written into a migration"
    const val EXPORT_FAILED_PREFIX: String = "The migration file could not be saved"
    const val NUMBERING_FROM_ONE: String = "The folder could not be read, so the numbering starts at 0001."
    const val NO_FOLDER_CHOSEN: String = "No folder was chosen. Nothing was saved."
    const val NO_FREE_VERSION: String = "The migrations folder has no free version number left (the highest is 9999)."

    // ---- the access result (owner decisions D3 and D6, pinned in AccessGenerationReportTest) ----
    const val SECRET_FILE_WARNING: String =
        "This file contains password hashes. Anyone who has it can try to guess the passwords offline. " +
            "Keep it secret: do not commit it to git or put it in a shared or synced folder."
    const val NOT_MIGRATED_TITLE: String = "Users not migrated"
    const val PERMISSIONS_LEFT_OUT_TITLE: String = "Permissions left out"
    const val GUEST_NOT_MIGRATED: String = "not created; it has a publicly known password"
    const val NO_HASH_NOT_MIGRATED: String = "no password hash in the export; set a password on the broker if it needs one"
    const val NO_ALGORITHM_NOT_MIGRATED: String = "its password hash has no hashing algorithm; set a new password on the broker"
    const val DEFAULT_VHOST_NOTE: String = "The default virtual host / already exists on 4.x and is not changed"
    fun unsupportedAlgorithmNotMigrated(algorithm: String): String =
        "set a new password on the broker; its password hash uses $algorithm, which 4.x does not accept"
    const val USER_NOT_MIGRATED: String = "the user is not migrated"
    const val ACCESS_PILL: String = "access, all virtual hosts"
    const val READABLE_BY_YOU_ONLY: String = "readable by you only"
    const val ACCESS_FILE_LABEL: String = "access"
    const val ACCESS_NEEDS_ADMIN: String =
        "The access migration needs a user with the administrator tag and the level Full control; apply it before the topology files."
    fun permissionLabel(user: String, vhost: String): String = "$user on $vhost"

    fun accessLine(summary: AccessSummary): String =
        "Access: ${plural(summary.virtualHosts, "virtual host")}, ${plural(summary.users, "user")}, " +
            "${plural(summary.permissions, "permission")}, ${plural(summary.topicPermissions, "topic permission")}"

    const val GENERATE_HINT: String = "Generate is possible when every item is decided."

    /** "item 14 of 381": the place of the open item in the list of items that need a decision. */
    fun itemPosition(number: Int, total: Int): String = "item ${count(number)} of ${count(total)}"

    /** The name of an item for the sheet, escaped like every string taken from a file. Bindings are named `source->destination:routingKey`. */
    fun itemTitle(item: ReviewItem): String = escapeForText(item.name.orEmpty())

    /** The line of the decision bar. [itemCount] is every item that needs a decision, [pending] the undecided ones. */
    fun pendingLine(itemCount: Int, pending: Int): String = when {
        itemCount == 0 -> "No item needs a decision"
        pending == 0 -> "Every item is decided"
        pending == 1 -> "1 item needs a decision"
        else -> "${count(pending)} items need a decision"
    }

    /** "381 of 381: 340 accepted, 38 skipped (in the Skipped group), 3 replaced." A part that is zero is left out. */
    fun decidedLine(itemCount: Int, accepted: Int, skipped: Int, replaced: Int): String {
        val parts = buildList {
            if (accepted > 0) add("${count(accepted)} accepted")
            if (skipped > 0) add("${count(skipped)} skipped (in the Skipped group)")
            if (replaced > 0) add("${count(replaced)} replaced")
        }
        return "${count(itemCount)} of ${count(itemCount)}: ${parts.joinToString(", ")}."
    }

    /** The sentence under a Replace text the migration format or the core refused; [reason] is escaped here. */
    fun replaceRefused(reason: String): String =
        "The actions are refused: ${escapeForText(reason)}. Nothing was stored; the item is still undecided."

    fun replaceHint(vhost: String): String =
        "Migration actions, the \"up\" list of a migration file. An action without \"vhost\" is for \"/\". " +
            "Only actions for virtual host ${escapeForText(vhost)} are accepted."

    /** One line per reason Accept is blocked: a line per missing tick, or the manual sentence. */
    fun acceptBlockLines(block: DecisionRefusal?): List<String> = when (block) {
        is DecisionRefusal.TickMissing -> Acknowledgement.entries.filter { it in block.missing }.map { "Tick \"${it.text}\" to accept" }
        is DecisionRefusal.ManualNeeded -> listOf(MANUAL_NEEDED)
        else -> emptyList()
    }

    /** What a refusal of `decide` says under the Replace text. */
    fun replaceRefusalText(refusal: DecisionRefusal): String = when (refusal) {
        DecisionRefusal.ReplaceHasDelete -> REPLACE_HAS_DELETE
        is DecisionRefusal.ReplaceInvalid -> replaceRefused(refusal.reason)
        else -> REPLACE_NOT_STORED
    }

    fun pillText(pill: DecisionPill): String = when (pill) {
        DecisionPill.Undecided -> "Needs decision"
        is DecisionPill.Accepted -> if (pill.ticked) "Accepted, ordering tick set" else "Accepted"
        is DecisionPill.Replaced -> "Replaced"
        DecisionPill.Skipped -> "Skipped"
    }

    /** "Review" for an item that needs a decision or was skipped, "Change" once it was accepted or replaced. */
    fun reviewButtonText(pill: DecisionPill): String = when (pill) {
        is DecisionPill.Accepted, is DecisionPill.Replaced -> "Change"
        DecisionPill.Undecided, DecisionPill.Skipped -> "Review"
    }

    fun savedHeadline(files: Int): String = "${plural(files, "migration")} saved"

    fun exportedHeadline(files: Int): String = "${plural(files, "migration")} exported"

    fun vhostHint(virtualHosts: Int): String =
        "Each file applies only on a connection whose virtual host matches. " +
            "A source with ${count(virtualHosts)} virtual hosts needs one 4.x profile per virtual host."

    /** The one place that builds the text of a save problem; the composables only draw it. Reasons and paths are escaped here. */
    fun problemText(trouble: GenerationState.Trouble): String = when (val problem = trouble.problem) {
        SaveProblem.NoFolderChosen -> NO_FOLDER_CHOSEN
        is SaveProblem.Folder -> "${problem.text}. No file was saved."
        SaveProblem.NoFreeVersion -> NO_FREE_VERSION
        is SaveProblem.FileRefused ->
            "${problem.version}.json was not saved: ${escapeForText(problem.reason)}. Saved: ${versionList(trouble.saved)}. " +
                "Not saved: ${versionList(trouble.unsaved)}. The saved files stay in the folder."
    }

    private fun versionList(files: List<SavedFile>): String = if (files.isEmpty()) "none" else files.joinToString(", ") { it.version }

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
