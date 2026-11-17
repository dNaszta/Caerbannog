package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.mqtt.StreamInboundRoute
import dev.caerbannog.core.publish.MqttPublishAccess
import dev.caerbannog.core.topology.BindingRow

/*
 * The small decisions the screens' wiring in Main.kt makes, pulled out of the composables so they can be unit-tested
 * without a window. Behaviour is the same as when they were inline.
 */

/** The bindings that feed [stream]: its own destination, and not the default exchange (an empty source). */
internal fun inboundRoutesFor(bindings: List<BindingRow>, stream: String): List<StreamInboundRoute> =
    bindings
        .filter { it.destination == stream && it.source.isNotEmpty() }
        .map { StreamInboundRoute(exchange = it.source, routingKey = it.routingKey, arguments = it.arguments) }

/**
 * The one rule behind every Publish MQTT control (see [MqttPublishAccess]). The publisher object always exists and an `amq.topic`
 * binding alone does not make the broker reachable by MQTT, so only the profile's MQTT port decides that part.
 */
internal fun mqttPublishAccess(source: StreamSource?, publishingEnabled: Boolean): MqttPublishAccess =
    MqttPublishAccess.of(hasMqtt = source?.hasMqtt == true, publishingEnabled = publishingEnabled, vhost = source?.vhost ?: "/")

/**
 * What a screen gets for a "Publish MQTT" control. Absent: both null. Disabled: [open] null and [disabledReason] set. Enabled: [open] set.
 */
internal data class MqttEntry<T>(val open: T?, val disabledReason: String?)

private fun <T> entryFor(access: MqttPublishAccess, open: T): MqttEntry<T> = when (access) {
    MqttPublishAccess.NoMqttPort -> MqttEntry(null, null)
    is MqttPublishAccess.NoWritePermission -> MqttEntry(null, access.reason)
    MqttPublishAccess.Allowed -> MqttEntry(open, null)
}

/** The stream screen's "Publish MQTT" action: absent without MQTT, disabled without write permission; otherwise it pre-fills the suggested topic (if any) and opens the dialog. */
internal fun streamMqttOpener(
    source: StreamSource?,
    publishingEnabled: Boolean,
    setTopic: (String) -> Unit,
    openDialog: () -> Unit,
): MqttEntry<(String?) -> Unit> = entryFor<(String?) -> Unit>(mqttPublishAccess(source, publishingEnabled)) { suggestedTopic ->
    suggestedTopic?.let(setTopic)
    openDialog()
}

/** The topology screen's "Publish MQTT" action: absent without MQTT, disabled without write permission, otherwise it opens the dialog. */
internal fun topologyMqttOpener(source: StreamSource?, publishingEnabled: Boolean, openDialog: () -> Unit): MqttEntry<() -> Unit> =
    entryFor(mqttPublishAccess(source, publishingEnabled), openDialog)

/** After a publish on the stream screen: show the newest page of the message browser (when there is one), then refresh clients and the list. */
internal fun afterStreamPublish(browseLastPage: (() -> Unit)?, refreshClients: () -> Unit, refreshStreamList: () -> Unit): () -> Unit = {
    browseLastPage?.invoke()
    refreshClients()
    refreshStreamList()
}

/** After an MQTT publish on the topology screen: refresh the topology (queue counts) and the stream list. */
internal fun afterTopologyPublish(refreshTopology: () -> Unit, refreshStreamList: () -> Unit): () -> Unit = {
    refreshTopology()
    refreshStreamList()
}
