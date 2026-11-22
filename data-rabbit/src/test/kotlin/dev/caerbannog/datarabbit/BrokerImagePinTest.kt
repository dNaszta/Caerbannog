package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-19: every live-broker image is an explicit patch tag kept in one place, `BrokerImages` (in `RabbitContainers.kt`).
 * A floating tag lets a new RabbitMQ release turn `main` red without a commit (found 2026-11-19 with 4.4.1).
 * Fast, no container: reads the test sources and the two dev compose files as text. The rules live in the
 * functions below and are themselves tested on samples, so a broken scan cannot pass silently.
 *
 * This file is excluded from the scan (it has to name the bad forms). Gradle runs a module's tests with the
 * module directory as the working directory, so `src/test/kotlin` and `../dev` resolve.
 */
class BrokerImagePinTest {
    data class Hit(val where: String, val image: String)

    /** Pinned means a full `major.minor.patch-management` tag. */
    private val pinnedTag = Regex("""\d+\.\d+\.\d+-management""")

    /** True for the text of a line that names the image the way Testcontainers or compose would: `rabbitmq:<tag>` or a bare quoted `"rabbitmq"`. */
    internal fun imageReferences(line: String): List<String> =
        Regex("""(?<![\w.\-/])rabbitmq:[^\s"')`,]*""").findAll(line).map { it.value }.toList() +
            Regex("""["']rabbitmq["']""").findAll(line).map { it.value.trim('"', '\'') }.toList()

    internal fun isPinned(reference: String): Boolean = reference.substringAfter("rabbitmq:", "").let { pinnedTag.matches(it) }

    private fun sourceFiles(): List<File> =
        File("src/test").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "kts", "java", "properties", "yml", "yaml", "json") }
            .filter { it.name != "BrokerImagePinTest.kt" }.toList()

    private fun composeFiles(): List<File> = listOf(File("../dev/broker/docker-compose.yml"), File("../dev/broker-3x/docker-compose.yml"))

    /** Outside `BrokerImages` every reference is a violation; inside, only a non-pinned one is. */
    internal fun violations(name: String, text: String, allowInsideObject: Boolean): List<String> {
        val lines = text.lines()
        var depth = 0
        var inside = false
        val found = mutableListOf<String>()
        lines.forEachIndexed { i, line ->
            if (allowInsideObject && !inside && Regex("""\bobject\s+BrokerImages\b""").containsMatchIn(line)) { inside = true; depth = 0 }
            val insideNow = inside
            if (inside) {
                depth += line.count { it == '{' } - line.count { it == '}' }
                if (depth <= 0 && line.contains('}')) inside = false
            }
            for (ref in imageReferences(line)) {
                if (insideNow) { if (!isPinned(ref)) found += "$name:${i + 1}: floating or untagged image `$ref` in BrokerImages" }
                else found += "$name:${i + 1}: image literal `$ref` outside BrokerImages (use BrokerImages.CURRENT_4X / LEGACY_3X / UPGRADE_4_x)"
            }
        }
        return found
    }

    @Test
    fun `no test source names a rabbitmq image outside BrokerImages, and BrokerImages has only pinned tags`() {
        val files = sourceFiles()
        assertTrue(files.size > 50 && files.any { it.name == "RabbitContainers.kt" }, "scan found ${files.size} files; the scan is broken (cwd ${File(".").absoluteFile})")
        val problems = files.flatMap { violations(it.name, it.readText(), allowInsideObject = it.name == "RabbitContainers.kt") }
        assertTrue(problems.isEmpty(), "Live-test images must be pinned in one place (M2-19), ${problems.size} problem(s):\n" + problems.take(40).joinToString("\n") +
            if (problems.size > 40) "\n... and ${problems.size - 40} more" else "")
    }

    @Test
    fun `RabbitContainers declares the BrokerImages object with the approved pins`() {
        val text = File("src/test/kotlin/dev/caerbannog/datarabbit/RabbitContainers.kt").readText()
        assertTrue(Regex("""internal\s+object\s+BrokerImages\b""").containsMatchIn(text), "RabbitContainers.kt has no `internal object BrokerImages`")
        for ((name, tag) in mapOf("LEGACY_3X" to "3.13.7", "UPGRADE_4_0" to "4.0.9", "UPGRADE_4_1" to "4.1.8", "UPGRADE_4_2" to "4.2.9", "UPGRADE_4_3" to "4.3.8")) {
            assertTrue(Regex("""\b$name\b[^\n]*"rabbitmq:$tag-management"""").containsMatchIn(text), "BrokerImages.$name must be \"rabbitmq:$tag-management\"")
        }
        assertTrue(Regex("""\bCURRENT_4X\b[^\n]*"rabbitmq:4\.4\.1-management"""").containsMatchIn(text), "BrokerImages.CURRENT_4X must default to \"rabbitmq:4.4.1-management\"")
    }

    @Test
    fun `the two dev compose files pin the same tags as BrokerImages`() {
        for ((file, expected) in listOf(composeFiles()[0] to "4.4.1-management", composeFiles()[1] to "3.13.7-management")) {
            assertTrue(file.isFile, "${file.path} not found (cwd ${File(".").absoluteFile})")
            val images = file.readLines().filter { it.trim().startsWith("image:") }.map { it.substringAfter("image:").trim() }
            assertEquals(listOf("docker.io/library/rabbitmq:$expected"), images, "${file.path} must pin the image")
        }
    }

    @Test
    fun `CURRENT_4X is the only reader of the caerbannog_brokerImage system property`() {
        val readers = sourceFiles().filter { it.name.endsWith(".kt") }.filter { "caerbannog.brokerImage" in it.readText() }.map { it.name }
        assertEquals(listOf("RabbitContainers.kt"), readers, "exactly one test file may read the override, and it is RabbitContainers.kt")
        val text = File("src/test/kotlin/dev/caerbannog/datarabbit/RabbitContainers.kt").readText()
        assertEquals(1, Regex("""System\.getProperty\("caerbannog\.brokerImage"""").findAll(text).count(), "the property must be read once")
        val gradle = File("build.gradle.kts").readText()
        assertTrue("caerbannog.brokerImage" in gradle && "brokerImage" in gradle, "build.gradle.kts must pass -PbrokerImage to brokerTest as caerbannog.brokerImage")
        val spike = Regex("""tasks\.register<Test>\("brokerSpikeTest"\)\s*\{.*?\n\}""", RegexOption.DOT_MATCHES_ALL).find(gradle)?.value ?: ""
        assertTrue("brokerImage" !in spike, "the ladder images of brokerSpikeTest are not overridable")
    }

    @Test
    fun `CURRENT_4X is the pinned image, or exactly the override when the property is set`() {
        val cls = runCatching { Class.forName("dev.caerbannog.datarabbit.BrokerImages") }.getOrNull()
        assertTrue(cls != null, "class dev.caerbannog.datarabbit.BrokerImages does not exist yet")
        val instance = cls!!.getField("INSTANCE").get(null)
        val value = (runCatching { cls.getMethod("getCURRENT_4X").invoke(instance) }.getOrNull() ?: cls.getDeclaredField("CURRENT_4X").also { it.isAccessible = true }.get(instance)) as String
        val override = System.getProperty("caerbannog.brokerImage")?.takeIf { it.isNotBlank() }
        assertEquals(override ?: "rabbitmq:4.4.1-management", value, "CURRENT_4X (override: $override)")
    }

    // ---- the rule itself, on samples ----

    @Test
    fun `the rule flags floating, short and missing tags and bare names`() {
        for (bad in listOf("rabbitmq:4-management", "rabbitmq:4.4-management", "rabbitmq:3.13-management", "rabbitmq:latest", "rabbitmq:4.4.1", "rabbitmq:4.4.1-alpine")) {
            assertTrue(!isPinned(bad), "$bad must not count as pinned")
        }
        assertEquals(listOf("rabbitmq:4-management"), imageReferences("""brokerWithPlugins("rabbitmq:4-management", "x")"""))
        assertEquals(listOf("rabbitmq"), imageReferences("""RabbitMQContainer(DockerImageName.parse("rabbitmq"))"""))
        assertEquals(listOf("rabbitmq:latest"), imageReferences("""val i = "rabbitmq:latest""""))
        assertTrue(isPinned("rabbitmq:4.4.1-management") && isPinned("rabbitmq:3.13.7-management") && isPinned("rabbitmq:4.0.9-management"))
    }

    @Test
    fun `the rule ignores the Maven coordinate and the rabbitmq_ plugin names`() {
        assertEquals(emptyList<String>(), imageReferences("""implementation("com.rabbitmq:stream-client:1.12.0")"""))
        assertEquals(emptyList<String>(), imageReferences("""brokerWithPlugins(BrokerImages.CURRENT_4X, "rabbitmq_stream", "rabbitmq_stream_management")"""))
        assertEquals(emptyList<String>(), imageReferences("""obj["rabbitmq_version"]"""))
    }

    @Test
    fun `a literal outside BrokerImages is flagged, inside it only a floating one is`() {
        val src = """
            internal object BrokerImages {
                const val A = "rabbitmq:4.4.1-management"
                const val B = "rabbitmq:4-management"
            }
            val x = "rabbitmq:4.4.1-management"
        """.trimIndent()
        val found = violations("S.kt", src, allowInsideObject = true)
        assertEquals(2, found.size, found.joinToString("\n"))
        assertTrue("S.kt:3" in found[0] && "floating" in found[0], found[0])
        assertTrue("S.kt:5" in found[1] && "outside BrokerImages" in found[1], found[1])
        assertEquals(1, violations("T.kt", "val x = \"rabbitmq:4.4.1-management\"", allowInsideObject = false).size)
    }
}
