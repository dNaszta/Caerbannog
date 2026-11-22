package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrate3x.AnalysisHolder
import dev.caerbannog.core.migrate3x.SecretValue
import dev.caerbannog.core.migrate3x.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

const val FORWARDING_POLL_MILLIS: Long = 2_000

/**
 * State in, events out for the forwarding view; every safeguard is `ForwardingService`'s. [context] is the effective level and the configure
 * permission of the held 4.x connection, read at every call. One [job] at a time (a new event that starts work cancels the old one; the polling is
 * that job). The typed password lives only in [form] as a redacting `SecretValue`; it is dropped when the form is left.
 */
class ForwardingViewModel(
    private val service: ForwardingService,
    private val held: AnalysisHolder,
    private val destination: () -> DestinationConnection?,
    private val profiles: (String) -> ConnectionProfile?,
    private val context: () -> ForwardingContext,
    private val scope: CoroutineScope,
    private val pollMillis: Long = FORWARDING_POLL_MILLIS,
) {
    private val mutable = MutableStateFlow<ForwardingScreenState>(ForwardingScreenState.Closed)
    val state: StateFlow<ForwardingScreenState> = mutable

    private var job: Job? = null
    private var query = ""
    private var picked: QueueRow? = null
    private var form: ForwardingForm? = null
    private var facts: DryRunResult? = null
    private var request: ForwardingRequest? = null
    private var sourceProfile: ConnectionProfile? = null
    private var destinationProfile: ConnectionProfile? = null

    /** Shows the queues of the held live analysis; a file analysis or none shows nothing. */
    fun open() {
        if (held.held.value?.kind != SourceKind.Live) return
        query = ""
        showPick()
    }

    /** Leaving the screen stops the polling, not the shovel (D6), and drops the form. */
    fun close() {
        job?.cancel()
        wipe()
        mutable.value = ForwardingScreenState.Closed
    }

    fun setQuery(query: String) {
        if (mutable.value !is ForwardingScreenState.Pick) return
        this.query = query
        showPick()
    }

    fun pick(row: QueueRow) {
        val current = mutable.value as? ForwardingScreenState.Pick ?: return
        if (current.forwardDisabledReason != null) return
        val dest = destination()?.profile ?: return
        val source = held.held.value?.let { profiles(it.fileLabel) } ?: return
        picked = row; sourceProfile = source; destinationProfile = dest
        form = ForwardingForm(source.host, if (source.tls) DEFAULT_AMQPS_PORT else DEFAULT_AMQP_PORT)
        prepare()
    }

    fun setAmqpHost(host: String) = edit { it.copy(amqpHost = host) }
    fun setAmqpPort(port: Int) = edit { it.copy(amqpPort = port) }
    fun setKeepRunning(on: Boolean) = edit { it.copy(keepRunning = on) }
    fun setTyped(text: String) = edit { it.copy(typed = text) }
    fun setAnotherUser(on: Boolean, user: String, password: String) =
        edit { it.copy(anotherUser = on, user = user, password = if (on && password.isNotEmpty()) SecretValue.of(password) else null) }

    /** Starts the forwarding when the typed name is exact (the service refuses anyway). */
    fun confirm() {
        val current = mutable.value as? ForwardingScreenState.Confirm ?: return
        val typed = current.form.typed
        if (!current.canConfirm) return
        val built = buildRequest(current.form) ?: return
        // The credentials are needed for the PUT only: polling and the later dry run use a request without them.
        val watched = built.copy(source = built.source.copy(credentials = null))
        request = watched
        // Kept for a re-prepare after a stale plan, without the typed name and never with the password.
        form = current.form.copy(password = null, typed = "")
        job?.cancel()
        val queue = current.queue
        mutable.value = ForwardingScreenState.Starting(queue)
        job = scope.launch {
            when (val result = service.start(built, typed)) {
                StartResult.Started -> { show(queue, ForwardingState.Started()); poll(queue, watched) }
                StartResult.NotConfirmed -> prepareNow()
                is StartResult.Blocked -> {
                    // Fresh facts (and the Remove button of a ShovelExists block) come from a new dry run, but a refused
                    // start must never end on Confirm: the plan the service returned is what the user has to see.
                    prepareNow()
                    if (mutable.value !is ForwardingScreenState.Blocked) mutable.value = blockedFrom(result.plan)
                }
                is StartResult.Refused -> mutable.value = ForwardingScreenState.Problem(queue, result.reason)
                is StartResult.ProbeFailed -> mutable.value = ForwardingScreenState.Problem(queue, ForwardingTexts.probeFailed(result.outcome))
                is StartResult.Failed -> mutable.value = ForwardingScreenState.Problem(queue, result.reason)
            }
        }
    }

    /** Back to the list; sends nothing and wipes the form. Not while a shovel is being watched (that is [close]). */
    fun cancel() {
        val current = mutable.value
        val watching = current is ForwardingScreenState.Progress && current.state !is ForwardingState.Finished && current.state !is ForwardingState.Failed
        if (current is ForwardingScreenState.Closed || current is ForwardingScreenState.Pick || watching) return
        job?.cancel()
        wipe()
        showPick()
    }

    /** The user's Remove: below Full control nothing is sent (the button is disabled with its reason). */
    fun remove() {
        val ref = request?.shovel ?: return
        val current = mutable.value
        val offered = (current as? ForwardingScreenState.Blocked)?.remove ?: (current as? ForwardingScreenState.Progress)?.remove ?: return
        if (!offered.enabled) return
        val queue = picked?.name.orEmpty()
        job?.cancel()
        job = scope.launch {
            mutable.value = when (val result = service.remove(ref)) {
                RemoveResult.Removed -> ForwardingScreenState.Problem(queue, ForwardingTexts.SHOVEL_REMOVED)
                RemoveResult.NeedsFullControl -> current
                is RemoveResult.Failed -> ForwardingScreenState.Problem(queue, result.reason)
            }
        }
    }

    // ---- internals ----

    private fun edit(change: (ForwardingForm) -> ForwardingForm) {
        val current = form ?: return
        if (mutable.value !is ForwardingScreenState.Confirm) return
        form = change(current)
        mutable.value = confirmState() ?: return
    }

    private fun wipe() {
        form = null; facts = null; request = null; picked = null
    }

    private fun showPick() {
        val doc = held.held.value?.document
        val rows = doc?.queues.orEmpty().map { QueueRow(it.vhost, it.name) }.filter { it.name.contains(query, ignoreCase = true) }
        mutable.value = ForwardingScreenState.Pick(query, rows, disabledReason())
    }

    private fun disabledReason(): String? {
        val dest = destination()?.profile ?: return ForwardingTexts.CONNECT_FIRST
        return ForwardingPlanner.gate(context()).firstOrNull()?.let { ForwardingTexts.block(it, dest.name, "") }
    }

    private fun removeButton(): RemoveButton {
        val ctx = context()
        val enabled = ctx.level == TopologyControlLevel.FULL_CONTROL && ctx.topologyUnlocked
        return RemoveButton(enabled, if (enabled) null else ForwardingTexts.NEEDS_FULL_CONTROL)
    }

    private fun buildRequest(form: ForwardingForm): ForwardingRequest? {
        val row = picked ?: return null
        return ForwardingRequests.of(sourceProfile ?: return null, row.vhost, row.name, destinationProfile ?: return null, form)
    }

    private fun prepare() {
        job?.cancel()
        mutable.value = ForwardingScreenState.Preparing(picked?.name.orEmpty())
        job = scope.launch { prepareNow() }
    }

    private suspend fun prepareNow() {
        val queue = picked?.name.orEmpty()
        val dest = destinationProfile?.name.orEmpty()
        val built = buildRequest(form ?: return) ?: return
        request = built.copy(source = built.source.copy(credentials = null))
        mutable.value = when (val prepared = service.prepare(built)) {
            is Prepared.Ready -> { facts = prepared.facts; confirmState() ?: return }
            is Prepared.Blocked -> {
                val exists = prepared.plan.blocks.any { it is ForwardingBlock.ShovelExists }
                ForwardingScreenState.Blocked(queue, dest, prepared.plan.blocks, prepared.plan.warnings, prepared.facts, removeButton().takeIf { exists })
            }
            is Prepared.ProbeFailed -> ForwardingScreenState.Problem(queue, ForwardingTexts.probeFailed(prepared.outcome))
        }
    }

    private fun blockedFrom(plan: ForwardingPlan): ForwardingScreenState.Blocked =
        ForwardingScreenState.Blocked(picked?.name.orEmpty(), destinationProfile?.name.orEmpty(), plan.blocks, plan.warnings, facts, remove = null)

    private fun confirmState(): ForwardingScreenState.Confirm? {
        val current = form ?: return null
        val known = facts ?: return null
        val built = buildRequest(current) ?: return null
        val plan = ForwardingPlanner.plan(context(), built, known)
        val confirmation = ForwardingConfirmations.of(built, plan)
        return ForwardingScreenState.Confirm(
            built.source.queue, destinationProfile?.name.orEmpty(), confirmation.lines, plan.warnings, known, current, canConfirm = current.typed == confirmation.requiredText,
        )
    }

    private fun show(queue: String, state: ForwardingState, texts: List<String> = progressTexts(state)) {
        val remove = when (state) {
            ForwardingState.Finished -> null // D4: nothing left to remove
            is ForwardingState.Failed -> removeButton().takeIf { !state.cleanedUp }
            else -> removeButton()
        }
        mutable.value = ForwardingScreenState.Progress(queue, state, texts, remove)
    }

    private fun progressTexts(state: ForwardingState): List<String> {
        val req = request ?: return emptyList()
        val ready = facts?.source?.ready ?: 0
        val plain = if (!req.source.address.tls) listOf(ForwardingTexts.NOT_ENCRYPTED) else emptyList()
        val keep = req.srcDeleteAfter == SrcDeleteAfter.NEVER
        return when (state) {
            is ForwardingState.Started -> listOf(ForwardingTexts.moved(0, ready)) + plain
            is ForwardingState.Running -> listOf(if (keep) ForwardingTexts.movedKeepRunning(state.forwarded) else ForwardingTexts.moved(state.forwarded, ready)) + plain
            ForwardingState.Finished -> listOf(ForwardingTexts.FINISHED)
            is ForwardingState.Failed ->
                listOf(ForwardingTexts.failed(state.reason), if (state.cleanedUp) ForwardingTexts.CLEANED_UP else ForwardingTexts.notCleanedUp(destinationProfile?.name.orEmpty()))
        }
    }

    private suspend fun poll(queue: String, watched: ForwardingRequest) {
        var current: ForwardingState = ForwardingState.Started()
        while (current !is ForwardingState.Finished && current !is ForwardingState.Failed) {
            delay(pollMillis)
            current = service.poll(watched, current)
            if (current is ForwardingState.Finished) {
                // The source is read once more (a dry run, no new gateway method) to count what was published meanwhile.
                val stayed = (service.prepare(watched).let { (it as? Prepared.Ready)?.facts ?: (it as? Prepared.Blocked)?.facts })?.source?.ready
                val text = when {
                    stayed == null -> ForwardingTexts.FINISHED
                    stayed == 0L -> ForwardingTexts.FINISHED_NONE_STAYED
                    else -> ForwardingTexts.finishedStayed(stayed)
                }
                show(queue, current, listOf(text))
            } else {
                show(queue, current)
            }
        }
    }

    private companion object {
        const val DEFAULT_AMQPS_PORT = 5671
        const val DEFAULT_AMQP_PORT = 5672
    }
}
