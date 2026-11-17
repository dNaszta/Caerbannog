package dev.caerbannog.core.planner

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationAction

/**
 * One row of the policy form: a catalog [key] and its value as typed text (as [HeaderMatch] does), so a number is typed key by key.
 * [typedValue] is what the file holds: a number for a whole-number key, text for everything else (`max-age` is never a number: S-F1c).
 */
data class PolicySetting(val key: String, val value: String) {
    /** The typed value, or null when the text is not a value of the key's kind (or the key is not in the catalog). */
    fun typedValue(): ArgumentValue? = when (val kind = PolicyKeyCatalog.entry(key)?.kind) {
        null -> null
        is PolicyValueKind.WholeNumber -> asciiWhole(value)?.takeIf { it >= kind.min }?.let(ArgumentValue::Whole)
        PolicyValueKind.Duration -> ArgumentValue.Text(value).takeIf { isDuration(value) }
        PolicyValueKind.Text -> ArgumentValue.Text(value)
        is PolicyValueKind.Choice -> ArgumentValue.Text(value).takeIf { kind.values.any { it.wire == value } }
    }

    /** The fixed message for a value [typedValue] refuses; null while the value is good. */
    fun problem(): String? = when {
        typedValue() != null -> null
        else -> when (PolicyKeyCatalog.entry(key)?.kind) {
            is PolicyValueKind.WholeNumber -> PlannerTexts.POLICY_WHOLE_NUMBER
            PolicyValueKind.Duration -> PlannerTexts.POLICY_DURATION
            is PolicyValueKind.Choice -> PlannerTexts.POLICY_CHOICE
            PolicyValueKind.Text, null -> PlannerTexts.POLICY_SETTING_NOT_OFFERED
        }
    }

    companion object {
        /** ASCII digits only (no sign, no space, no other script) that fit a `Long`; null otherwise. */
        internal fun asciiWhole(text: String): Long? =
            if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toLongOrNull() else null

        /** Digits, then exactly one unit of [MaxAgeUnits]. */
        internal fun isDuration(text: String): Boolean =
            text.length >= 2 && text.last() in MaxAgeUnits.measured && text.dropLast(1).all { it in '0'..'9' }
    }
}

/**
 * The Policy step (M9-08): a name, a pattern, what it applies to, a priority and settings from [PolicyKeyCatalog]. It builds one `DeclarePolicy`;
 * an operator policy is never built. The pattern is only checked for being empty: what it matches is advice ([hints]), never a block (O11).
 */
data class PolicyForm(
    val name: String,
    val pattern: String,
    val target: PolicyTarget = PolicyTarget.ALL,
    val priority: String = "0",
    val settings: List<PolicySetting> = emptyList(),
) : StepForm {
    /** The priority as a number: ASCII digits with an optional leading `-`, within `Int`; null otherwise. */
    fun priorityValue(): Int? {
        val digits = priority.removePrefix("-")
        return if (digits.isNotEmpty() && digits.all { it in '0'..'9' }) priority.toIntOrNull() else null
    }

    /** The same form for another target; a row whose key the new target does not offer is removed, not kept hidden. */
    fun withTarget(target: PolicyTarget): PolicyForm {
        val offered = PolicyKeyCatalog.offeredFor(target).map { it.key }.toSet()
        return copy(target = target, settings = settings.filter { it.key in offered })
    }

    /** The rows [withTarget] would remove, in row order (the page says which). */
    fun removedBy(target: PolicyTarget): List<PolicySetting> {
        val offered = PolicyKeyCatalog.offeredFor(target).map { it.key }.toSet()
        return settings.filter { it.key !in offered }
    }

    /** The keys the dropdown of row [row] offers: those of the target that no other row uses (the row's own key stays offered). */
    fun offeredKeys(row: Int): List<PolicyKeyEntry> {
        val others = settings.filterIndexed { i, _ -> i != row }.map { it.key }.toSet()
        return PolicyKeyCatalog.offeredFor(target).filter { it.key !in others }
    }

    /** A new row for the first offered key no row uses; the form itself when every offered key is used. A choice starts at its first value. */
    fun addSetting(): PolicyForm {
        val used = settings.map { it.key }.toSet()
        val next = PolicyKeyCatalog.offeredFor(target).firstOrNull { it.key !in used } ?: return this
        return copy(settings = settings + PolicySetting(next.key, startValue(next)))
    }

    /** Row [row] takes another key; its value restarts, because the old text may not fit the new kind. Not a key this row may take: unchanged. */
    fun withSettingKey(row: Int, key: String): PolicyForm {
        val entry = offeredKeys(row).firstOrNull { it.key == key } ?: return this
        if (row !in settings.indices || settings[row].key == key) return this
        return copy(settings = settings.mapIndexed { i, s -> if (i == row) PolicySetting(key, startValue(entry)) else s })
    }

    fun withSettingValue(row: Int, value: String): PolicyForm =
        copy(settings = settings.mapIndexed { i, s -> if (i == row) s.copy(value = value) else s })

    fun removeSetting(row: Int): PolicyForm = copy(settings = settings.filterIndexed { i, _ -> i != row })

    private fun startValue(entry: PolicyKeyEntry): String = (entry.kind as? PolicyValueKind.Choice)?.values?.first()?.wire.orEmpty()

    /** One message per field, in this order: name, pattern, settings, each row, priority. */
    override fun problems(ctx: FormContext): List<FieldProblem> = buildList {
        // A policy named `amq.x` is accepted by the broker (201, S-H): the reserved prefix is not checked here. The round trip uses stand-in
        // pattern and settings so it judges the name alone.
        addAll(FormChecks.nameProblem(name, PlannerTexts.POLICY_NAME_MISSING, "name", reservedPrefix = false) { nameProbe(ctx) })
        if (pattern.isEmpty()) add(FieldProblem("pattern", PlannerTexts.POLICY_PATTERN_MISSING))
        if (settings.isEmpty()) add(FieldProblem("settings", PlannerTexts.POLICY_SETTINGS_MISSING))
        val seen = mutableSetOf<String>()
        val offered = PolicyKeyCatalog.offeredFor(target).map { it.key }.toSet()
        settings.forEachIndexed { i, setting ->
            val message = when {
                !seen.add(setting.key) -> PlannerTexts.POLICY_SETTING_TWICE
                setting.key !in offered -> PlannerTexts.POLICY_SETTING_NOT_OFFERED
                else -> setting.problem()
            }
            if (message != null) add(FieldProblem("setting:$i", message))
        }
        if (priorityValue() == null) add(FieldProblem("priority", PlannerTexts.POLICY_WHOLE_NUMBER))
    }

    override fun actions(ctx: FormContext): List<MigrationAction> = if (problems(ctx).isEmpty()) listOf(build(ctx)) else emptyList()

    override fun hints(ctx: FormContext): List<FieldHint> = buildList {
        if (pattern.isNotEmpty()) add(FieldHint("pattern", PolicyMatchLine.of(pattern, ctx)))
        if (target == PolicyTarget.QUEUES || target == PolicyTarget.ALL) add(FieldHint("target", PlannerTexts.POLICY_SHORT_LIST))
    }

    private fun build(ctx: FormContext) = MigrationAction.DeclarePolicy(
        name = name,
        pattern = pattern,
        definition = settings.mapNotNull { s -> s.typedValue()?.let { s.key to it } }.toMap(),
        vhost = ctx.plan.vhost,
        applyTo = target.wire,
        priority = priorityValue() ?: 0,
    )

    private fun nameProbe(ctx: FormContext) = MigrationAction.DeclarePolicy(
        name = name, pattern = "x", definition = mapOf("max-length-bytes" to ArgumentValue.Whole(1)), vhost = ctx.plan.vhost,
    )
}

/** The sentence under the pattern: which names of this plan and of the broker it matches (the pattern alone, not filtered by what the policy applies to). */
internal object PolicyMatchLine {
    fun of(pattern: String, ctx: FormContext): String {
        val inPlan = ctx.plan.steps.mapNotNull { declaredName(it.action) }.distinct()
        val brokerRead = !ctx.connectionName.isNullOrBlank()
        val onBroker = if (brokerRead) ctx.known.queues.toList() + ctx.known.streams.toList() else emptyList()
        val planMatches = when (val r = PatternMatches.of(pattern, inPlan)) {
            is PatternMatchResult.Matches -> r.names
            PatternMatchResult.Unevaluable -> return PlannerTexts.POLICY_PATTERN_UNEVALUABLE
        }
        val brokerMatches = (PatternMatches.of(pattern, onBroker) as? PatternMatchResult.Matches)?.names.orEmpty()
        return PlannerTexts.policyMatchLine(planMatches, brokerMatches, ctx.connectionName)
    }

    private fun declaredName(action: MigrationAction): String? = when (action) {
        is MigrationAction.DeclareQueue -> action.name
        is MigrationAction.DeclareStream -> action.name
        else -> null
    }
}
