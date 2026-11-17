package dev.caerbannog.core.streams

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the Create Stream dialog.
 */
data class CreateStreamState(
    val isOpen: Boolean = false,
    val name: String = "",
    val error: String? = null,
    val isCreating: Boolean = false,
)

/**
 * State of the Delete Stream confirmation dialog.
 */
data class DeleteStreamState(
    val isOpen: Boolean = false,
    val streamName: String = "",
    val typedConfirmation: String = "",
    val consumers: Int = 0,
    val error: String? = null,
    val isDeleting: Boolean = false,
) {
    val canConfirm: Boolean
        get() = typedConfirmation == streamName && !isDeleting

    val consumersWarning: String?
        get() = if (consumers > 0) "$consumers consumers are connected" else null
}

/**
 * View model managing creation and deletion of streams with typed confirmation,
 * consumer safety warnings, name validation, and configure-permission gating.
 */
class StreamManagementViewModel(
    private val manager: StreamManager,
    val connectionName: String,
    val vhost: String,
    val configurePermission: Boolean = true,
    private val existingStreams: () -> Set<String> = { emptySet() },
    private val consumerCounts: (String) -> Int = { 0 },
    private val onStreamCreated: suspend (String) -> Unit = {},
    private val onStreamDeleted: suspend (String) -> Unit = {},
    private val scope: CoroutineScope,
) {
    val canCreate: Boolean get() = configurePermission
    val canDelete: Boolean get() = configurePermission

    val gateReason: String? = if (!configurePermission) {
        "Topology is locked because the user has no configure permission on virtual host $vhost."
    } else null

    private val _createState = MutableStateFlow(CreateStreamState())
    val createState: StateFlow<CreateStreamState> = _createState.asStateFlow()

    private val _deleteState = MutableStateFlow(DeleteStreamState())
    val deleteState: StateFlow<DeleteStreamState> = _deleteState.asStateFlow()

    fun openCreate() {
        if (!canCreate) return
        _createState.value = CreateStreamState(isOpen = true)
    }

    fun closeCreate() {
        _createState.value = CreateStreamState(isOpen = false)
    }

    fun setCreateName(name: String) {
        _createState.update { it.copy(name = name, error = null) }
    }

    suspend fun confirmCreate(): Boolean {
        if (!canCreate) return false
        val name = _createState.value.name.trim()
        if (name.isEmpty()) {
            _createState.update { it.copy(error = "A name is required") }
            return false
        }
        if (existingStreams().contains(name)) {
            _createState.update { it.copy(error = "A stream with this name exists") }
            return false
        }

        _createState.update { it.copy(isCreating = true, error = null) }
        // The default settings on purpose: this dialog makes plain streams (retention is a policy or a migration file, M9-14c).
        return when (val outcome = manager.createStream(vhost, name)) {
            is StreamManagementResult.Success -> {
                _createState.value = CreateStreamState(isOpen = false)
                onStreamCreated(name)
                true
            }
            is StreamManagementResult.Refused -> {
                _createState.update { it.copy(isCreating = false, error = outcome.reason) }
                false
            }
            is StreamManagementResult.Failed -> {
                _createState.update { it.copy(isCreating = false, error = outcome.message) }
                false
            }
            is StreamManagementResult.NotFound -> {
                _createState.update { it.copy(isCreating = false, error = "The stream does not exist") }
                false
            }
        }
    }

    fun openDelete(streamName: String, activeConsumers: Int = consumerCounts(streamName)) {
        if (!canDelete) return
        _deleteState.value = DeleteStreamState(
            isOpen = true,
            streamName = streamName,
            consumers = activeConsumers,
        )
    }

    fun closeDelete() {
        _deleteState.value = DeleteStreamState(isOpen = false)
    }

    fun setDeleteConfirmation(typed: String) {
        _deleteState.update { it.copy(typedConfirmation = typed, error = null) }
    }

    suspend fun confirmDelete(): Boolean {
        if (!canDelete) return false
        val state = _deleteState.value
        if (!state.canConfirm) return false

        _deleteState.update { it.copy(isDeleting = true, error = null) }
        return when (val outcome = manager.deleteStream(vhost, state.streamName)) {
            is StreamManagementResult.Success -> {
                _deleteState.value = DeleteStreamState(isOpen = false)
                onStreamDeleted(state.streamName)
                true
            }
            is StreamManagementResult.Refused -> {
                _deleteState.update { it.copy(isDeleting = false, error = outcome.reason) }
                false
            }
            is StreamManagementResult.Failed -> {
                _deleteState.update { it.copy(isDeleting = false, error = outcome.message) }
                false
            }
            is StreamManagementResult.NotFound -> {
                _deleteState.update { it.copy(isDeleting = false, error = "The stream does not exist") }
                false
            }
        }
    }
}
