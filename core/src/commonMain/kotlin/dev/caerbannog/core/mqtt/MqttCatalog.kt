package dev.caerbannog.core.mqtt

import dev.caerbannog.core.streams.CatalogResult

/**
 * Read-only view of MQTT client connections and subscriptions in RabbitMQ, served by the management API.
 */
interface MqttCatalog {
    /**
     * Lists active MQTT client connections and their subscriptions on [vhost].
     * If [vhost] is null, lists across all vhosts visible to the user.
     */
    suspend fun listClients(vhost: String? = null): CatalogResult<List<MqttClientRow>>
}

object UnavailableMqttCatalog : MqttCatalog {
    override suspend fun listClients(vhost: String?): CatalogResult<List<MqttClientRow>> =
        CatalogResult.Success(emptyList())
}
