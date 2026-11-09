package dev.caerbannog.core.migrate3x

data class QueueRef(val vhost: String, val name: String)

/** [Unknown]: the value never appeared after the scan's retries. */
enum class ClassicIndexVersion { V1, V2, Unknown }

/** Optional facts only a live 3.x connection can give. The file-only case passes no facts at all. */
data class LiveSourceFacts(
    val connectionName: String,
    /** `rabbitmq_version` of `GET /api/overview`. */
    val brokerVersion: String?,
    /** A classic queue missing from the map counts as [ClassicIndexVersion.Unknown]. */
    val classicIndexVersions: Map<QueueRef, ClassicIndexVersion> = emptyMap(),
    /** Names from `GET /api/deprecated-features/used`. */
    val deprecatedFeaturesInUse: Set<String> = emptySet(),
)
