package dev.caerbannog.core.forwarding

sealed interface Prepared {
    data class Ready(val plan: ForwardingPlan, val facts: DryRunResult, val confirmation: ForwardingConfirmation) : Prepared
    data class Blocked(val plan: ForwardingPlan, val facts: DryRunResult?) : Prepared
    data class ProbeFailed(val outcome: DryRunOutcome) : Prepared
}

sealed interface StartResult {
    data object Started : StartResult
    data object NotConfirmed : StartResult
    data class Blocked(val plan: ForwardingPlan) : StartResult
    data class Refused(val reason: String) : StartResult
    data class ProbeFailed(val outcome: DryRunOutcome) : StartResult
    data class Failed(val reason: String) : StartResult
}

sealed interface RemoveResult {
    data object Removed : RemoveResult
    data object NeedsFullControl : RemoveResult
    data class Failed(val reason: String) : RemoveResult
}

class ForwardingService(private val gateway: ShovelGateway, private val context: () -> ForwardingContext) {
    suspend fun prepare(request: ForwardingRequest): Prepared = Prepared.ProbeFailed(DryRunOutcome.Failed("M8-07 STUB")) // M8-07 STUB
    suspend fun start(request: ForwardingRequest, typedName: String): StartResult = StartResult.Failed("M8-07 STUB") // M8-07 STUB
    suspend fun poll(request: ForwardingRequest, previous: ForwardingState): ForwardingState = previous // M8-07 STUB
    suspend fun remove(shovel: ShovelRef): RemoveResult = RemoveResult.Failed("M8-07 STUB") // M8-07 STUB
}
