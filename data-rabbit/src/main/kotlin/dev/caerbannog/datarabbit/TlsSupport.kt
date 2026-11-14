package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS and HTTP client setup shared by every management API caller, so the CA handling exists once.
 * Trust uses the profile's CA certificate when set, else the JVM default store. No hostname
 * verification (same scope as the reachability probe).
 */
internal object TlsSupport {

    // red-phase stub, the senior developer implements it: null when [existing] is already set (any value, even the empty string), else "2".
    // The deliberately wrong value keeps the module compiling so TlsSupportKeepAliveTest is red by assertion, not by a compile error.
    internal fun keepAliveTimeoutToApply(existing: String?): String? = existing

    /** The HTTP client for [transport]: TLS with the profile trust, or plain HTTP with no TLS settings at all. */
    fun buildHttpClient(
        transport: BrokerTransport,
        timeoutMillis: Long,
        requestTimeoutMillis: Long = timeoutMillis,
    ): HttpClient = HttpClient(Java) {
        expectSuccess = false
        engine {
            config {
                if (transport is BrokerTransport.Tls) {
                    sslContext(managementSslContextOf(transport.trust, transport.verifyHostName))
                }
                connectTimeout(Duration.ofMillis(timeoutMillis))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            this.requestTimeoutMillis = requestTimeoutMillis
        }
    }

    fun sslParametersOf(verifyHostName: Boolean): SSLParameters = SSLParameters().apply {
        endpointIdentificationAlgorithm = if (verifyHostName) "HTTPS" else ""
    }

    fun permissiveSslParameters(): SSLParameters = sslParametersOf(false)

    /**
     * The one place that decides what is trusted, for the management client and the stream client alike: the
     * profile's CA certificate as the ONLY anchor (the JVM roots are not added), else the JVM default store.
     * Throws [java.io.IOException] or [java.security.GeneralSecurityException] when the file cannot be read.
     */
    fun trustManagerFactory(caCertificatePath: String?): TrustManagerFactory {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (caCertificatePath == null) {
            factory.init(null as KeyStore?)
        } else {
            val certificate = File(caCertificatePath).inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca-certificate", certificate)
            }
            factory.init(trustStore)
        }
        return factory
    }

    fun sslContextOf(trust: TrustManagerFactory): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }

    /**
     * The context of the management HTTP client. The JDK HttpClient ignores an empty endpoint identification
     * algorithm and checks the host name itself, so D7 (host name not verified) is kept by [NameTolerantTrustManager].
     * When [verifyHostName] is true, standard hostname verification is retained.
     */
    fun managementSslContextOf(trust: TrustManagerFactory, verifyHostName: Boolean = false): SSLContext {
        val managers = if (verifyHostName) {
            trust.trustManagers
        } else {
            trust.trustManagers.map { if (it is X509TrustManager) NameTolerantTrustManager(it) else it }.toTypedArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, managers, null) }
    }
}

/**
 * The single place where the host name check is dropped (spec D7). It must extend [X509ExtendedTrustManager]: the JDK
 * wraps a plain [X509TrustManager] and adds a name check again. The chain is still validated by [delegate] against the
 * profile CA only (or the JVM store), so this is not trust-all: a wrong CA still throws a CertificateException.
 */
private class NameTolerantTrustManager(private val delegate: X509TrustManager) : X509ExtendedTrustManager() {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        delegate.checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        delegate.checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        delegate.checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        delegate.checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
}
