package dev.caerbannog.devseed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * M8 hand trial: file-level check, no container. The 3.13 dev broker publishes ports that differ from the 4.x dev
 * broker (so both run side by side), its definitions parse and carry the cases the migration screen must show, and the
 * Taskfile has the three tasks. Gradle runs tests in the module directory, so the repository root is its parent.
 */
class Dev3xBrokerSetupTest {
    private val root: Path = Path.of("").toAbsolutePath().parent
    private val dir = root.resolve("dev/broker-3x")

    private fun ports(compose: Path): List<String> =
        Files.readAllLines(compose).map { it.trim() }.filter { it.startsWith("- \"") }.map { it.removePrefix("- ").trim('"') }

    private fun hostPort(mapping: String): String = mapping.substringBefore(":")

    private val definitions: JsonObject =
        Json.parseToJsonElement(Files.readString(dir.resolve("definitions.json"))).jsonObject

    private fun array(key: String): JsonArray = definitions.getValue(key).jsonArray

    private fun queue(name: String): JsonObject =
        array("queues").map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == name }

    @Test
    fun `no host port of the 3x broker clashes with the 4x dev broker`() {
        val old = ports(dir.resolve("docker-compose.yml")).map(::hostPort)
        val new = ports(root.resolve("dev/broker/docker-compose.yml")).map(::hostPort)
        assertEquals(listOf("5673", "15673", "5553"), old)
        assertTrue(old.none { it in new }, "clash between $old and $new")
    }

    @Test
    fun `the 3x broker is a 3 13 image and the 4x broker has the shovel plugins`() {
        assertTrue(Files.readString(dir.resolve("docker-compose.yml")).contains("rabbitmq:3.13-management"))
        val plugins = Files.readString(root.resolve("dev/broker/enabled_plugins"))
        assertTrue("rabbitmq_shovel," in plugins && "rabbitmq_shovel_management" in plugins, "plugins: $plugins")
    }

    @Test
    fun `definitions hold the migration cases`() {
        assertTrue(queue("orders").getValue("arguments").jsonObject.isEmpty(), "orders is a version 1 classic queue (no x-queue-version)")
        assertEquals("1", queue("invoices").getValue("arguments").jsonObject.getValue("x-queue-version").jsonPrimitive.content)
        assertTrue("x-max-priority" in queue("priority-jobs").getValue("arguments").jsonObject)
        assertTrue(array("policies").any { "ha-mode" in it.jsonObject.getValue("definition").jsonObject })
        assertTrue(array("topic_permissions").isNotEmpty())
        assertTrue(array("vhosts").size >= 2)
        assertTrue(array("users").any { it.jsonObject.getValue("tags").jsonPrimitive.content == "administrator" })
        assertTrue(array("users").size >= 3 && array("permissions").size >= 3)
        assertTrue(array("exchanges").isNotEmpty() && array("bindings").isNotEmpty())
    }

    @Test
    fun `seed script is executable and uses only the dev password`() {
        assertTrue(Files.isExecutable(dir.resolve("seed.sh")), "seed.sh must be executable")
        assertTrue("admin:dev-password" in Files.readString(dir.resolve("seed.sh")))
    }

    @Test
    fun `Taskfile has up down and reset for the 3x broker`() {
        val text = Files.readString(root.resolve("Taskfile.yml"))
        for (task in listOf("dev:up-3x", "dev:down-3x", "dev:reset-3x")) assertTrue("  $task:" in text, "task $task missing")
    }
}
