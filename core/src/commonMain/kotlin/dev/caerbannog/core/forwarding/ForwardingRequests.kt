package dev.caerbannog.core.forwarding

import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.migrate3x.SecretValue

/** What the confirmation form holds. The password is a [SecretValue] (redacting toString): it is dropped with the form and never printed. */
data class ForwardingForm(
    val amqpHost: String,
    val amqpPort: Int,
    val anotherUser: Boolean = false,
    val user: String = "",
    val password: SecretValue? = null,
    val keepRunning: Boolean = false,
    val typed: String = "",
)

object ForwardingRequests {
    /** One queue, one request. Destination: the same queue name in the virtual host of [destinationProfile] (D5). M8-09 STUB. */
    fun of(sourceProfile: ConnectionProfile, sourceVhost: String, queue: String, destinationProfile: ConnectionProfile, form: ForwardingForm): ForwardingRequest =
        ForwardingRequest(
            ForwardingSource(sourceProfile.name, ShovelAddress.forProfile(sourceProfile), sourceVhost, queue, null),
            destinationProfile.name, "STUB", queue,
        ) // M8-09 STUB
}
