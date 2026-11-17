package dev.caerbannog.acceptance.mqtt

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.caerbannog.acceptance.migrations.ScreenDriver
import dev.caerbannog.core.connections.ChipIcon
import dev.caerbannog.core.connections.ChipState
import dev.caerbannog.core.connections.ConnectionChipState
import dev.caerbannog.core.connections.ConnectionControlEvents
import dev.caerbannog.core.connections.ConnectionControlState
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionCheckStatus
import dev.caerbannog.core.connections.RefreshSectionState
import dev.caerbannog.core.connections.fakes.FakeMqttMessagePublisher
import dev.caerbannog.core.mqtt.MqttQos
import dev.caerbannog.core.mqtt.PublishMqttViewModel
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.publish.MqttPublishAccess
import dev.caerbannog.core.streams.RefreshInterval
import dev.caerbannog.core.streams.StreamClientsScreen
import dev.caerbannog.core.streams.StreamClientsState
import dev.caerbannog.core.topology.TopologyScreen as TopologyScreenKind
import dev.caerbannog.core.topology.TopologyState
import dev.caerbannog.core.topology.TopologyTab
import dev.caerbannog.ui.mqtt.PublishMqttEvents
import dev.caerbannog.ui.streams.StreamClientsEvents
import dev.caerbannog.ui.streams.StreamScreen
import dev.caerbannog.ui.topology.TopologyScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * M9-10e: the real stream and Topology screens, drawn through the Compose UI test API, with the four controls that open the Publish MQTT dialog.
 * What the screens are given is decided by the one core rule [MqttPublishAccess] from the facts of the scenario (the port, the permission check,
 * the virtual host), the way `StreamWiring.kt` of the desktop app does it (`StreamWiringTest` covers that file). Cucumber has no DI here, so the
 * facts live in one object, reset before every scenario.
 */
internal object MqttGateWorld {
    var mqttPort: Int = 1883
    var vhost: String = "/"

    /** What the connection says about the permission check; null is no check at all. */
    var permissionStatus: PermissionCheckStatus? = PermissionCheckStatus.Checking

    val publisher = FakeMqttMessagePublisher()

    /** The controls the last "disabled" or "enabled" assertion looked at; "it says" and "each of them says" read them. */
    val subjects = mutableListOf<String>()

    /** Same expression as `canWrite` in Main.kt: only a finished check that says publishing is enabled. */
    private val publishingEnabled: Boolean
        get() = (permissionStatus as? PermissionCheckStatus.Done)?.result?.publishingEnabled ?: false

    val access: MqttPublishAccess get() = MqttPublishAccess.of(hasMqtt = mqttPort > 0, publishingEnabled = publishingEnabled, vhost = vhost)

    fun grant(enabled: Boolean) {
        permissionStatus = PermissionCheckStatus.Done(PermissionCheckResult.Checked(topologyUnlocked = false, publishingEnabled = enabled))
    }

    fun reset() {
        mqttPort = 1883
        vhost = "/"
        permissionStatus = PermissionCheckStatus.Checking
        publisher.clear()
        subjects.clear()
    }
}

private val connectedChip = ConnectionControlState(
    chip = ConnectionChipState(ChipState.Connected, ChipIcon.Link, "prod-eu", null, null, null, "Connection prod-eu, Connected. Open connection menu", false),
    rows = emptyList(),
    emptyText = null,
    refresh = RefreshSectionState(RefreshInterval.Every5, enabled = true, note = "Not loaded yet"),
)

private val noControl = object : ConnectionControlEvents {
    override fun connect(name: String) = Unit
    override fun disconnect(name: String) = Unit
    override fun retry(name: String) = Unit
    override fun setRefreshInterval(interval: RefreshInterval) = Unit
    override fun refreshNow() = Unit
    override fun openConnections() = Unit
}

private object NoStreamEvents : StreamClientsEvents {
    override fun show(stream: String) = Unit
    override fun hide() = Unit
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun back() = Unit
}

private class DialogEvents(private val vm: PublishMqttViewModel) : PublishMqttEvents {
    override fun setTopic(topic: String) = vm.setTopic(topic)
    override fun setQos(qos: MqttQos) = vm.setQos(qos)
    override fun setRetain(retain: Boolean) = vm.setRetain(retain)
    override fun setPayload(payload: String) = vm.setPayload(payload)
    override fun addHeader() = vm.addHeader()
    override fun setHeader(index: Int, key: String, value: String) = vm.setHeader(index, key, value)
    override fun removeHeader(index: Int) = vm.removeHeader(index)
    override fun publish() = vm.publish()
    override fun cancel() = vm.cancel()
    override fun clearFeedback() = vm.clearFeedback()
}

/** The tags and the way to read a control, shared by both drivers. */
@OptIn(ExperimentalTestApi::class)
internal abstract class MqttGateDriver(content: @androidx.compose.runtime.Composable () -> Unit) : ScreenDriver(content) {
    /** True when something is drawn with [tag] (the merged and the unmerged tree alike). */
    fun isThere(tag: String): Boolean = exec { waitForIdle(); onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** The control is drawn, not disabled, and can be clicked. */
    fun isEnabledAndClickable(tag: String): Boolean = exec {
        waitForIdle()
        val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "nothing is drawn with the test tag $tag" }
        val config = nodes.first().config
        config.getOrNull(SemanticsProperties.Disabled) == null && config.contains(SemanticsActions.OnClick)
    }

    /** The control is drawn and marked disabled (`assertIsNotEnabled` reads the same property). */
    fun isDisabled(tag: String): Boolean = exec {
        waitForIdle()
        val nodes = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        check(nodes.isNotEmpty()) { "nothing is drawn with the test tag $tag" }
        nodes.first().config.getOrNull(SemanticsProperties.Disabled) != null
    }

    fun contentDescription(tag: String): String? = exec {
        waitForIdle()
        onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
    }

    /** A user's press, also on a disabled control. */
    fun press(tag: String) = exec { waitForIdle(); onNodeWithTag(tag).performClick(); waitForIdle() }
}

@OptIn(ExperimentalTestApi::class)
internal class MqttGateStreamDriver(stream: String, route: StreamInboundRoute) : MqttGateDriver({
    val scope = androidx.compose.runtime.remember { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { scope.cancel() } }
    var showDialog by androidx.compose.runtime.remember { mutableStateOf(false) }
    val vm = androidx.compose.runtime.remember { PublishMqttViewModel(MqttGateWorld.publisher, "prod-eu", scope) }
    val dialogState by vm.state.collectAsState()
    val (open, reason) = when (val access = MqttGateWorld.access) {
        MqttPublishAccess.NoMqttPort -> null to null
        is MqttPublishAccess.NoWritePermission -> null to access.reason
        MqttPublishAccess.Allowed -> ({ topic: String? -> topic?.let(vm::setTopic); showDialog = true }) to null
    }
    run {
        StreamScreen(
            stream = stream,
            state = StreamClientsState(screen = StreamClientsScreen.Content, stream = stream),
            nowMillis = 10_000L,
            events = NoStreamEvents,
            control = connectedChip,
            controlEvents = noControl,
            inboundRoutes = listOf(route),
            showPublishMqttDialog = showDialog,
            publishMqttState = dialogState,
            publishMqttEvents = DialogEvents(vm),
            onOpenPublishMqttDialog = open,
            publishMqttDisabledReason = reason,
        )
    }
})

@OptIn(ExperimentalTestApi::class)
internal class MqttGateTopologyDriver : MqttGateDriver({
    val scope = androidx.compose.runtime.remember { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { scope.cancel() } }
    var showDialog by androidx.compose.runtime.remember { mutableStateOf(false) }
    val vm = androidx.compose.runtime.remember { PublishMqttViewModel(MqttGateWorld.publisher, "prod-eu", scope) }
    val dialogState by vm.state.collectAsState()
    val (open, reason) = when (val access = MqttGateWorld.access) {
        MqttPublishAccess.NoMqttPort -> null to null
        is MqttPublishAccess.NoWritePermission -> null to access.reason
        MqttPublishAccess.Allowed -> ({ showDialog = true }) to null
    }
    run {
        TopologyScreen(
            state = TopologyState(screen = TopologyScreenKind.Ready, tab = TopologyTab.Mqtt, hasMqtt = MqttGateWorld.mqttPort > 0, vhost = MqttGateWorld.vhost),
            nowMillis = 0,
            events = NoTopologyScreenEvents,
            control = connectedChip,
            controlEvents = noControl,
            showPublishMqttDialog = showDialog,
            publishMqttState = dialogState,
            publishMqttEvents = DialogEvents(vm),
            onOpenPublishMqttDialog = open,
            publishMqttDisabledReason = reason,
        )
    }
})

private object NoTopologyScreenEvents : dev.caerbannog.ui.topology.TopologyEvents {
    override fun setShown(shown: Boolean) = Unit
    override fun refreshNow() = Unit
    override fun retry() = Unit
    override fun setTab(tab: TopologyTab) = Unit
    override fun setQueueFilter(filter: dev.caerbannog.core.topology.QueueTypeFilter) = Unit
    override fun setSearch(text: String) = Unit
    override fun selectVhost(name: String) = Unit
    override fun selectQueue(name: String?) = Unit
    override fun setShowDefaultExchange(show: Boolean) = Unit
    override fun setBindingsView(mode: dev.caerbannog.core.topology.BindingsViewMode) = Unit
    override fun nextPage() = Unit
    override fun previousPage() = Unit
}

/** The screens of the running scenario; disposed after it. */
internal object MqttGateScreens {
    var stream: MqttGateStreamDriver? = null
    var topology: MqttGateTopologyDriver? = null

    fun openStream(name: String, route: StreamInboundRoute): MqttGateStreamDriver {
        stream?.dispose()
        return MqttGateStreamDriver(name, route).also { stream = it }
    }

    fun openTopology(): MqttGateTopologyDriver {
        topology?.dispose()
        return MqttGateTopologyDriver().also { topology = it }
    }

    fun endScenario() {
        stream?.dispose()
        topology?.dispose()
        stream = null
        topology = null
    }
}
