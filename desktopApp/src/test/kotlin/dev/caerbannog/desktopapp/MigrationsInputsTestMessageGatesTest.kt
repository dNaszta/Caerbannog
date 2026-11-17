package dev.caerbannog.desktopapp

import dev.caerbannog.core.connections.BrokerReachabilityChecker
import dev.caerbannog.core.connections.ConnectionCheckResult
import dev.caerbannog.core.connections.ConnectionProfile
import dev.caerbannog.core.connections.ConnectionProfileInput
import dev.caerbannog.core.connections.PermissionCheckResult
import dev.caerbannog.core.connections.PermissionChecker
import dev.caerbannog.core.connections.SecretStore
import dev.caerbannog.core.connections.fakes.FakeConnectionGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * M9-10b step 2 (round 1): the two inputs the Migrations screen gets for "Send a test message". `hasMqtt` is the same function as `offersMqttPublishing`
 * (the held source has an MQTT port); `publishingEnabled` is the expression of `canWrite` in Main.kt (the finished permission check says publishing is
 * enabled; an unfinished check or no connection counts as no write permission). Own permission fake; `CompositionRootMigrationsTest` is not touched.
 */
class MigrationsInputsTestMessageGatesTest {
    @TempDir
    lateinit var dir: File

    private class MapSecrets : SecretStore {
        private val map = mutableMapOf<String, String>()
        override fun store(profileName: String, password: String) { map[profileName] = password }
        override fun retrieve(profileName: String): String? = map[profileName]
        override fun delete(profileName: String) { map.remove(profileName) }
    }

    private object Reachable : BrokerReachabilityChecker {
        override suspend fun check(profile: ConnectionProfile) = ConnectionCheckResult.BothReachable
    }

    /** Answers with the given publishing flag, or never answers while [gate] is not completed. */
    private class Permissions(val publishing: Boolean, val gate: CompletableDeferred<Unit>? = null) : PermissionChecker {
        override suspend fun check(profile: ConnectionProfile, password: String): PermissionCheckResult {
            gate?.await()
            return PermissionCheckResult.Checked(topologyUnlocked = true, publishingEnabled = publishing)
        }
    }

    private val gateway = FakeConnectionGateway()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun compose(permissions: PermissionChecker, mqttPort: Int?) =
        createAppComposition(
            File(dir, "connections.json"), MapSecrets(), permissionChecker = permissions, reachabilityChecker = Reachable, gateway = gateway,
            uiDispatcher = Dispatchers.Unconfined,
        ).also {
            it.viewModel.addConnection(
                ConnectionProfileInput(name = "uat-eu", host = "localhost", virtualHost = "/", username = "svc", password = "not-a-real-secret", mqttPort = mqttPort),
            )
        }

    private fun AppComposition.inputs() = migrationsInputs(viewModel.state.value)

    @Test
    fun `write permission and an MQTT port give both inputs true`() {
        val composition = compose(Permissions(publishing = true), mqttPort = 1883)
        try {
            runBlocking { composition.viewModel.connect("uat-eu") }
            val inputs = composition.inputs()
            assertEquals(true, inputs.hasMqtt, "hasMqtt")
            assertEquals(true, inputs.publishingEnabled, "publishingEnabled")
        } finally { composition.close(); scope.cancel() }
    }

    @Test
    fun `no write permission turns publishingEnabled off and leaves hasMqtt on`() {
        val composition = compose(Permissions(publishing = false), mqttPort = 1883)
        try {
            runBlocking { composition.viewModel.connect("uat-eu") }
            val inputs = composition.inputs()
            assertEquals(true, inputs.hasMqtt, "hasMqtt")
            assertEquals(false, inputs.publishingEnabled, "publishingEnabled")
        } finally { composition.close(); scope.cancel() }
    }

    @Test
    fun `a source without an MQTT port has no hasMqtt`() {
        val composition = compose(Permissions(publishing = true), mqttPort = null)
        try {
            runBlocking { composition.viewModel.connect("uat-eu") }
            val inputs = composition.inputs()
            assertEquals(false, inputs.hasMqtt, "hasMqtt")
            assertEquals(true, inputs.publishingEnabled, "publishingEnabled does not depend on the port")
        } finally { composition.close(); scope.cancel() }
    }

    @Test
    fun `an unfinished permission check counts as no write permission`() {
        val gate = CompletableDeferred<Unit>()
        val composition = compose(Permissions(publishing = true, gate = gate), mqttPort = 1883)
        try {
            scope.launch { composition.viewModel.connect("uat-eu") }
            assertEquals(false, composition.inputs().publishingEnabled, "publishingEnabled while the check runs")
        } finally { gate.complete(Unit); composition.close(); scope.cancel() }
    }

    @Test
    fun `no connection gives both inputs false`() {
        val composition = compose(Permissions(publishing = true), mqttPort = 1883)
        try {
            val inputs = composition.inputs()
            assertEquals(false, inputs.hasMqtt, "hasMqtt")
            assertEquals(false, inputs.publishingEnabled, "publishingEnabled")
        } finally { composition.close(); scope.cancel() }
    }
}
