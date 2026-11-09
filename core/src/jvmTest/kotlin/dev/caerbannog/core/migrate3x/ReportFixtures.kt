package dev.caerbannog.core.migrate3x

/** A small hand-built report (not analyzer output) for the JSON and text golden tests. Must match fixtures/m8-03/report-expected.json. */
internal fun smallReport(): CompatibilityReport = CompatibilityReport(
    source = ReportSource("legacy-eu", "3.13.7", live = true, singleVirtualHost = null),
    summary = ReportSummary(topologyItems = 2, compatibleItems = 1, needDecision = 1, blockers = 1, warnings = 1, infos = 1, messageLoss = 1),
    findings = listOf(
        Finding(
            id = "classic-index-v1|/|Queue|orders|", rule = "classic-index-v1", severity = Severity.Blocker, findingClass = FindingClass.MessageLoss,
            kind = ItemKind.Queue, vhost = "/", name = "orders", about = emptyList(), appliesWhen = AppliesWhen.Always,
            message = "message one", why = "why one",
            proposal = Proposal.Manual("Migrate", "Use a new broker", true),
            alternatives = listOf(Proposal.Manual("Policy", "Set queue-version", false)),
            defaultDecision = DefaultDecision.Accept, verified = true, evidence = EvidenceSource.LiveQueueStats,
        ),
        Finding(
            id = "classic-not-replicated|/|Queue|jobs|", rule = "classic-not-replicated", severity = Severity.Warning, findingClass = FindingClass.BehaviourChange,
            kind = ItemKind.Queue, vhost = "/", name = "jobs", about = listOf("x-max-length", "x-message-ttl"), appliesWhen = AppliesWhen.Always,
            message = "message two", why = "why two",
            proposal = Proposal.ConvertQueueType(QueueKind.Quorum, Acknowledgement.OrderingByPriorityMayChange),
            alternatives = listOf(Proposal.KeepClassic),
            defaultDecision = DefaultDecision.Accept, verified = true, evidence = EvidenceSource.Definitions,
        ),
        Finding(
            id = "classic-to-quorum-unmeasured|-|Global|-|", rule = "classic-to-quorum-unmeasured", severity = Severity.Info, findingClass = FindingClass.BehaviourChange,
            kind = ItemKind.Global, vhost = null, name = null, about = emptyList(), appliesWhen = AppliesWhen.Always,
            message = "message three", why = "why three",
            proposal = Proposal.None, alternatives = emptyList(),
            defaultDecision = DefaultDecision.Skip, verified = false, evidence = EvidenceSource.KnownBrokerBehaviour,
        ),
    ),
)
