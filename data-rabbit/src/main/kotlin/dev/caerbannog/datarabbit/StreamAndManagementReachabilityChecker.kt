package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.Endpoint
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * Real [BrokerReachabilityChecker]: a raw TLS handshake on the stream port (a TCP connect when the profile has TLS off), plus an HTTPS (or plain HTTP) call to the
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
        val transport = try {
            BrokerTransport.from(profile)
        } catch (e: Exception) {
            return ConnectionCheckResult.EndpointUnreachable(
                Endpoint.STREAM,
                "could not load the CA certificate '${profile.caCertificatePath}': ${e.message}",
            )
        }

        checkStreamEndpoint(profile, transport)?.let { return it }
        return checkManagementEndpoint(profile, transport)
    }

    private fun checkStreamEndpoint(profile: ConnectionProfile, transport: BrokerTransport): ConnectionCheckResult? {
        try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(profile.host, profile.streamPort), connectTimeoutMillis.toInt())
                // Plain: the TCP connect is the whole probe. Like the TLS handshake it never speaks the stream
                // protocol and sends no credential, so a silent plain server counts as reachable.
                if (transport is BrokerTransport.Tls) {
                    val ssl = TlsSupport.sslContextOf(transport.trust).socketFactory
                        .createSocket(raw, profile.host, profile.streamPort, true) as SSLSocket
                    ssl.use {
                        it.sslParameters = TlsSupport.permissiveSslParameters()
                        it.soTimeout = connectTimeoutMillis.toInt()
                        it.startHandshake()
                    }
                }
            }
            return null
        } catch (e: SSLException) {
            // M2-14f: distinguish untrusted certificate from plain endpoint / no TLS answer.
            return classifyTlsRefused(e)
        } catch (e: ConnectException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, e.message ?: "connection refused")
        } catch (e: SocketTimeoutException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, "timed out connecting")
        } catch (e: IOException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.STREAM, e.message ?: "unreachable")
        }
    }

    private fun classifyTlsRefused(error: SSLException): ConnectionCheckResult =
        if (TlsExceptionClassifier.hasCertificateCause(error)) ConnectionCheckResult.CertificateRejected
        else ConnectionCheckResult.TlsRefused

    private suspend fun checkManagementEndpoint(profile: ConnectionProfile, transport: BrokerTransport): ConnectionCheckResult {
        val client = TlsSupport.buildHttpClient(transport, connectTimeoutMillis)
        val authority = ManagementRequests.formatAuthority(profile.host, profile.managementPort)
        try {
            client.get("${transport.scheme}://$authority/api/overview")
        } catch (e: SSLException) {
            return classifyTlsRefused(e)
        } catch (e: IOException) {
            return ConnectionCheckResult.EndpointUnreachable(Endpoint.MANAGEMENT, e.message ?: "unreachable")
        } finally {
            client.close()
        }

        return checkStreamManagementPlugin(profile, transport)
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
    private suspend fun checkStreamManagementPlugin(profile: ConnectionProfile, transport: BrokerTransport): ConnectionCheckResult {
        val client = TlsSupport.buildHttpClient(transport, connectTimeoutMillis)
        val authority = ManagementRequests.formatAuthority(profile.host, profile.managementPort)
        try {
            val response = client.get("${transport.scheme}://$authority/api/stream/connections")
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
}
