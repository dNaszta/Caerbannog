package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.StreamSource
import dev.caerbannog.core.mqtt.StreamInboundRoute
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
 * Only a profile with an MQTT port can publish over MQTT. The publisher object always exists and an `amq.topic` binding alone
 * does not make the broker reachable by MQTT, so neither decides.
 */
internal fun offersMqttPublishing(source: StreamSource?): Boolean = source?.hasMqtt == true

/** The stream screen's "Publish MQTT" action: absent without MQTT; otherwise it pre-fills the suggested topic (if any) and opens the dialog. */
internal fun streamMqttOpener(source: StreamSource?, setTopic: (String) -> Unit, openDialog: () -> Unit): ((String?) -> Unit)? =
    if (offersMqttPublishing(source)) {
        { suggestedTopic ->
            suggestedTopic?.let(setTopic)
            openDialog()
        }
    } else {
        null
    }

/** The topology screen's "Publish MQTT" action: absent without MQTT, otherwise it opens the dialog. */
internal fun topologyMqttOpener(source: StreamSource?, openDialog: () -> Unit): (() -> Unit)? =
    if (offersMqttPublishing(source)) openDialog else null

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
