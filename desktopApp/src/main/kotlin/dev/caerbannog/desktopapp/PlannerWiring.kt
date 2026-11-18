package dev.caerbannog.desktopapp

import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.FolderOpener
import dev.caerbannog.core.migrations.MigrationFile
import dev.caerbannog.core.planner.BrokerPolicyReader
import dev.caerbannog.core.planner.KnownQueue
import dev.caerbannog.core.planner.KnownTopology
import dev.caerbannog.core.planner.KnownTopologyReader
import dev.caerbannog.core.planner.NamingStyle
import dev.caerbannog.core.planner.PlanOpening
import dev.caerbannog.core.planner.PlannerContext
import dev.caerbannog.core.planner.PlannerEntry
import dev.caerbannog.core.planner.PlannerMode
import dev.caerbannog.core.planner.PlannerViewModel
import dev.caerbannog.core.planner.ReviewView
import dev.caerbannog.core.planner.SavedPlan
import dev.caerbannog.core.planner.StepForm
import dev.caerbannog.core.streams.CatalogResult
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamListRequest
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.ui.planner.PlannerEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What the planner is built from. The planner takes the same inputs as the Migrations screen (one connection, one set of folder ports), so
 * the two screens cannot disagree about the folder, the level or the permission. Not connected is a state of the planner (it plans and saves),
 * never a lock and never "Read-only": the level and permission are neutral then, so the banner says "Not connected".
 */
fun plannerContextOf(inputs: MigrationsInputs): PlannerContext {
    val notConnected = inputs.connectionName.isBlank() || inputs.unavailableReason == NOT_CONNECTED
    if (notConnected) {
        // The defaults of PlannerContext are the neutral level and permission, so "Read-only" is never said about no connection.
        return PlannerContext(connectionName = null, vhost = inputs.vhost)
    }
    return PlannerContext(
        connectionName = inputs.connectionName,
        vhost = inputs.vhost,
        controlLevel = inputs.controlLevel,
        hasConfigurePermission = inputs.hasConfigurePermission,
        unavailableReason = inputs.unavailableReason,
    )
}

/**
 * The reader of the broker's names for [inputs], or null: no connection, a connection that cannot run migrations (RabbitMQ 3.x is a read-only
 * migration source, it is sent nothing), or a catalog that is missing. With null the planner sends no request of any kind.
 */
fun knownTopologyReaderOf(inputs: MigrationsInputs): KnownTopologyReader? {
    if (inputs.connectionName.isBlank() || inputs.unavailableReason != null) return null
    val topology = inputs.topology ?: return null
    val streams = inputs.streams ?: return null
    return CatalogKnownTopologyReader(topology, streams)
}

/**
 * [KnownTopologyReader] over the catalogs the Topology and Streams screens already use. Makes list calls only: the exchanges, every page of
 * the queues and every page of the streams. It has no path under one queue's name and never asks for bindings (the broker's own bindings are
 * not drawn). Any failed call, also of a later page, answers null: half a list would call existing things "not in this plan".
 */
class CatalogKnownTopologyReader(
    private val topology: TopologyCatalog,
    private val streams: StreamCatalog,
) : KnownTopologyReader {
    override suspend fun read(vhost: String): KnownTopology? {
        val exchanges = (topology.exchanges(vhost) as? CatalogResult.Success)?.value ?: return null
        val queues = allQueues(vhost) ?: return null
        val streamNames = allStreams(vhost) ?: return null
        val ownExchanges = ownDeadLetterExchanges(vhost)
        return KnownTopology(
            // The default exchange is not a name a plan can bind to.
            exchanges = exchanges.filterNot { it.isDefault }.associate { it.name to it.type },
            queues = queues.keys,
            streams = streamNames,
            // A broker stream has no policy name here (a StreamSummary carries none): no check note for a stream that already exists.
            queueDetails = queues.mapValues { (name, queue) -> queue.copy(ownDeadLetterExchange = ownExchanges[name]) } +
                streamNames.associateWith { KnownQueue("stream", null) },
        )
    }

    /**
     * The `x-dead-letter-exchange` argument of each queue, as text (M9-09), from the declarations the export already reads (one GET). Only that
     * argument is read, never `effective_policy_definition`. A queue whose typed arguments are incomplete makes no claim (the lost argument may be
     * this one), and a failed read answers nothing for every queue: the names and policies of [read] must not depend on one extra call.
     */
    private suspend fun ownDeadLetterExchanges(vhost: String): Map<String, String> {
        val declarations = (topology.queueDeclarations(vhost) as? CatalogResult.Success)?.value ?: return emptyMap()
        return declarations.filter { it.typedArgumentsComplete }.mapNotNull { declaration ->
            (declaration.arguments["x-dead-letter-exchange"] as? ArgumentValue.Text)?.let { declaration.name to it.value }
        }.toMap()
    }

    private suspend fun allQueues(vhost: String): Map<String, KnownQueue>? {
        val queues = linkedMapOf<String, KnownQueue>()
        var page = 1
        do {
            val result = (topology.queues(vhost, page) as? CatalogResult.Success)?.value ?: return null
            result.rows.forEach { queues[it.name] = KnownQueue(it.type, it.policy) }
            page++
        } while (page <= result.pageCount)
        return queues
    }

    private suspend fun allStreams(vhost: String): Set<String>? {
        val names = mutableSetOf<String>()
        var page = 1
        do {
            val result = (streams.listStreams(StreamListRequest(vhost = vhost, page = page, pageSize = STREAM_PAGE_SIZE)) as? CatalogResult.Success)?.value
                ?: return null
            names += result.rows.map { it.name }
            page++
        } while (page <= result.pageCount)
        return names
    }

    private companion object {
        /** The broker's maximum page size, as for the queues. */
        const val STREAM_PAGE_SIZE = 500
    }
}

/**
 * The scope of a planner view model: `Dispatchers.IO` limited to one thread, with a [SupervisorJob]. The draft store does blocking file I/O
 * under a lock, which must never run on the UI dispatcher; one thread keeps the calls in order, and a failing call does not end the scope.
 */
fun newPlannerScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/**
 * The planner view model for [inputs]: the draft store of the app and the same folder ports as the Migrations screen. In [PlannerMode.EditFile] it
 * works on a pending file of that folder and never uses the draft store.
 */
fun AppComposition.createPlannerViewModel(
    inputs: MigrationsInputs,
    scope: CoroutineScope = newPlannerScope(),
    mode: PlannerMode = PlannerMode.NewPlan,
): PlannerViewModel =
    PlannerViewModel(
        draftStore = planDraftStore,
        folderPorts = migrationFolderPorts,
        settings = appSettings,
        context = plannerContextOf(inputs),
        scope = scope,
        reader = knownTopologyReaderOf(inputs),
        declareCheck = inputs.declareCheck,
        policies = inputs.policyReader,
        // The summary says when the check ran; the view model's own default clock is for tests and always says 0.
        clock = RefreshClock { System.currentTimeMillis() },
        mode = mode,
    )

/**
 * "Copy into a new plan": the steps of [file] (applied elsewhere, so not edited) become the draft of a new plan, written through the real draft store, and
 * the planner opens on it. The store refuses to write over a draft it cannot read: the answer is then false, nothing was written and the planner stays
 * closed. A file with a broker-level action is never copied (a `declareUser` would put its hash in the draft); the screen does not offer it either.
 */
suspend fun AppComposition.copyIntoNewPlan(file: MigrationFile, vhost: String): Boolean {
    if (file.up.any { it.isBrokerLevel }) return false
    if (!planDraftStore.save(PlanOpening.planOf(file, vhost))) return false
    navigation.openPlanner()
    return true
}

/**
 * What the planner window keys its view model on: the context AND the check port. A reconnect to the same profile has the same context
 * but a new port, and a view model keyed on the context alone would keep the port of the closed connection.
 */
data class PlannerKey(val context: PlannerContext, val declareCheck: DeclareCheck?, val policyReader: BrokerPolicyReader? = null)

fun plannerKeyOf(inputs: MigrationsInputs): PlannerKey = PlannerKey(plannerContextOf(inputs), inputs.declareCheck, inputs.policyReader)

/**
 * Follows [viewModel] for the navigation: a saved plan closes the planner and opens Migrations with that version waiting; a confirmed
 * Dismiss closes the planner. Each is reported once, however often the state is published.
 */
fun AppComposition.followPlanner(viewModel: PlannerViewModel, scope: CoroutineScope): Job = scope.launch {
    var reported: SavedPlan? = null
    var closed = false
    viewModel.state.collect { state ->
        val saved = state.saved
        if (saved != null && saved != reported) {
            reported = saved
            navigation.planSaved(saved)
        }
        if (state.dismissed && !closed) {
            closed = true
            navigation.closePlanner()
        }
    }
}

/** What the Migrations screen offers as its way into the planner, from the draft file (blocking file read: call it off the UI thread). */
suspend fun AppComposition.plannerEntry(): PlannerEntry = PlannerEntry.of(planDraftStore.load())

/** Maps the planner screen's events onto the [PlannerViewModel] (the screen knows no view model); "Show folder" goes through [folderOpener]. */
class PlannerEventsAdapter(
    private val viewModel: PlannerViewModel,
    private val folderOpener: FolderOpener = DesktopFolderOpener(),
) : PlannerEvents {
    override fun openWizard() = viewModel.openWizard()
    override fun chooseKind(kind: String) = viewModel.chooseKind(kind)
    override fun updateForm(form: StepForm) = viewModel.updateForm(form)
    override fun next() = viewModel.next()
    override fun back() = viewModel.back()
    override fun cancelWizard() = viewModel.cancelWizard()
    override fun addToPlan() = viewModel.addToPlan()
    override fun selectStep(id: Int) = viewModel.selectStep(id)
    override fun editStep(id: Int) = viewModel.editStep(id)
    override fun removeStep(id: Int) = viewModel.removeStep(id)
    override fun moveStepUp(id: Int) = viewModel.moveStepUp(id)
    override fun moveStepDown(id: Int) = viewModel.moveStepDown(id)
    override fun setDescription(text: String) = viewModel.setDescription(text)
    override fun setVirtualHost(vhost: String) = viewModel.setVirtualHost(vhost)
    override fun setNamingStyle(style: NamingStyle) = viewModel.setNamingStyle(style)
    override fun selectReviewView(view: ReviewView) = viewModel.selectReviewView(view)
    override fun chooseFolder() = viewModel.chooseFolder()
    override fun saveAsMigration() = viewModel.saveAsMigration()
    override fun dismiss() = viewModel.dismiss()
    override fun startNewPlan() = viewModel.startNewPlan()
    override fun confirm() = viewModel.confirm()
    override fun cancelQuestion() = viewModel.cancelQuestion()
    override fun retryDraft() = viewModel.retryDraft()
    override fun flush() = viewModel.flush()
    override fun check() = viewModel.check()
    override fun showFolder(path: String) = folderOpener.show(path)
}
