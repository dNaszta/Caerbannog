package dev.caerbannog.core.publish

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gate determining whether publishing a test message is permitted for this connection and user.
 */
sealed interface PublishGate {
    data object Enabled : PublishGate
    data class Disabled(val reason: String) : PublishGate
}

/**
 * UI State for the Publish Test Message dialog.
 */
data class PublishMessageState(
    val connectionName: String,
    val vhost: String,
    val gate: PublishGate = PublishGate.Enabled,
    val targetKind: PublishTargetKind = PublishTargetKind.STREAM,
    val targetName: String = "",
    val routingKey: String = "",
    val messageId: String = "",
    val contentType: String = "application/json",
    val body: String = "",
    val applicationProperties: List<Pair<String, String>> = emptyMap<String, String>().toList(),
    val isPublishing: Boolean = false,
    val confirmation: String? = null,
    val error: String? = null,
) {
    val canPublish: Boolean
        get() = gate is PublishGate.Enabled &&
            targetName.isNotBlank() &&
            !isPublishing

    val warningBanner: String
        get() = "This message goes to the real broker on $connectionName. Publishing test messages is allowed for this user, but its consumers will receive it."
}

/**
 * View model managing test message composition, target selection, permission validation,
 * and publish dispatch.
 */
class PublishMessageViewModel(
    private val publisher: MessagePublisher,
    private val connectionName: String,
    private val vhost: String,
    private val writePermission: Boolean = true,
    private val scope: CoroutineScope,
    initialTargetKind: PublishTargetKind = PublishTargetKind.STREAM,
    initialTargetName: String = "",
) {
    private val gate: PublishGate = if (writePermission) {
        PublishGate.Enabled
    } else {
        PublishGate.Disabled("Test publishing is disabled: no write permission on virtual host $vhost.")
    }

    private val _state = MutableStateFlow(
        PublishMessageState(
            connectionName = connectionName,
            vhost = vhost,
            gate = gate,
            targetKind = initialTargetKind,
            targetName = initialTargetName,
        )
    )
    val state: StateFlow<PublishMessageState> = _state.asStateFlow()

    fun setTargetKind(kind: PublishTargetKind) {
        _state.value = _state.value.copy(targetKind = kind, confirmation = null, error = null)
    }

    fun setTargetName(name: String) {
        _state.value = _state.value.copy(targetName = name, confirmation = null, error = null)
    }

    fun setRoutingKey(key: String) {
        _state.value = _state.value.copy(routingKey = key, confirmation = null, error = null)
    }

    fun setMessageId(id: String) {
        _state.value = _state.value.copy(messageId = id, confirmation = null, error = null)
    }

    fun setContentType(type: String) {
        _state.value = _state.value.copy(contentType = type, confirmation = null, error = null)
    }

    fun setBody(body: String) {
        _state.value = _state.value.copy(body = body, confirmation = null, error = null)
    }

    fun addApplicationProperty(key: String, value: String) {
        val current = _state.value.applicationProperties.filterNot { it.first == key }
        _state.value = _state.value.copy(
            applicationProperties = current + (key to value),
            confirmation = null,
            error = null,
        )
    }

    fun removeApplicationProperty(key: String) {
        _state.value = _state.value.copy(
            applicationProperties = _state.value.applicationProperties.filterNot { it.first == key },
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
            val outcome = if (current.gate is PublishGate.Disabled) {
                PublishOutcome.Refused(current.gate.reason)
            } else {
                PublishOutcome.Refused("Target name is required.")
            }
            _state.value = current.copy(error = outcome.reason)
            onComplete?.invoke(outcome)
            return
        }

        _state.value = current.copy(isPublishing = true, confirmation = null, error = null)

        val properties = buildMap {
            if (current.messageId.isNotBlank()) put("messageId", current.messageId)
            if (current.contentType.isNotBlank()) put("contentType", current.contentType)
        }
        val appProperties = current.applicationProperties.toMap()
        val target = PublishTarget(
            kind = current.targetKind,
            name = current.targetName,
            routingKey = current.routingKey.takeIf { it.isNotBlank() },
        )
        val request = PublishMessageRequest(
            vhost = current.vhost,
            target = target,
            payload = current.body.encodeToByteArray(),
            properties = properties,
            applicationProperties = appProperties,
        )

        scope.launch {
            val outcome = try {
                publisher.publish(request)
            } catch (e: Exception) {
                PublishOutcome.Failed(e.message ?: "Failed to publish message")
            }

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
}
