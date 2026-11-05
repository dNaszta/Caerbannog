package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens one held connection to a broker (spec "Connection lifecycle"): TLS only; the management API is probed with
 * `GET /api/whoami` (the first thing a user without the management tag or with a wrong password shows); the stream
 * `Environment` is built later, on the first offset call. The password is read from [secretStore] once, here (D6).
 * Failures the user can act on are [OpenResult.Failed] with the texts of [ConnectionFailureReason]; nothing stays
 * open after a failure; no secret, host or path is put in a reason or in [log].
 */
class RabbitMqConnectionGateway(
    private val secretStore: SecretStore,
    private val log: (String) -> Unit = {},
) : ConnectionGateway {

    override suspend fun open(profile: ConnectionProfile): OpenResult {
        val password = secretStore.retrieve(profile.name)
            ?: return OpenResult.Failed(ConnectionFailureReason.NO_PASSWORD_STORED)
        val trust = try {
            TlsSupport.trustManagerFactory(profile.caCertificatePath)
        } catch (e: IOException) {
            return OpenResult.Failed(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED)
        } catch (e: GeneralSecurityException) {
            return OpenResult.Failed(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED)
        }
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val http = ManagementHttp(
            TlsSupport.buildHttpClient(sslContext, ConnectionTimeouts.MANAGEMENT_MILLIS), profile.username, password,
        )
        val requests = ManagementRequests.forProfile(profile)
        val environments = StreamEnvironments(profile, password, BrokerTransport.Tls(trust))
        val failure = try {
            probe(http, requests)
        } catch (e: CancellationException) {
            // A cancelled Connect leaves nothing open.
            http.close()
            environments.close()
            throw e
        }
        if (failure != null) {
            http.close()
            environments.close()
            return OpenResult.Failed(failure)
        }
        return OpenResult.Opened(
            RabbitMqHeldConnection(
                catalog = ManagementStreamCatalog(http, requests),
                offsets = RabbitMqStreamOffsets(environments),
                resources = listOf(environments, http),
                log = log,
            ),
        )
    }

    // null means the broker let us in. A 401 "Not management user" is NOT a failure (M2-03a): visibility NONE.
    private suspend fun probe(http: ManagementHttp, requests: ManagementRequests): String? =
        when (val reply = http.get(requests.whoami())) {
            ManagementReply.TlsRefused -> ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED
            ManagementReply.Unreachable -> ConnectionFailureReason.BROKER_NOT_REACHABLE
            is ManagementReply.Response -> when {
                reply.status == 200 -> null
                reply.status == 401 && ManagementErrors.isNotManagementUser(reply.body) -> null
                reply.status == 401 || reply.status == 403 -> ConnectionFailureReason.CREDENTIALS_REFUSED
                else -> ConnectionFailureReason.BROKER_NOT_REACHABLE
            }
        }
}
