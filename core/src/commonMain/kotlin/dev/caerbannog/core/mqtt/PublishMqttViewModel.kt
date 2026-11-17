package dev.caerbannog.core.mqtt

import dev.caerbannog.core.publish.PublishOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI State for the Publish MQTT Message dialog.
 */
data class PublishMqttState(
    val connectionName: String,
    val topic: String = "",
    val qos: MqttQos = MqttQos.AT_MOST_ONCE,
    val retain: Boolean = false,
    val payload: String = DEFAULT_PAYLOAD,
    val headers: List<MqttHeader> = emptyList(),
    val isPublishing: Boolean = false,
    val confirmation: String? = null,
    val error: String? = null,
    val topicError: String? = null,
    val headerNotice: HeaderNotice? = null,
) {
    /** The notice text while a header with the notice's key (trimmed, case-sensitive) is in the list; never part of [canPublish]. */
    val headerNoticeText: String?
        get() = headerNotice?.takeIf { notice -> headers.any { it.key.trim() == notice.key } }?.text

    val canPublish: Boolean
        get() = topic.isNotBlank() &&
            topicError == null &&
            !isPublishing

    companion object {
        /** Editable sample message the dialog opens with, so the field holds real text instead of an inert placeholder. */
        const val DEFAULT_PAYLOAD: String = "{\"temperature\": 21.5, \"unit\": \"C\"}"
    }

    val warningBanner: String
        get() = "This message will be sent over MQTT to the broker on $connectionName. Connected subscribers with matching topics will receive it."
}

/**
 * View model managing MQTT message composition, QoS/retain configuration,
 * topic validation, and dispatch to [MqttMessagePublisher].
 */
class PublishMqttViewModel(
    private val publisher: MqttMessagePublisher,
    private val connectionName: String,
    private val scope: CoroutineScope,
    initialTopic: String = "",
    initialHeaders: List<MqttHeader> = emptyList(),
    headerNotice: HeaderNotice? = null,
) {
    private val _state = MutableStateFlow(
        PublishMqttState(
            connectionName = connectionName,
            topic = initialTopic,
            topicError = validateTopic(initialTopic),
            headers = initialHeaders,
            headerNotice = headerNotice,
        )
    )
    val state: StateFlow<PublishMqttState> = _state.asStateFlow()

    fun setTopic(topic: String) {
        val err = validateTopic(topic)
        _state.value = _state.value.copy(
            topic = topic,
            topicError = err,
            confirmation = null,
            error = null,
        )
    }

    fun setQos(qos: MqttQos) {
        _state.value = _state.value.copy(qos = qos, confirmation = null, error = null)
    }

    fun setRetain(retain: Boolean) {
        _state.value = _state.value.copy(retain = retain, confirmation = null, error = null)
    }

    fun setPayload(payload: String) {
        _state.value = _state.value.copy(payload = payload, confirmation = null, error = null)
    }

    fun addHeader() {
        _state.value = _state.value.copy(headers = _state.value.headers + MqttHeader(), confirmation = null, error = null)
    }

    fun setHeader(index: Int, key: String, value: String) {
        val current = _state.value
        if (index !in current.headers.indices) return
        _state.value = current.copy(
            headers = current.headers.toMutableList().also { it[index] = MqttHeader(key, value) },
            confirmation = null,
            error = null,
        )
    }

    fun removeHeader(index: Int) {
        val current = _state.value
        if (index !in current.headers.indices) return
        _state.value = current.copy(
            headers = current.headers.toMutableList().also { it.removeAt(index) },
            confirmation = null,
            error = null,
        )
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(confirmation = null, error = null)
    }

    fun cancel() {
        _state.value = _state.value.copy(
            isPublishing = false,
            confirmation = null,
            error = null,
        )
    }

    fun publish(onComplete: ((PublishOutcome) -> Unit)? = null) {
        val current = _state.value
        if (!current.canPublish) {
            val err = current.topicError ?: "Topic is required."
            _state.value = current.copy(error = err)
            onComplete?.invoke(PublishOutcome.Refused(err))
            return
        }

        _state.value = current.copy(isPublishing = true, confirmation = null, error = null)
        val request = PublishMqttRequest(
            topic = current.topic.trim(),
            payload = current.payload.encodeToByteArray(),
            qos = current.qos,
            retain = current.retain,
            // Rows with a blank key are unfinished and are not sent.
            headers = current.headers.filter { it.key.isNotBlank() }.map { MqttHeader(it.key.trim(), it.value) },
        )

        scope.launch {
            val outcome = publisher.publish(request)
            when (outcome) {
                is PublishOutcome.Success -> {
                    _state.value = _state.value.copy(
                        isPublishing = false,
                        confirmation = outcome.message,
                        error = null,
                    )
                }
                is PublishOutcome.Refused -> {
                    _state.value = _state.value.copy(
                        isPublishing = false,
                        confirmation = null,
                        error = outcome.reason,
                    )
                }
                is PublishOutcome.Failed -> {
                    _state.value = _state.value.copy(
                        isPublishing = false,
                        confirmation = null,
                        error = outcome.error,
                    )
                }
            }
            onComplete?.invoke(outcome)
        }
    }

    companion object {
        fun validateTopic(topic: String): String? {
            if (topic.isEmpty()) return null
            if (topic.isBlank()) return "Topic cannot be blank"
            if (topic.contains('+') || topic.contains('#')) {
                return "Topic cannot contain wildcards '+' or '#'"
            }
            return null
        }
    }
}
