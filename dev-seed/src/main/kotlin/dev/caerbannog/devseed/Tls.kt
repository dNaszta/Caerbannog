package dev.caerbannog.devseed

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Trust for the dev broker: the given CA certificate as the only anchor, else the JVM default store. Host names are
 * not checked (same rule as the app, decision D7 (a)); the broker is a local throwaway.
 */
internal object Tls {
    fun trustManagerFactory(caPath: String?): TrustManagerFactory {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        if (caPath == null) {
            factory.init(null as KeyStore?)
        } else {
            val certificate = File(caPath).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("dev-ca", certificate)
            }
            factory.init(store)
        }
        return factory
    }

    fun sslContext(caPath: String?): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, trustManagerFactory(caPath).trustManagers, null) }
}
