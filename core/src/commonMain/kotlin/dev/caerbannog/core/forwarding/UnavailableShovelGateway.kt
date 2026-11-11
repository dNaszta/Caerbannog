package dev.caerbannog.core.forwarding

/** The shovel gateway of a connection that is not a held 4.x one (a 3.x connection can never be a shovel destination). */
object UnavailableShovelGateway : ShovelGateway {
    override suspend fun dryRun(request: ForwardingRequest): DryRunOutcome = DryRunOutcome.Failed(ForwardingTexts.NO_DESTINATION)
    override suspend fun start(request: ForwardingRequest): StartOutcome = StartOutcome.Failed(ForwardingTexts.NO_DESTINATION)
    override suspend fun status(shovel: ShovelRef): StatusOutcome = StatusOutcome.Failed(ForwardingTexts.NO_DESTINATION)
    override suspend fun remove(shovel: ShovelRef): RemoveOutcome = RemoveOutcome.Failed(ForwardingTexts.NO_DESTINATION)
}
