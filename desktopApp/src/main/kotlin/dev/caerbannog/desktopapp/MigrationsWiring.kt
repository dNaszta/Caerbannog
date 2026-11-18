package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.ConnectionsState
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.migrations.ActionResult
import dev.caerbannog.core.migrations.DeclareCheck
import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationRunner
import dev.caerbannog.core.migrations.MigrationsScreenState
import dev.caerbannog.core.migrations.MigrationsViewModel
import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.mqtt.UnavailableMqttMessagePublisher
import dev.caerbannog.core.planner.BrokerPolicyReader
import dev.caerbannog.core.planner.TestMessageOpening
import dev.caerbannog.ui.mqtt.PublishMqttEvents
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.topology.TopologyCatalog
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * What one Migrations screen is built from. The window rebuilds the view model when this value changes, so it is a value: equal inputs are
 * equal. The executor and the catalog are compared by identity (a new connection brings new ones).
 */
data class MigrationsInputs(
    val connectionName: String,
    val vhost: String,
    val hasConfigurePermission: Boolean,
    val controlLevel: TopologyControlLevel,
    /** The tags of the connection's user from the permission check (M8-12a); an access file needs "administrator". */
    val userTags: Set<String> = emptySet(),
    val executor: TopologyMigrationExecutor?,
    /** Why there is no [executor]: never blank when there is none; null when there is one. */
    val unavailableReason: String?,
    val topology: TopologyCatalog?,
    /** The stream catalog of the same connection as [topology]; the planner reads stream names from it. */
    val streams: StreamCatalog? = null,
    /** The read-only check port of the same connection (M9-05), already behind the closed-connection guard; null when there is none or the connection cannot run migrations. */
    val declareCheck: DeclareCheck? = null,
    /** M9-08: the one-policy read for the notes of the check, same rules as [declareCheck]. */
    val policyReader: BrokerPolicyReader? = null,
    /** M9-10b: the held source has an MQTT port (the port part of [mqttPublishAccess]). */
    val hasMqtt: Boolean = false,
    /** M9-10b: the finished permission check says test publishing is enabled; an unfinished check or no connection is false (as `canWrite` in Main.kt). */
    val publishingEnabled: Boolean = false,
)

internal const val NOT_CONNECTED = "Connect to a broker first"
private const val NO_EXECUTOR = "This connection cannot run migrations"

/**
 * The inputs from the connection state: the profile's own level (read-only until proven otherwise), the connection's virtual host, the
 * permission check's answer, the connection's executor and the reason when it has none.
 */
fun AppComposition.migrationsInputs(connections: ConnectionsState): MigrationsInputs {
    val current = connections.currentConnection
    val source = active.source
    val profile = connections.profiles.firstOrNull { it.name == current?.name }
    val permission = (current?.status as? PermissionCheckStatus.Done)?.result
    val executor = source?.migrationExecutor
    return MigrationsInputs(
        connectionName = current?.name.orEmpty(),
        vhost = source?.vhost ?: "/",
        hasConfigurePermission = permission?.topologyUnlocked ?: false,
        controlLevel = profile?.topologyControlLevel ?: TopologyControlLevel.READ_ONLY,
        userTags = permission?.userTags.orEmpty(),
        executor = executor,
        unavailableReason = if (executor != null) null else when {
            source == null -> NOT_CONNECTED
            else -> source.migrationUnavailableReason?.takeIf { it.isNotBlank() } ?: NO_EXECUTOR
        },
        topology = source?.topology,
        streams = source?.catalog,
        declareCheck = source?.declareCheck,
        policyReader = source?.policyReader,
        hasMqtt = source?.hasMqtt == true,
        publishingEnabled = (current?.status as? PermissionCheckStatus.Done)?.result?.publishingEnabled ?: false,
    )
}

/** The screen state with the two inputs of "Send a test message"; they come from the connection, not from the view model's own state. */
fun MigrationsScreenState.withTestMessageGates(inputs: MigrationsInputs): MigrationsScreenState =
    copy(hasMqtt = inputs.hasMqtt, publishingEnabled = inputs.publishingEnabled)

/** A UUID per test message. Lives here so that `core` stays free of `java.*`. */
object UuidCorrelationIds : CorrelationIdSource {
    override fun next(): String = UUID.randomUUID().toString()
}

/** One open Publish MQTT dialog of a test message: a fresh view model per opening, so no earlier topic, header or feedback leaks into it. */
class TestMessageSession(val vm: PublishMqttViewModel, val events: PublishMqttEvents)

/**
 * Builds the session for [opening]. Only the existing publisher publishes, and only when the user presses Publish; opening sends nothing.
 * [onClose] runs when the dialog is cancelled.
 */
fun AppComposition.openTestMessage(opening: TestMessageOpening, connectionName: String, scope: CoroutineScope, onClose: () -> Unit): TestMessageSession {
    val vm = PublishMqttViewModel(
        publisher = active.source?.mqttPublisher ?: UnavailableMqttMessagePublisher,
        connectionName = connectionName,
        scope = scope,
        initialTopic = opening.topic,
        initialHeaders = opening.headers,
        headerNotice = opening.notice,
    )
    return TestMessageSession(vm, PublishMqttEventsAdapter(vm, onClose = onClose))
}

/** The Migrations view model for [inputs]: the real runner over [migrationHistory] and the connection's executor (a refusing one when there is none). */
fun AppComposition.createMigrationsViewModel(inputs: MigrationsInputs, scope: CoroutineScope): MigrationsViewModel =
    MigrationsViewModel(
        fileRepository = null,
        runner = MigrationRunner(migrationHistory, inputs.executor ?: RefusingExecutor),
        topologyCatalog = inputs.topology,
        profileName = inputs.connectionName,
        vhost = inputs.vhost,
        hasConfigurePermission = inputs.hasConfigurePermission,
        controlLevel = inputs.controlLevel,
        userTags = inputs.userTags,
        folderPorts = migrationFolderPorts,
        migrationUnavailableReason = inputs.unavailableReason,
        scope = scope,
        // The applied history is per profile: a file applied on ANY saved profile of this machine is not edited (M9-12), read when the screen asks.
        savedProfileNames = { viewModel.state.value.profiles.map { it.name } },
    )

/** Stands in when there is no executor; the view model refuses to apply first, so this is the second lock, never the only one. */
private object RefusingExecutor : TopologyMigrationExecutor {
    override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Failed(NO_EXECUTOR)
}
