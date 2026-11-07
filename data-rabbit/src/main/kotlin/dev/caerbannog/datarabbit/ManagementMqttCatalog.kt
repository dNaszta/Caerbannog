package dev.caerbannog.datarabbit

import dev.caerbannog.core.mqtt.MqttCatalog
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.streams.CatalogResult

/**
 * [MqttCatalog] implementation over the RabbitMQ management API.
 */
internal class ManagementMqttCatalog(
    private val http: ManagementHttp,
    private val requests: ManagementRequests,
) : MqttCatalog {

    override suspend fun listClients(vhost: String?): CatalogResult<List<MqttClientRow>> {
        val connReply = http.get(requests.connections())
        val connBody = when (connReply) {
            is ManagementReply.Response -> if (connReply.status == 200) connReply.body else return ManagementErrors.map(connReply.status, CatalogResult.Unexpected)
            ManagementReply.Unreachable, ManagementReply.CertificateRejected, ManagementReply.NoTlsAnswer -> return CatalogResult.Unreachable
        }

        val bindingsBody = if (!vhost.isNullOrEmpty()) {
            when (val bindingsReply = http.get(requests.bindings(vhost))) {
                is ManagementReply.Response -> if (bindingsReply.status == 200) bindingsReply.body else null
                else -> null
            }
        } else null

        return ManagementJson.parseMqttClients(connBody, bindingsBody, vhost)
    }
}
