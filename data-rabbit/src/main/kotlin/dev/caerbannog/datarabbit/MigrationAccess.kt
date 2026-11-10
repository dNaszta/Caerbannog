package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.brokerMajorVersion
import dev.caerbannog.core.connections.displayableBrokerVersion
import dev.caerbannog.core.json.UntrustedJson
import dev.caerbannog.core.json.parseUntrustedJson
import dev.caerbannog.core.migrations.TopologyMigrationExecutor
import dev.caerbannog.core.streams.StreamManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Whether a connection may apply migrations, decided FAIL CLOSED from the broker's version: an executor only for RabbitMQ 4 or later. A 3.x
 * broker is a migration source only, and a version that cannot be read (any failure, any shape) gives no executor either. One
 * `GET /api/overview` decides; no write request is sent before the version is known.
 */
internal data class MigrationAccess(
    val executor: TopologyMigrationExecutor?,
    val unavailableReason: String?,
    /** The displayable version text of the one overview read; null when it could not be read or looks odd. */
    val version: String? = null,
) {

    companion object {
        const val THREE_X = "This broker runs RabbitMQ 3.x: it is a migration source only"
        const val UNKNOWN_VERSION = "The broker version could not be read, so migrations are not offered"

        /** The overview is a shallow object; far below the reader's failure point (M5-07). */
        private const val MAX_OVERVIEW_DEPTH = 64
        private const val FIRST_SUPPORTED_MAJOR = 4

        suspend fun forBroker(http: ManagementHttp, requests: ManagementRequests, streams: StreamManager): MigrationAccess {
            val version = readVersion(http, requests)
            val major = brokerMajorVersion(version)
            return when {
                major == null -> MigrationAccess(null, UNKNOWN_VERSION)
                major < FIRST_SUPPORTED_MAJOR -> MigrationAccess(null, THREE_X, version)
                else -> MigrationAccess(ManagementTopologyMigrationExecutor(http, requests, streams) { delay(it) }, null, version)
            }
        }

        private suspend fun readVersion(http: ManagementHttp, requests: ManagementRequests): String? {
            val reply = http.get(requests.overview()) as? ManagementReply.Response ?: return null
            if (reply.status != OK) return null
            val root = (parseUntrustedJson(reply.body, MAX_OVERVIEW_DEPTH) as? UntrustedJson.Parsed)?.element as? JsonObject ?: return null
            val raw = (root["rabbitmq_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return displayableBrokerVersion(raw)
        }

        private const val OK = 200
    }
}
