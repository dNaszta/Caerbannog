package dev.caerbannog.core.publish

/**
 * Whether a control that opens the Publish MQTT dialog may be used. One rule for every surface (the test message of a route, the stream
 * screen, the pipeline graph, the topology screen), so they cannot disagree: the MQTT port first, then the write permission.
 */
sealed interface MqttPublishAccess {
    /** The profile has no MQTT port. */
    data object NoMqttPort : MqttPublishAccess

    /** MQTT is reachable but the profile has no write permission; [reason] is [noWritePermission] for the virtual host. */
    data class NoWritePermission(val reason: String) : MqttPublishAccess

    data object Allowed : MqttPublishAccess

    companion object {
        /** An unfinished or failed permission check arrives as `publishingEnabled = false`. */
        fun of(hasMqtt: Boolean, publishingEnabled: Boolean, vhost: String): MqttPublishAccess = when {
            !hasMqtt -> NoMqttPort
            !publishingEnabled -> NoWritePermission(noWritePermission(vhost))
            else -> Allowed
        }
    }
}
