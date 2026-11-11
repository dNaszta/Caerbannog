package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.migrate3x.SecretValue

enum class SrcDeleteAfter { QUEUE_LENGTH, NEVER }

/** The only value in v1 (P9); no request field can change it. */
enum class AckMode(val wireName: String) { ON_CONFIRM("on-confirm") }

enum class DestinationQueueType { QUORUM, STREAM, CLASSIC, OTHER }

/** Plain class (no equals, no copy): the source password of one shovel. */
class ShovelSourceCredentials(val username: String, private val password: SecretValue) {
    fun passwordForRequest(): String = password.expose()
    override fun toString() = "ShovelSourceCredentials($username, ***)"
}

/** There is NO password field, so no toString or copy can leak one. */
data class ShovelAddress(val tls: Boolean, val username: String, val host: String, val port: Int, val vhost: String) {
    val scheme: String get() = if (tls) "amqps" else "amqp"
    override fun toString() = "$scheme://$username@$host:$port/${percentEncode(vhost)}"

    companion object {
        /** M8-07 STUB: ignores the profile switch and the credentials. */
        fun forProfile(profile: ConnectionProfile, host: String = profile.host, port: Int? = null, credentials: ShovelSourceCredentials? = null): ShovelAddress =
            ShovelAddress(true, profile.username, host, port ?: 5671, profile.virtualHost) // M8-07 STUB
    }
}

/** M8-07 STUB: does not encode. */
internal fun percentEncode(text: String): String = text // M8-07 STUB

data class ForwardingSource(
    val connectionName: String,
    val address: ShovelAddress,
    val vhost: String,
    val queue: String,
    val credentials: ShovelSourceCredentials?,
)

data class ForwardingRequest(
    val source: ForwardingSource,
    val destinationConnection: String,
    val destinationVhost: String,
    val destinationQueue: String,
    val srcDeleteAfter: SrcDeleteAfter = SrcDeleteAfter.QUEUE_LENGTH,
) {
    val ackMode: AckMode get() = AckMode.ON_CONFIRM
    val shovel: ShovelRef get() = ShovelRef(destinationVhost, ShovelNaming.nameFor(source.queue))
}

data class ShovelRef(val vhost: String, val name: String)

object ShovelNaming { fun nameFor(sourceQueue: String): String = "caerbannog-migrate-$sourceQueue" }
