package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.mqtt.MqttCatalog
import dev.caerbannog.core.mqtt.MqttClientRow
import dev.caerbannog.core.streams.CatalogResult
import java.util.Collections

class FakeMqttCatalog : MqttCatalog {
    val clients: MutableList<MqttClientRow> = Collections.synchronizedList(ArrayList())
    @Volatile var result: CatalogResult<List<MqttClientRow>>? = null

    override suspend fun listClients(vhost: String?): CatalogResult<List<MqttClientRow>> {
        result?.let { return it }
        val filtered = if (vhost.isNullOrEmpty()) {
            clients.toList()
        } else {
            clients.filter { it.vhost == vhost }
        }
        return CatalogResult.Success(filtered)
    }
}
