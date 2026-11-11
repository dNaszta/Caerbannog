package dev.caerbannog.core.forwarding

/** The shovel gateway of a connection that is not a held 4.x one (a 3.x connection can never be a shovel destination). M8-09 STUB: the text is not final. */
object UnavailableShovelGateway : ShovelGateway {
    override suspend fun dryRun(request: ForwardingRequest): DryRunOutcome = DryRunOutcome.Failed("") // M8-09 STUB
    override suspend fun start(request: ForwardingRequest): StartOutcome = StartOutcome.Failed("") // M8-09 STUB
    override suspend fun status(shovel: ShovelRef): StatusOutcome = StatusOutcome.Failed("") // M8-09 STUB
    override suspend fun remove(shovel: ShovelRef): RemoveOutcome = RemoveOutcome.Failed("") // M8-09 STUB
}
