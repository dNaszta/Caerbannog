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
        /** [credentials] null = the profile user. The scheme follows the profile's TLS switch only; there is no fallback (D9a). */
        fun forProfile(profile: ConnectionProfile, host: String = profile.host, port: Int? = null, credentials: ShovelSourceCredentials? = null): ShovelAddress =
            ShovelAddress(profile.tls, credentials?.username ?: profile.username, host, port ?: if (profile.tls) 5671 else 5672, profile.virtualHost)
    }
}

/** Percent-encodes everything but the RFC 3986 unreserved characters, byte by byte in UTF-8 (so "/" prints %2F). */
internal fun percentEncode(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') append(c)
        else append('%').append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[byte.toInt() and 0xF])
    }
}

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
