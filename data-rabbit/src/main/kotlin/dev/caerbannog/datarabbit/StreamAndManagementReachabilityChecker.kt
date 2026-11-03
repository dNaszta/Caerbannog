package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.Endpoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Real [BrokerReachabilityChecker]: a raw TLS handshake on the stream port, plus an HTTPS call to the
 * management API via the Ktor client (`docs/spec.md`'s tech-stack table: "Management API: Ktor
 * client").
 *
 * The stream-port check uses a plain [SSLSocket] handshake rather than
 * `com.rabbitmq:stream-client`'s own connection setup - docs/tasks/M1-02-connection-profiles.md's
 * Design notes explicitly leave this choice to the developer ("or a raw SSLSocket handshake if that
 * is simpler"); a bare reachability probe does not need the stream protocol itself, which is not
 * exercised by this app until M2's real stream browsing, so no `stream-client` dependency is added
 * here for it.
 *
 * Deliberately narrow in what it proves, per the same Design notes ("did the TLS handshake and the
 * HTTP call succeed at all, nothing more"): trust-chain validation uses [ConnectionProfile.
 * caCertificatePath] when set (falling back to the JVM's default trust store otherwise), but hostname
 * verification is not enforced - checking *who* the certificate is for is a stricter check than this
 * reachability probe's job, and no scenario in `01_connections.feature` requires it.
 */
class StreamAndManagementReachabilityChecker(
    private val connectTimeoutMillis: Long = 5_000,
) : BrokerReachabilityChecker {

    override suspend fun check(profile: ConnectionProfile): ConnectionCheckResult {
        val sslContext = try {
            buildSslContext(profile.caCertificatePath)
        } catch (e: Exception) {
            return ConnectionCheckResult.EndpointUnreachable(
                Endpoint.STREAM,
                "could not load the CA certificate '${profile.caCertificatePath}': ${e.message}",
            )
        }

        checkStreamEndpoint(profile, sslContext)?.let { return it }
        return checkManagementEndpoint(profile, sslContext)
    }

    private fun checkStreamEndpoint(profile: ConnectionProfile, sslContext: SSLContext): ConnectionCheckResult? {
        try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(profile.host, profile.streamPort), connectTimeoutMillis.toInt())
                val ssl = sslContext.socketFactory.createSocket(raw, profile.host, profile.streamPort, true) as SSLSocket
                ssl.use {
                    it.sslParameters = permissiveSslParameters()
                    it.soTimeout = connectTimeoutMillis.toInt()
                    it.startHandshake()
                }
            }
            return null
        } catch (e: SSLException) {
            // Covers both a failed handshake (SSLHandshakeException, a subtype) and a peer that spoke
            // back something that was not TLS at all (e.g. a plain, non-TLS socket) - either way, the
            // app does not connect without TLS.
            return ConnectionCheckResult.TlsRefused
        } catch (e: ConnectException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, e.message ?: "connection refused")
        } catch (e: SocketTimeoutException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "timed out connecting")
        } catch (e: IOException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, e.message ?: "unreachable")
        }
    }

    private suspend fun checkManagementEndpoint(profile: ConnectionProfile, sslContext: SSLContext): ConnectionCheckResult {
        val client = buildHttpClient(sslContext)
        try {
            client.get("https://${profile.host}:${profile.managementPort}/api/overview")
        } catch (e: SSLException) {
            return ConnectionCheckResult.TlsRefused
        } catch (e: IOException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, e.message ?: "unreachable")
        } finally {
            client.close()
        }

        return checkStreamManagementPlugin(profile, sslContext)
    }

    /**
     * TODO(spike): the exact REST path the `rabbitmq_stream_management` plugin registers
     * (`/api/stream/connections`, used here) is this developer's best-effort guess from RabbitMQ's
     * management-API naming convention, not confirmed against a real broker with the plugin disabled -
     * `RealBrokerReachabilityTest` only proves the "both reachable" / plugin-enabled path. A 404
     * response here (the route does not exist at all) is read as "plugin missing" without needing
     * authentication, so this stays a reachability-shaped check, not a permission one: it is checking
     * whether the route is registered, not what the authenticated user may do. If this endpoint name
     * turns out to be wrong, only this one method needs correcting - the result shape and every
     * scenario this task proves stay the same.
     */
    private suspend fun checkStreamManagementPlugin(profile: ConnectionProfile, sslContext: SSLContext): ConnectionCheckResult {
        val client = buildHttpClient(sslContext)
        try {
            val response = client.get("https://${profile.host}:${profile.managementPort}/api/stream/connections")
            return if (response.status == HttpStatusCode.NotFound) {
                ConnectionCheckResult.ManagementPluginMissing("rabbitmq_stream_management")
            } else {
                ConnectionCheckResult.BothReachable
            }
        } catch (e: IOException) {
            // The overview call above already proved the management API itself is reachable; a
            // failure only on this second, more specific call is reported against the same endpoint.
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, e.message ?: "unreachable")
        } finally {
            client.close()
        }
    }

    private fun buildHttpClient(sslContext: SSLContext): HttpClient = HttpClient(Java) {
        expectSuccess = false
        engine {
            config {
                sslContext(sslContext)
                sslParameters(permissiveSslParameters())
                connectTimeout(Duration.ofMillis(connectTimeoutMillis))
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = this@StreamAndManagementReachabilityChecker.connectTimeoutMillis
            requestTimeoutMillis = this@StreamAndManagementReachabilityChecker.connectTimeoutMillis
        }
    }

    /** No hostname/CN verification - see this class's header for why that is out of scope here. */
    private fun permissiveSslParameters(): SSLParameters = SSLParameters().apply {
        endpointIdentificationAlgorithm = ""
    }

    private fun buildSslContext(caCertificatePath: String?): SSLContext {
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
