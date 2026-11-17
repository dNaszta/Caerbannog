package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.echoableName

/**
 * The fixed texts of the planner, in one place: plain text, no markup. A name from a plan goes into a message only through the echo
 * rule ([echoableName]); a name the rule refuses is left out and the sentence stays whole without it.
 */
object PlannerTexts {
    /** The name of the dead-lettering kind: the card, the title of its form and the key of `chooseKind`. */
    const val DEAD_LETTER_KIND: String = "Dead-lettering for a queue"

    /** The kinds the wizard offers, in the order of the mockup and the spec. None deletes anything. */
    val WIZARD_KINDS: List<String> = listOf(
        "Queue", "Exchange", "Binding", "Stream", "Policy", DEAD_LETTER_KIND, "MQTT route",
    )

    /** The kinds that have a form today, in wizard order; every kind of [WIZARD_KINDS] has one since M9-09. */
    val BUILT_KINDS: List<String> = WIZARD_KINDS

    fun mqttRefusal(reason: MqttFilterRefusal): String = when (reason) {
        MqttFilterRefusal.Empty -> "Enter a topic filter"
        MqttFilterRefusal.HashNotLast -> "# must be the last level"
        MqttFilterRefusal.PlusNotWholeLevel -> "+ must stand for a whole level"
        MqttFilterRefusal.DotInLevel -> "A level cannot contain a dot: RabbitMQ would read it as another level"
        MqttFilterRefusal.StarInLevel -> "A level cannot contain *: use + for a whole level"
        MqttFilterRefusal.EmptyLevel -> "A level cannot be empty"
    }

    /** Always shown with an MQTT route: the app cannot read the broker's MQTT exchange (spike S-B1). */
    const val MQTT_EXCHANGE_ASSUMPTION: String =
        "Assumes the MQTT plugin uses its default exchange amq.topic. The app cannot check this: on a broker with another MQTT exchange this route receives nothing from MQTT."
    const val MQTT_ROUTE_LABEL: String = "MQTT route"
    const val MQTT_FILTER_LABEL: String = "Topic filter"
    const val MQTT_DESTINATION_LABEL: String = "Destination"
    const val MQTT_QUEUE_LABEL: String = "Queue"
    const val MQTT_STREAM_LABEL: String = "Stream"
    fun mqttRouteSaves(key: String): String = "Saved as a binding on amq.topic with the key $key"
    fun mqttMatchingTopic(topic: String): String = "Topic that matches: $topic"

    /** [to] is "stream x", "queue x" or the bare name when the kind is not known. */
    fun mqttRouteTitle(filter: String, to: String): String = "MQTT route $filter to $to"
    fun mqttRouteDetail(key: String): String = "Binding on amq.topic with the key $key"

    fun kindDescription(kind: String): String = when (kind) {
        "Queue" -> "A quorum or classic queue"
        "Exchange" -> "Fanout, direct, topic or headers"
        "Binding" -> "From an exchange to a queue or an exchange"
        "Stream" -> "Optionally with its response stream (DLN)"
        "Policy" -> "Limits and other settings by name pattern"
        DEAD_LETTER_KIND -> DEAD_LETTER_KIND_DESCRIPTION
        "MQTT route" -> "A topic filter to a queue or stream, saved as a binding on amq.topic"
        else -> ""
    }

    /** One sentence per exchange type, under the type choice of the exchange form. */
    fun exchangeTypeNote(type: String): String = when (type) {
        "fanout" -> "Fanout: a message goes to every bound queue."
        "direct" -> "Direct: a message goes to the queues bound with exactly its routing key."
        "topic" -> "Topic: a message goes by a pattern with * and #."
        "headers" -> "Headers: a message goes by its headers, not by the routing key."
        else -> ""
    }

    fun queueTypeNote(type: String): String = when (type) {
        "quorum" -> "Replicated, durable."
        "classic" -> "One copy on one node."
        else -> ""
    }

    /** What a naming style does to a derived name, for the menu of the plan header. */
    fun namingExample(style: NamingStyle): String =
        if (style == NamingStyle.NONE) "No hints; derived names as in kebab-case" else PlanNaming.derived("orders", DerivedWord.DLN, style)

    const val QUEUE_FORM_NOTE: String =
        "Limits and dead-lettering are set with a policy step, so they can be changed later without deleting the queue."
    const val INFO_LINE: String =
        "A plan is a draft. Nothing on this screen changes the broker: saving writes a migration file, and the file is applied from the Migrations screen."
    const val NO_STEPS_HELP: String =
        "Add the first step with the wizard: a queue, an exchange or a binding. You do not write JSON; the plan is saved as a migration file when you are done."
    const val STEPS_TITLE: String = "Steps, applied from top to bottom"
    const val REVIEW_TITLE: String = "The migration this plan saves as"
    const val REVIEW_NOTE: String = "Read only. This is the text of the file that Save as migration writes."
    const val WIZARD_KIND_TITLE: String = "What do you want to add?"
    const val NOT_IN_PLAN_NOTE: String = "Not in this plan and not seen on the broker"
    const val CHECK_LABEL: String = "Check"
    const val BLOCKED_PILL: String = "Blocked"
    const val CHECK_STALE: String = "The plan changed since the check. Check again."
    const val CHECK_AGAIN: String = "Check again"

    /** Under the summary: the check probes the broker as it is now, so it is never worded as a promise (owner review item 1 of M9-06). */
    const val CHECK_CAVEAT: String = "This is how the broker looks now; it is not a promise of what applying will do."
    const val DISMISS_LABEL: String = "Dismiss"
    const val PROBLEM_PILL: String = "Problem"

    const val QUEUE_NAME_MISSING: String = "Give the queue a name"
    const val EXCHANGE_NAME_MISSING: String = "Give the exchange a name"
    const val STREAM_NAME_MISSING: String = "Give the stream a name"
    const val RESPONSE_NAME_MISSING: String = "Give the response stream a name"
    const val NAME_RESERVED: String = "Names that start with \"amq.\" are reserved"
    const val QUEUE_NAME_TOO_LONG: String = "A queue name can be at most 255 bytes long"
    const val RESPONSE_NAME_SAME: String = "The response stream needs a name of its own"
    const val RESPONSE_STREAM_TICK: String = "Also create its response stream (DLN)"
    const val RESPONSE_STREAM_NAME_LABEL: String = "Response stream (DLN) name"

    /**
     * What the planner does, plus the owner's one sentence about the broker (decision O16, basis S-D1 to S-D3 of the
     * spike M9-00b). No second claim about the broker.
     */
    const val RESPONSE_STREAM_NOTE: String =
        "A response stream is an ordinary stream, named after its request stream. Ticking this declares both streams and adds no routing between them. RabbitMQ does not move messages into it by itself: your applications write to it."
    const val RESPONSE_LINK_LABEL: String = "response (DLN)"

    /**
     * The one sentence of the wizard summary and of the review graph note when an MQTT route leads into a response stream (owner decision O13).
     * It says what is not planned and claims no bridge from a stream to MQTT; it says nothing about a correlation id (O16).
     */
    const val SERVICE_NOT_PLANNED: String = "Your own service turns a request into an MQTT message; the planner does not plan that."

    /** The two MQTT ticks of the stream form, each with its sub-line and the label of its topic filter field (owner decision O13). */
    const val ANSWERS_OVER_MQTT_TICK: String = "Answers arrive over MQTT"
    const val ANSWERS_OVER_MQTT_SUBLINE: String = "Adds an MQTT route into the response stream."
    const val ANSWERS_TOPIC_LABEL: String = "Topic filter of the answers"
    const val ANSWERS_TOPIC_PLACEHOLDER: String = "devices/+/answer"
    const val REQUESTS_OVER_MQTT_TICK: String = "Requests are also recorded from an MQTT topic"
    const val REQUESTS_OVER_MQTT_SUBLINE: String = "Adds an MQTT route into the request stream."
    const val REQUESTS_TOPIC_LABEL: String = "Topic filter of the requests"

    /** The second line of a response stream's row; the name goes in only through the echo rule. */
    fun responseStreamOf(request: String): String =
        if (echoableName(request)) "Response stream (DLN) of $request" else "Response stream (DLN)"

    const val BINDING_EXCHANGE_MISSING: String = "Choose the exchange to bind from"
    const val BINDING_DESTINATION_MISSING: String = "Choose what to bind to"

    /** The two warnings of a headers binding with no header (measured, S-E4); owner wording of 2026-11-16. */
    const val HEADERS_NONE_ALL: String = "With no header, this binding matches every message"
    const val HEADERS_NONE_ANY: String = "With no header, this binding matches no message at all"

    /** Working wording of the architect for the four header refusals; the owner may reword them. */
    const val HEADER_NAME_MISSING: String = "Give the header a name"
    const val HEADER_NAME_IS_MATCH_RULE: String = "x-match is the match rule, not a header"
    const val HEADER_LISTED_TWICE: String = "A header can be listed once"
    const val HEADER_WHOLE_DIGITS_ONLY: String = "A whole number has digits only"

    const val ADD_A_STEP_FIRST: String = "Add a step first"
    const val NO_FOLDER: String = "Choose a migrations folder first"
    const val DRAFT_LABEL: String = "Draft, not saved as a migration"
    const val FILE_NOT_WRITTEN: String = "The file could not be written"
    const val FILE_NOT_WRITTEN_DETAIL: String = "The plan is still here as a draft. Nothing was changed."
    const val NO_STEPS_YET: String = "No steps yet"
    const val NOTHING_TO_REVIEW: String = "Nothing to review yet"
    const val DRAFT_CARD_TITLE: String = "Draft plan"
    const val DRAFT_CARD_PILL: String = "Not saved"
    const val DRAFT_NOT_SAVED: String = "The draft could not be saved on this machine; the plan is only in memory"
    const val DRAFT_NOT_MOVED: String = "The draft file could not be moved aside; it was not changed"
    const val DRAFT_FILE_NAME: String = "planner-draft.json"

    const val NOT_OFFERED: String = "The planner is not offered on this connection"
    const val DRAFT_UNREADABLE: String = "The saved draft cannot be read"
    const val READ_ONLY_LEVEL: String =
        "This connection is set to Read-only. You can plan, check and save; applying needs the level Declare and edit."
    const val NOT_CONNECTED: String = "Not connected. You can plan and save; connect to a RabbitMQ 4 broker to check the plan."
    const val NO_FOLDER_BANNER: String = "Choose a migrations folder to save this plan. Until then it stays a draft on this machine."

    fun noConfigurePermission(connection: String?): String =
        "The permission check found that this user cannot change topology on ${connection ?: "this connection"}. " +
            "You can plan, check and save, then apply the migration on a connection that allows it."

    /**
     * [planVhost] comes from a file, so it goes in only through the echo rule. A virtual host is often written with one leading slash
     * ("/orders"), which the rule alone refuses, so that single slash is allowed in front of a name the rule accepts.
     */
    fun vhostMismatch(planVhost: String, connection: String, connectionVhost: String): String {
        val echo = echoableName(planVhost) || planVhost.startsWith("/") && echoableName(planVhost.drop(1))
        val plan = if (echo) "the virtual host $planVhost" else "another virtual host"
        val target = if (echo) "a connection for $planVhost" else "a connection for that virtual host"
        return "This plan is for $plan; $connection is connected to $connectionVhost. It can be saved here and applied on $target."
    }

    /** "Check against uat-eu", or just "Check" while there is no connection. */
    fun checkLabel(connection: String?): String = if (connection == null) CHECK_LABEL else "$CHECK_LABEL against $connection"

    /** [step] and [total] count steps, never requests; the suffix tells the user that nothing is changed. */
    fun checkProgress(step: Int, total: Int, connection: String): String =
        "Checking step $step of $total against $connection. Only reads are sent."

    /** The words of the result table of the spec; the reason a finding carries is drawn on its own line, never in the word. */
    fun resultLabel(finding: StepFinding): String = when (finding) {
        StepFinding.WillBeCreated -> "Will be created"
        StepFinding.AlreadyThere -> "Already there"
        StepFinding.Different -> "Exists with different settings"
        is StepFinding.Missing -> "Needs something that is missing"
        is StepFinding.NotChecked -> "Could not be checked"
        StepFinding.WillBeDeleted -> "Will be deleted"
        StepFinding.AlreadyGone -> "Already gone"
    }

    /**
     * "Checked against uat-eu at 14:02: 5 will be created, 1 already there." Zero counts are left out and "blocked" is last (a blocked step
     * counts under its result too). [time] is formatted by the screen: `core` has no time zone.
     */
    fun checkSummary(connection: String, time: String, results: List<StepCheck>): String {
        val counts = SUMMARY_ORDER.mapNotNull { (label, matches) ->
            results.count { matches(it.finding) }.takeIf { it > 0 }?.let { "$it $label" }
        } + listOfNotNull(results.count { it.blocked != null }.takeIf { it > 0 }?.let { "$it blocked" })
        return "Checked against $connection at $time: ${counts.joinToString(", ")}."
    }

    private val SUMMARY_ORDER: List<Pair<String, (StepFinding) -> Boolean>> = listOf(
        "will be created" to { f -> f == StepFinding.WillBeCreated },
        "already there" to { f -> f == StepFinding.AlreadyThere },
        "exists with different settings" to { f -> f == StepFinding.Different },
        "needs something that is missing" to { f -> f is StepFinding.Missing },
        "could not be checked" to { f -> f is StepFinding.NotChecked },
        // After the declare words, so the sentence of a plan without a delete step reads as in the mockup. Owner review item 3 may move them.
        "will be deleted" to { f -> f == StepFinding.WillBeDeleted },
        "already gone" to { f -> f == StepFinding.AlreadyGone },
    )

    fun draftCardSteps(steps: Int): String = "$steps ${plural(steps, "step")} \u00b7 not a migration yet"

    fun planSaved(version: String): String = "Plan saved as $version. Nothing was sent to the broker. Apply it when you are ready."

    fun dismissQuestion(steps: Int): String =
        "Dismiss this plan? Its $steps ${if (steps == 1) "step is" else "steps are"} discarded. Nothing was sent to the broker."

    fun startNewPlanQuestion(asideFileName: String): String =
        "Move $DRAFT_FILE_NAME to $asideFileName and start a new plan? The old file is kept."

    fun summaryTitle(steps: Int): String = "This adds $steps ${plural(steps, "step")}"

    fun saveBlocked(problems: Int): String = "Fix the $problems ${plural(problems, "problem")} in the plan first"

    fun declaredWithOtherSettings(name: String): String = "The plan already declares ${subject(name, "this name")} with other settings"

    fun declaredTwice(name: String): String = "The plan declares ${subject(name, "this name")} twice with the same settings"

    fun queueAndStreamOfOneName(name: String): String =
        if (echoableName(name)) "The plan declares $name as a queue and as a stream" else "The plan declares a queue and a stream of one name"

    /** [kind] is "queue", "exchange" or "stream"; [place] is the 1-based place of the step that declares it. */
    fun declaredLater(kind: String, name: String, place: Int): String {
        val named = if (echoableName(name)) "The $kind $name" else "The $kind"
        return "$named is declared later, in step $place. Move this step below it."
    }

    /** The legend of the review graph, in the order of the mockup. The dashed and dotted links join it with M9-07 and M9-09. */
    val GRAPH_LEGEND: List<String> = listOf(
        "Thick outline, \"new\": added by this plan",
        "Thin outline: already on the broker",
        "Dotted outline, \"not in this plan\": the broker has no such item and the plan does not declare it",
    )

    const val NEW_MARK: String = "new"
    const val NOT_IN_THIS_PLAN: String = "not in this plan"
    const val GRAPH_NOTE: String = "Read only. Nothing on the graph changes the plan."

    // ---- the policy step (M9-08) ----

    const val POLICY_NAME_MISSING: String = "Give the policy a name"
    const val POLICY_PATTERN_MISSING: String = "Enter a pattern"
    const val POLICY_SETTINGS_MISSING: String = "Add at least one setting"
    const val POLICY_WHOLE_NUMBER: String = "Enter a whole number"
    const val POLICY_DURATION: String = "Enter a number and a unit, for example 7D"
    const val POLICY_SETTING_TWICE: String = "Each setting can be added once"
    const val POLICY_CHOICE: String = "Choose one of the offered values"
    const val POLICY_SETTING_NOT_OFFERED: String = "This setting is not offered for what the policy applies to"
    const val POLICY_SHORT_LIST: String = "Only settings that every kind of queue honours are offered here"
    const val POLICY_PATTERN_UNEVALUABLE: String = "The app cannot tell which names this pattern matches"
    const val POLICY_MATCHES_NOTHING: String = "Matches no name yet"
    const val POLICY_ADD_SETTING: String = "Add setting"

    /** Most names of one source the match line spells out; the rest is a count. */
    const val MATCH_LINE_MAX_NAMES: Int = 10

    /**
     * The match line under the pattern: `matches "orders" in this plan and "orders-archive" on "uat-eu"`. [broker] is empty (and [connection] blank)
     * when the broker was not read, so no claim is made about it. A name that cannot be echoed is counted, never printed.
     */
    fun policyMatchLine(inPlan: List<String>, broker: List<String>, connection: String?): String {
        val parts = listOfNotNull(
            nameList(inPlan)?.let { "$it in this plan" },
            if (connection.isNullOrBlank()) null else nameList(broker)?.let { "$it on \"$connection\"" },
        )
        return if (parts.isEmpty()) POLICY_MATCHES_NOTHING else "matches " + parts.joinToString(" and ")
    }

    /** `"a"`, `"a" and "b"`, `"a", "b" and "c"`; ten names at most, then `and N more`; null for no name at all. */
    private fun nameList(names: List<String>): String? {
        if (names.isEmpty()) return null
        val shown = names.filter { echoableName(it) }.take(MATCH_LINE_MAX_NAMES)
        val more = names.size - shown.size
        val quoted = shown.map { "\"$it\"" }
        return when {
            more > 0 && quoted.isEmpty() -> "$more ${plural(more, "name")}"
            more > 0 -> quoted.joinToString(", ") + " and $more more"
            quoted.size == 1 -> quoted.single()
            else -> quoted.dropLast(1).joinToString(", ") + " and " + quoted.last()
        }
    }

    /** The page message after a change of target removed rows: the new target's [targetLabel] and the labels of the removed rows, in row order. */
    fun settingsRemoved(targetLabel: String, labels: List<String>): String =
        "Removed because \"$targetLabel\" does not offer them: ${labels.joinToString(", ")}."

    fun policiesOverlap(first: String, second: String, on: String): String =
        "The policies ${named(first, "a policy")} and ${named(second, "another policy")} both match ${named(on, "a queue")}. " +
            "RabbitMQ applies only one policy to a queue: put the settings into one policy."

    /** [typeWords] is `quorum queue`, `classic queue` or `stream`; [ignoredLabel] the label (or the echoable broker key) of the first key the type ignores. */
    fun policyNoEffect(on: String, typeWords: String, ignoredLabel: String): String =
        "This policy has no effect on ${named(on, "a queue")}: a $typeWords ignores $ignoredLabel, " +
            "and RabbitMQ then applies none of the policy's settings."

    const val AT_LEAST_ONCE_WITHOUT_REJECT: String =
        "RabbitMQ accepts this but would not keep a message it cannot pass on: at least once only holds together with the setting that refuses publishers when the queue is full."

    fun deadLetterExchangeMissing(exchange: String): String =
        "The exchange ${if (echoableName(exchange)) "$exchange " else ""}does not exist yet. RabbitMQ accepts the policy and then loses the dead letters."

    /** The three decided sentences of the check, for a step that would bind to a queue the broker already applies [policy] to. */
    fun brokerPolicyTakenPlace(policy: String, queue: String): String =
        "The broker applies ${policyWords(policy)} to ${named(queue, "a queue")}. Only one policy applies to a queue: this one would take its place."

    fun brokerPolicyWins(policy: String, queue: String): String =
        "The broker applies ${policyWords(policy)} to ${named(queue, "a queue")}, which has a higher priority. Only one policy applies to a queue: this one would not apply."

    fun brokerPolicySamePriority(policy: String, queue: String): String =
        "The broker applies ${policyWords(policy)} to ${named(queue, "a queue")} at the same priority. Only one of the two will apply, and RabbitMQ does not say which."

    private fun policyWords(policy: String): String = if (echoableName(policy)) "the policy $policy" else "a policy"

    /** The dead-lettering recipe (M9-09). Decided wording: spec O19; labels and safety nets: the brief "Texts". No number of minutes, ever. */
    const val DEAD_LETTER_KIND_DESCRIPTION: String = "Exchange, dead-letter queue, binding and policy"
    const val DEAD_LETTER_TARGET_QUEUE: String = "A dead-letter queue"
    const val DEAD_LETTER_TARGET_STREAM: String = "A stream"
    const val DEAD_LETTER_AT_MOST_ONCE: String = "At most once"
    const val DEAD_LETTER_AT_LEAST_ONCE: String = "At least once"
    const val DEAD_LETTER_DEFAULT_PILL: String = "default"
    const val DEAD_LETTER_DEFAULT_NOTE: String = "If the dead-letter target cannot take a message, RabbitMQ drops it."
    const val DEAD_LETTER_AT_LEAST_ONCE_NOTE: String =
        "RabbitMQ keeps a message it cannot pass on and retries. Retries can take minutes, so do not rely on a quick delivery."
    const val DEAD_LETTER_FULL_QUEUE_NOTE: String =
        "While the queue is full, publishers are refused. A quorum queue holds one message more than its limit."
    const val DEAD_LETTER_REFUSED_TICK_LABEL: String = "Publishers are refused when the queue is full"
    const val DEAD_LETTER_TICK_REQUIRED: String = "Tick \"$DEAD_LETTER_REFUSED_TICK_LABEL\" to use $DEAD_LETTER_AT_LEAST_ONCE"
    const val DEAD_LETTER_AT_LEAST_ONCE_UNAVAILABLE: String = "At least once is only for a queue known to be a quorum queue."
    const val DEAD_LETTER_SOURCE_IS_STREAM: String = "Dead-lettering does not work for a stream. Choose a queue."
    const val DEAD_LETTER_QUEUE_MISSING: String = "Choose or type the queue"
    const val DEAD_LETTER_EDGE_LABEL: String = "dead letters"
    const val DEAD_LETTER_SUMMARY_CAUSES: String =
        "Messages that are rejected, expire, are dropped by the queue's length limit or are returned too often are dead-lettered."
    const val DEAD_LETTER_QUEUE_LABEL: String = "Queue"
    const val DEAD_LETTER_TARGET_HEADING: String = "Dead letters go to"
    const val DEAD_LETTER_EXCHANGE_LABEL: String = "Dead-letter exchange"
    const val DEAD_LETTER_TARGET_QUEUE_LABEL: String = "Dead-letter queue"
    const val DEAD_LETTER_TARGET_STREAM_LABEL: String = "Dead-letter stream"
    const val DEAD_LETTER_STRATEGY_HEADING: String = "How sure"

    fun deadLetterTargetQueueLine(name: String): String = "Quorum queue" + if (echoableName(name)) " $name" else ""

    fun deadLetterTargetStreamLine(name: String): String =
        "Stream" + (if (echoableName(name)) " $name" else "") + ": dead letters are kept and can be browsed."

    /** Each name is judged alone by the echo rule; a refused one is left out of its own place and the sentence stays whole. */
    fun deadLetterOwnArgument(queue: String, exchange: String): String =
        "The queue ${if (echoableName(queue)) "$queue " else ""}names its own dead-letter exchange${if (echoableName(exchange)) " $exchange" else ""}. " +
            "RabbitMQ uses that one and ignores this policy's exchange."

    fun deadLetterQueueUntouched(queue: String): String = "The queue ${if (echoableName(queue)) "$queue " else ""}itself is not changed."

    /** The retention block of the stream form. */
    const val KEEP_FOR_LABEL: String = "Keep messages for"
    const val KEEP_AT_MOST_LABEL: String = "Keep at most (bytes)"
    const val RETENTION_RESPONSE_TICK: String = "Same retention for the response stream"

    private fun named(name: String, fallback: String): String = if (echoableName(name)) name else fallback

    private fun subject(name: String, fallback: String): String = if (echoableName(name)) name else fallback

    private fun plural(n: Int, word: String): String = if (n == 1) word else "${word}s"
}
