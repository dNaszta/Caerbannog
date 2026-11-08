package dev.caerbannog.mcp

import dev.caerbannog.core.connections.ConnectionGateway
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.HeldConnection
import dev.caerbannog.core.connections.OpenResult
import dev.caerbannog.core.streams.StreamCatalog
import dev.caerbannog.core.streams.StreamOffsets
import dev.caerbannog.core.topology.TopologyCatalog
import dev.caerbannog.datarabbit.JavaKeyringSecretStore
import dev.caerbannog.datarabbit.JsonFileConnectionProfileRepository
import dev.caerbannog.datarabbit.RabbitMqConnectionGateway
import dev.caerbannog.datarabbit.StreamAndManagementReachabilityChecker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

fun main() {
    System.err.println("Caerbannog MCP Server starting over stdio...")

    val repository = JsonFileConnectionProfileRepository(
        JsonFileConnectionProfileRepository.defaultFile()
    )
    val secretStore = JavaKeyringSecretStore()
    val reachabilityChecker = StreamAndManagementReachabilityChecker()
    val gateway: ConnectionGateway = RabbitMqConnectionGateway(
        secretStore = secretStore,
        log = { System.err.println("[Gateway] $it") }
    )

    val connections = ConcurrentHashMap<String, HeldConnection>()

    fun getHeldConnection(profile: ConnectionProfile): HeldConnection {
        return connections.computeIfAbsent(profile.name) {
            runBlocking {
                when (val result = gateway.open(profile)) {
                    is OpenResult.Opened -> result.connection
                    is OpenResult.Failed -> error("Failed to connect to '${profile.name}': ${result.reason}")
                }
            }
        }
    }

    val server = McpServer(
        profileRepository = repository,
        connectionChecker = reachabilityChecker,
        streamCatalogProvider = { profile, _ -> getHeldConnection(profile).catalog },
        streamOffsetsProvider = { profile, _ -> getHeldConnection(profile).offsets },
        topologyCatalogProvider = { profile, _ -> getHeldConnection(profile).topology },
        streamMessageReaderProvider = { profile, _ -> getHeldConnection(profile).messages },
        messagePublisherProvider = { profile, _ -> getHeldConnection(profile).publisher },
        mqttCatalogProvider = { profile, _ -> getHeldConnection(profile).mqtt },
        mqttMessagePublisherProvider = { profile, _ -> getHeldConnection(profile).mqttPublisher },
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        System.err.println("Closing MCP connections...")
        for (conn in connections.values) {
            try {
                conn.close()
            } catch (e: Exception) {
                System.err.println("Error closing connection: ${e.message}")
            }
        }
    })

    server.runStdio(System.`in`, System.out)
}
