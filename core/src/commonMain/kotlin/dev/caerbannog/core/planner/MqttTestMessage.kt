package dev.caerbannog.core.planner

import dev.caerbannog.core.migrations.MigrationStatus
import dev.caerbannog.core.mqtt.CorrelationIdSource
import dev.caerbannog.core.mqtt.HeaderNotice
import dev.caerbannog.core.mqtt.MqttHeader
import dev.caerbannog.core.publish.MqttPublishAccess

/** The decided wording of the test message (owner decisions O16 and O17, and the refusals of the sample dialog). Never inline in a composable. */
object MqttTestMessageTexts {
    const val BUTTON: String = "Send a test message"
    const val DIALOG_TITLE: String = "Send a test message"
    const val OPEN_BUTTON: String = "Open the dialog"
    const val CANCEL_BUTTON: String = "Cancel"
    const val NO_MQTT_PORT: String = "This connection has no MQTT port"
    const val VALUE_HINT: String = "for example a device id"
    const val HASH_LABEL: String = "Value for the rest of the topic"
    const val ENTER_A_VALUE: String = "Enter a value"
    const val ONE_LEVEL_ONLY: String = "One level only: no /"
    const val NO_WILDCARD_IN_VALUE: String = "No + or # in a value"
    const val NO_CONTROL_CHARACTERS: String = "No control characters in a value"

    const val STREAM_WARNING: String =
        "In the stream message this arrives as the application property correlation-id, not as the AMQP correlation-id property. " +
            "Your service must read and write that application property."
    const val QUEUE_WARNING: String =
        "In the queue message this arrives as an entry of the headers table named correlation-id, not as the AMQP correlation_id property. " +
            "Your service must read and write that header."

    fun plusLabel(level: Int): String = "Value for the + in level $level"

    fun label(slot: SampleSlot): String = when (slot) {
        is SampleSlot.Plus -> plusLabel(slot.level)
        is SampleSlot.Hash -> HASH_LABEL
    }

    /** A programming error of the caller (the UI always passes the right count); kept as a text so nothing throws. */
    fun wrongCount(slots: Int, values: Int): String = "Expected $slots values, got $values"
}

sealed interface TestMessageOffer {
    /** The route is not shown with a button at all. */
    data object NotOffered : TestMessageOffer

    /** The button is shown disabled with [reason]. */
    data class Disabled(val reason: String) : TestMessageOffer

    data object Available : TestMessageOffer
}

/** What the Publish MQTT dialog opens with: nothing is published until the user presses Publish. */
data class TestMessageOpening(val topic: String, val headers: List<MqttHeader>, val notice: HeaderNotice?)

object MqttTestMessage {
    const val CORRELATION_KEY: String = "correlation-id"

    /** Applied, then the MQTT port, then the write permission. An unfinished permission check arrives as `publishingEnabled = false`. */
    fun offer(status: MigrationStatus, route: MqttRoute, hasMqtt: Boolean, publishingEnabled: Boolean, vhost: String): TestMessageOffer {
        if (status !is MigrationStatus.Applied) return TestMessageOffer.NotOffered
        return when (val access = MqttPublishAccess.of(hasMqtt, publishingEnabled, vhost)) {
            MqttPublishAccess.NoMqttPort -> TestMessageOffer.Disabled(MqttTestMessageTexts.NO_MQTT_PORT)
            is MqttPublishAccess.NoWritePermission -> TestMessageOffer.Disabled(access.reason)
            MqttPublishAccess.Allowed -> TestMessageOffer.Available
        }
    }

    /**
     * Never throws. With refused values or a wrong count the topic is the route's filter unchanged (nothing is invented); the Publish MQTT
     * dialog refuses a wildcard topic, so nothing can be published from it. The notice follows the [kind]; an unknown kind gets none.
     */
    fun opening(route: MqttRoute, kind: MqttDestinationKind?, values: List<String>, ids: CorrelationIdSource): TestMessageOpening {
        val topic = (MqttTopicSample.fill(route.filter, values) as? SampleResult.Topic)?.topic ?: route.filter
        val text = when (kind) {
            MqttDestinationKind.Stream -> MqttTestMessageTexts.STREAM_WARNING
            MqttDestinationKind.Queue -> MqttTestMessageTexts.QUEUE_WARNING
            null -> null
        }
        return TestMessageOpening(
            topic = topic,
            headers = listOf(MqttHeader(CORRELATION_KEY, ids.next())),
            notice = text?.let { HeaderNotice(CORRELATION_KEY, it) },
        )
    }
}
