package dev.caerbannog.core.planner

import dev.caerbannog.core.connections.fakes.FakeAppSettings
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolder
import dev.caerbannog.core.migrations.fakes.FakeMigrationFolders
import dev.caerbannog.core.planner.fakes.FakePlanDraftStore
import dev.caerbannog.core.streams.RefreshClock
import dev.caerbannog.core.topology.BindingDestinationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M9-02 test rig: a [PlannerViewModel] over fakes only (draft store, migrations folder, settings), on `Dispatchers.Unconfined` so a call has
 * finished when it returns (the thread test has its own rig with a real single thread). A "restart" is [restart]: a new view model over the
 * same store, folder and settings, which is all a restart keeps.
 *
 * The folder is `/work/migrations` with the migration `0004`, so the next version is `0005`; pass `folderPath = null` for "no folder chosen".
 */
internal class PlannerRig(
    folderPath: String? = FOLDER,
    latest: String? = "0004",
    var context: PlannerContext = PlannerContext(),
    val store: FakePlanDraftStore = FakePlanDraftStore(),
    val settings: FakeAppSettings = FakeAppSettings(),
    /** M9-04: the one read of the broker's names; null is no connection (or a connection with no planner). */
    val reader: KnownTopologyReader? = null,
    /** M9-06: the connection's read-only check port; null is a connection without one. A reconnect to the same profile is [restart] with another port. */
    var declareCheck: DeclareCheck? = null,
    /** M9-06: the time of a check (the summary's "at 14:02"); the virtual clock of the view model. */
    var nowMillis: Long = 1_700_000_000_000L,
) {
    val folders = FakeMigrationFolders(folderPath)
    val folder: FakeMigrationFolder = folders.folder(folderPath ?: FOLDER).allowExport().also { f -> latest?.let { f.addMigration(it) } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    var vm: PlannerViewModel = build()
    val state: PlannerState get() = vm.state.value

    private fun build() = PlannerViewModel(
        store, folders.ports, settings, context, scope, descriptionDebounceMillis = 0, reader = reader,
        declareCheck = declareCheck, clock = RefreshClock { nowMillis },
    )

    /** The app is closed and opened again, or the window rebuilt the view model for a changed [context] (or, for the same profile, a new check port). */
    fun restart(newContext: PlannerContext = context, newDeclareCheck: DeclareCheck? = declareCheck): PlannerViewModel {
        context = newContext
        declareCheck = newDeclareCheck
        vm = build()
        return vm
    }

    fun close() = scope.cancel()

    /** The whole wizard in one go: kind, form, Next, Add to plan. */
    fun add(form: StepForm) {
        vm.openWizard()
        vm.chooseKind(kindOf(form))
        vm.updateForm(form)
        vm.next()
        vm.addToPlan()
    }

    fun queue(name: String, type: String = "quorum") = add(QueueForm(name = name, type = type))
    fun exchange(name: String, type: String = "direct") = add(ExchangeForm(name = name, type = type))
    fun binding(exchange: String, queue: String, key: String = "") =
        add(BindingForm(exchange = exchange, destination = queue, destinationKind = BindingDestinationType.QUEUE, routingKey = key))

    /** Three steps: a queue, an exchange and a binding of the two. */
    fun threeSteps() {
        queue("orders")
        exchange("events")
        binding("events", "orders", "order.created")
    }

    fun titles(): List<String> = state.plan.steps.map { it.title }
    fun ids(): List<Int> = state.plan.steps.map { it.id }

    companion object {
        const val FOLDER = "/work/migrations"

        fun kindOf(form: StepForm): String = when (form) {
            is QueueForm -> "Queue"
            is ExchangeForm -> "Exchange"
            is BindingForm -> "Binding"
        }
    }
}
