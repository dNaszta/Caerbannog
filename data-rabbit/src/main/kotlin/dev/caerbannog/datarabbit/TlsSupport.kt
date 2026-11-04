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

    fun buildSslContext(caCertificatePath: String?): SSLContext {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (caCertificatePath == null) {
            trustManagerFactory.init(null as KeyStore?)
        } else {
            val certificate = File(caCertificatePath).inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca-certificate", certificate)
            }
            trustManagerFactory.init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }
    }
}
