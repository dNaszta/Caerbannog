package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrate3x.ReadOutcome
import dev.caerbannog.core.migrate3x.ThreeXReads

/** M8-02b STUB: the most queue pages one read of a vhost may take (R1); the stub only names it. */
internal const val MAX_QUEUE_PAGES: Int = 100

/**
 * M8-02b STUB (test developer): every read fails with a fixed text. The senior developer replaces the bodies with the real GET calls
 * (see docs/tasks/M8-02-live-3x-source.md, "M8-02b: scope" item 1). GET-only: this class must only ever call [ManagementHttp.get].
 */
internal class ManagementThreeXReads(
    @Suppress("unused") private val http: ManagementHttp,
    @Suppress("unused") private val requests: ManagementRequests,
) : ThreeXReads {
    override suspend fun definitions(): ReadOutcome<String> = ReadOutcome.Failed("M8-02b STUB")
    override suspend fun storageVersions(vhost: String): ReadOutcome<Map<String, Int?>> = ReadOutcome.Failed("M8-02b STUB")
    override suspend fun deprecatedFeaturesInUse(): ReadOutcome<Set<String>> = ReadOutcome.Failed("M8-02b STUB")
}
