package dev.caerbannog.datarabbit

import dev.caerbannog.core.connections.ConnectionFailureReason
import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.connections.SecretStore
import kotlinx.coroutines.delay
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens one held connection to a broker (spec "Connection lifecycle"): over TLS or plain as the profile says (one
 * [BrokerTransport], no fallback); the management API is probed with
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
        val transport = try {
            BrokerTransport.from(profile)
        } catch (e: IOException) {
            return OpenResult.Failed(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED)
        } catch (e: GeneralSecurityException) {
            return OpenResult.Failed(ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED)
        }
        val http = ManagementHttp(
            TlsSupport.buildHttpClient(transport, ConnectionTimeouts.MANAGEMENT_MILLIS), profile.username, password,
        )
        val requests = ManagementRequests.forProfile(profile, transport)
        val environments = StreamEnvironments(profile, password, transport)
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
        val catalog = ManagementStreamCatalog(http, requests)
        val manager = RabbitMqStreamManager(environments)
        return OpenResult.Opened(
            RabbitMqHeldConnection(
                catalog = catalog,
                offsets = RabbitMqStreamOffsets(environments),
                topology = ManagementTopologyCatalog(http, requests),
                messages = RabbitMqStreamMessageReader(environments, catalog),
                publisher = RabbitMqMessagePublisher(environments, http, requests),
                manager = manager,
                resources = listOf(environments, http),
                log = log,
                mqtt = ManagementMqttCatalog(http, requests),
                mqttPublisher = RabbitMqMqttPublisher(profile, password, transport),
                queueInspector = ManagementQueueMessageInspector(http, requests),
                // TODO(M8-02): a 3.x source keeps no executor; until the detection exists every connection gets one.
                migrationExecutor = ManagementTopologyMigrationExecutor(http, requests, manager) { delay(it) },
            ),
        )
    }

    // null means the broker let us in. A 401 "Not management user" is NOT a failure (M2-03a): visibility NONE.
    private suspend fun probe(http: ManagementHttp, requests: ManagementRequests): String? =
        when (val reply = http.get(requests.whoami())) {
            ManagementReply.CertificateRejected -> ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED
            ManagementReply.NoTlsAnswer -> ConnectionFailureReason.NO_TLS_ANSWER
            ManagementReply.Unreachable -> ConnectionFailureReason.BROKER_NOT_REACHABLE
            is ManagementReply.Response -> when {
                reply.status == 200 -> null
                reply.status == 401 && ManagementErrors.isNotManagementUser(reply.body) -> null
                reply.status == 401 || reply.status == 403 -> ConnectionFailureReason.CREDENTIALS_REFUSED
                else -> ConnectionFailureReason.BROKER_NOT_REACHABLE
            }
        }
}
