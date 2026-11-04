package dev.caerbannog.datarabbit

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManagerFactory

/**
 * TLS and HTTP client setup shared by every management API caller, so the CA handling exists once.
 * Trust uses the profile's CA certificate when set, else the JVM default store. No hostname
 * verification (same scope as the reachability probe).
 */
internal object TlsSupport {

    fun buildHttpClient(sslContext: SSLContext, timeoutMillis: Long): HttpClient = HttpClient(Java) {
        expectSuccess = false
        engine {
            config {
                sslContext(sslContext)
                sslParameters(permissiveSslParameters())
                connectTimeout(Duration.ofMillis(timeoutMillis))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = timeoutMillis
            requestTimeoutMillis = timeoutMillis
        }
    }

    fun permissiveSslParameters(): SSLParameters = SSLParameters().apply {
        endpointIdentificationAlgorithm = ""
    }

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

    fun buildSslContext(caCertificatePath: String?): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory(caCertificatePath).trustManagers, null)
        }
}
