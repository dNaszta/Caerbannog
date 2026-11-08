package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionProfile
import javax.net.ssl.TrustManagerFactory

/**
 * How one profile talks to its broker, decided in ONE place ([from]) and passed to the
 * stream client, the management client and the checks, instead of an `if (profile.tls)` in each of them.
 *  - [Tls]: stream over TLS (5551), management over https; [trust] is the one trust source ([TlsSupport.trustManagerFactory]).
 *  - [Plain]: no TLS context at all, management over http; the CA path of the profile is never read.
 * [from] is the only way to build one from a profile. With TLS on and an unreadable CA it throws `IOException` or
 * `GeneralSecurityException`, as [TlsSupport.trustManagerFactory] does today; with TLS off it never throws.
 */
internal sealed interface BrokerTransport {
    /** `https` or `http`. */
    val scheme: String

    class Tls(
        val trust: TrustManagerFactory,
        val verifyHostName: Boolean = false,
    ) : BrokerTransport {
        override val scheme: String = "https"
    }

    data object Plain : BrokerTransport {
        override val scheme: String = "http"
    }

    companion object {
        fun from(profile: ConnectionProfile): BrokerTransport =
            if (profile.tls) Tls(TlsSupport.trustManagerFactory(profile.caCertificatePath), profile.verifyHostName) else Plain
    }
}
