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
    /**
     * One queue, one request. Destination: the same queue name in the virtual host of [destinationProfile] (D5). The source address carries the
     * virtual host of the queue ([sourceVhost]), not the one of the profile. The typed password is used only while [ForwardingForm.anotherUser] is on.
     */
    fun of(sourceProfile: ConnectionProfile, sourceVhost: String, queue: String, destinationProfile: ConnectionProfile, form: ForwardingForm): ForwardingRequest {
        val credentials = form.password?.takeIf { form.anotherUser }?.let { ShovelSourceCredentials(form.user, it) }
        val address = ShovelAddress.forProfile(sourceProfile.copy(virtualHost = sourceVhost), form.amqpHost, form.amqpPort, credentials)
        return ForwardingRequest(
            ForwardingSource(sourceProfile.name, address, sourceVhost, queue, credentials),
            destinationProfile.name, destinationProfile.virtualHost, queue,
            if (form.keepRunning) SrcDeleteAfter.NEVER else SrcDeleteAfter.QUEUE_LENGTH,
        )
    }
}
