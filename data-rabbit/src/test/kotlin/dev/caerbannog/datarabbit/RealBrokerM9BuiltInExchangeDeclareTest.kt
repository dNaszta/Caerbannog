package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.rabbitmq.RabbitMQContainer
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * M9-01b: does the broker answer a repeated declare of an EXISTING built-in exchange (`amq.topic`, `amq.direct`) with 204 or with 401?
 * Unmeasured by S-H (which declared `amq.test`, an exchange that does not exist). Whatever it answers, the exchange form blocks the `amq.`
 * prefix; the answer decides what the refusal message may say. The request is the one of the real executor builder
 * (`MigrationRequests.requestFor`). Measured on 4.4.0: 204 for both. Result: docs/tasks/M9-00c-spike-results.md, "M9-01b: built-in exchanges".
 * Run: `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9BuiltInExchangeDeclareTest*'`.
 */
@Tag("broker")
@Testcontainers(disabledWithoutDocker = true)
class RealBrokerM9BuiltInExchangeDeclareTest {

    companion object {
        private val container: RabbitMQContainer = M9c.newBroker()
        private lateinit var api: MgmtApi
        val BUILT_IN = listOf("amq.topic" to "topic", "amq.direct" to "direct")
        const val EXPECTED_STATUS = 204 // measured on RabbitMQ 4.4.0, 2026-11-17: a no-op, not 401

        @JvmStatic @BeforeAll fun start() { container.start(); api = MgmtApi(container) }
        @JvmStatic @AfterAll fun stop() { container.stop() }
    }

    private val cleanup = M9c.Cleanup(api)

    @AfterEach fun clean() = cleanup.run()

    private fun declare(name: String, type: String): MgmtApi.Reply {
        val write = checkNotNull(MigrationRequests.requestFor(MigrationAction.DeclareExchange(name = name, type = type)))
        return api.put(write.path, write.body.toString())
    }

    @Test
    fun `The 4_x image is a 4_x broker`() = M9c.assertFourX(api)

    @Test
    fun `Control an ordinary exchange declared again with the same settings answers 204`() {
        val name = "m9-01b-control"
        assertEquals(201, declare(name, "topic").also { cleanup.exchange(name) }.status, "the first declare creates it")
        assertEquals(204, declare(name, "topic").status, "the same declare again is an idempotent no-op")
    }

    @Test
    fun `The built-in exchanges exist with their real types (the premise of the rows below)`() {
        for ((name, type) in BUILT_IN) {
            val got = api.get("/api/exchanges/%2F/$name")
            assertEquals(200, got.status, "$name must exist")
            assertEquals(type, got.obj["type"].toString().trim('"'), "$name type")
        }
    }

    @Test
    fun `S-H a repeated declare of an existing built-in exchange with its real type answers 204, not 401`() {
        val found = BUILT_IN.map { (name, type) -> declare(name, type).let { "$name -> ${it.status} ${if (it.ok) "" else it.reason().take(80)}".trim() } }
        println("M9-01b MEASURED: $found")
        for ((name, type) in BUILT_IN) {
            assertEquals(EXPECTED_STATUS, declare(name, type).status, "declare of the existing $name as $type; all answers: $found")
        }
    }
}
