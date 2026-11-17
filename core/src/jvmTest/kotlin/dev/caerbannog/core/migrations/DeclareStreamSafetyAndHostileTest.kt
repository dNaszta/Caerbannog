package dev.caerbannog.core.migrations

import dev.caerbannog.core.connections.TopologyControlLevel
import dev.caerbannog.core.connections.fakes.FakeStreamManager
import dev.caerbannog.core.connections.fakes.FakeTopologyMigrationExecutor
import dev.caerbannog.core.json.ArgumentValue
import dev.caerbannog.core.migrations.MigrationTestSupport.fileWith
import dev.caerbannog.core.migrations.MigrationTestSupport.reason
import dev.caerbannog.core.streams.StreamManagementViewModel
import dev.caerbannog.core.topology.QueueDeclaration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9-14c step 4 (round 1): what the step 2b tests did not pin. A stream WITH settings is a topology change like any other: the dry run and the
 * runner block it without the configure permission, at the Read-only level and for another virtual host, and the executor is never called then.
 * Hostile files: one megabyte of arguments, 10,000 keys, duplicate keys, null, numbers at the Long edges, markup and RTL in keys and values.
 * The Create stream dialog still makes plain streams. A stream named like a connection queue is still a stream.
 */
class DeclareStreamSafetyAndHostileTest {

    private val action = MigrationAction.DeclareStream("audit", "/", mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-queue-leader-locator" to ArgumentValue.Text("balanced")))
    private fun file(vararg actions: MigrationAction) =
        (MigrationParser.parse(MigrationParser.exportToJson("0001", "d", actions.toList())) as MigrationValidationResult.Valid).migration

    // ---- permissions and level ----

    @Test
    fun `a stream with settings is blocked without the configure permission, at Read-only, and in another vhost, and nothing is executed`() = runBlocking {
        for ((permission, level, vhost, why) in listOf(
            Quad(false, TopologyControlLevel.FULL_CONTROL, "/", "no configure permission"),
            Quad(true, TopologyControlLevel.READ_ONLY, "/", "Read-only level"),
            Quad(true, TopologyControlLevel.FULL_CONTROL, "other", "another virtual host"),
        )) {
            val executor = FakeTopologyMigrationExecutor()
            val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor)
            val dry = runner.dryRun(file(action), permission, level, vhost = vhost)
            assertFalse(dry.canApply, "$why: the dry run says it can be applied")
            assertTrue(dry.actions.single().let { !it.allowed }, "$why: the action is allowed")
            val applied = runner.applyPending("uat-eu", listOf(file(action)), permission, level, vhost = vhost)
            assertTrue(applied !is MigrationApplyResult.Success, "$why: $applied")
            assertTrue(executor.executedActions.isEmpty(), "$why: the executor was called with ${executor.executedActions}")
        }
    }

    @Test
    fun `with the permission and a level that allows it, a stream with settings is allowed and executed once`() = runBlocking {
        val executor = FakeTopologyMigrationExecutor()
        val runner = MigrationRunner(InMemoryMigrationHistoryRepository(), executor)
        for (level in listOf(TopologyControlLevel.DECLARE_AND_EDIT, TopologyControlLevel.FULL_CONTROL)) {
            assertTrue(runner.dryRun(file(action), true, level, vhost = "/").canApply, "$level")
        }
        assertIs<MigrationApplyResult.Success>(runner.applyPending("uat-eu", listOf(file(action)), true, TopologyControlLevel.DECLARE_AND_EDIT, vhost = "/"))
        assertEquals(listOf<MigrationAction>(action), executor.executedActions)
    }

    private data class Quad(val a: Boolean, val b: TopologyControlLevel, val c: String, val d: String)

    // ---- hostile files ----

    private fun stream(arguments: String) = fileWith("""{ "action": "declareStream", "name": "audit", "arguments": $arguments }""")

    @Test
    fun `ten thousand keys are refused at the first unsupported one with a short reason, fast`() {
        val keys = (1..10_000).joinToString(",") { "\"x-key-$it\": $it" }
        val started = System.nanoTime()
        val r = reason(stream("{ $keys }"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000, "took too long")
        assertEquals("stream setting \"x-key-1\" is not supported", r)
    }

    @Test
    fun `one megabyte of text in a setting is refused with the fixed reason and is never echoed`() {
        val big = "9".repeat(1_000_000)
        assertEquals("stream setting \"x-max-age\" must be text such as 7D", reason(stream("""{ "x-max-age": "$big" }""")))
        assertEquals("stream setting \"x-queue-leader-locator\" must be balanced, client-local, random or least-leaders", reason(stream("""{ "x-queue-leader-locator": "$big" }""")))
    }

    @Test
    fun `one megabyte under an unsupported key is refused with a short reason`() {
        val r = reason(stream("""{ "${"k".repeat(1_000_000)}": 1 }"""))
        assertTrue(r.length < 100, "reason of ${r.length} characters")
        assertEquals("stream setting is not supported", r)
    }

    @Test
    fun `a key that is not echoable is refused without the key, markup and RTL included`() {
        for (key in listOf("<b>x</b>", "x-\u202Emax-age", "x max age", "\u0645\u0627\u0643\u0633", "x-max-age\u0000", "\uD83D\uDC07")) {
            val escaped = key.replace("\u0000", "\\u0000").replace("\u202E", "\\u202E")
            val r = reason(stream("""{ "$escaped": 1 }"""))
            assertEquals("stream setting is not supported", r, "for key ${key.length} characters")
        }
    }

    @Test
    fun `numbers at the Long edges, the edge is accepted, one beyond is a decimal and is refused`() {
        val ok = MigrationParser.parse(fileWith("""{ "action": "declareStream", "name": "audit", "arguments": { "x-max-length-bytes": ${Long.MAX_VALUE} } }"""))
        assertIs<MigrationValidationResult.Valid>(ok)
        for (v in listOf("9223372036854775808", "-9223372036854775808", "0", "-0", "1e18", "1.0", "0.5")) {
            assertEquals("stream setting \"x-max-length-bytes\" must be a whole number above 0", reason(stream("""{ "x-max-length-bytes": $v }""")), v)
        }
    }

    @Test
    fun `duplicate keys never crash the parser, the file is refused or the last value wins, never both`() {
        val result = MigrationParser.parse(stream("""{ "x-max-age": "7D", "x-max-age": "1D" }"""))
        when (result) {
            is MigrationValidationResult.Invalid -> assertTrue(result.reason.isNotBlank())
            is MigrationValidationResult.Valid -> assertEquals(mapOf("x-max-age" to ArgumentValue.Text("1D")), (result.migration.up.single() as MigrationAction.DeclareStream).arguments)
        }
    }

    @Test
    fun `null values are refused with the key's own reason`() {
        assertEquals("stream setting \"x-max-age\" must be text such as 7D", reason(stream("""{ "x-max-age": null }""")))
        assertEquals("stream setting \"x-max-length-bytes\" must be a whole number above 0", reason(stream("""{ "x-max-length-bytes": null }""")))
        assertEquals("stream setting \"x-queue-leader-locator\" must be balanced, client-local, random or least-leaders", reason(stream("""{ "x-queue-leader-locator": null }""")))
    }

    @Test
    fun `an unsupported key hides behind a valid one in no order`() {
        assertEquals("stream setting \"x-foo\" is not supported", reason(stream("""{ "x-max-age": "7D", "x-foo": 1 }""")))
        assertEquals("stream setting \"x-foo\" is not supported", reason(stream("""{ "x-foo": 1, "x-max-age": "7D" }""")))
        assertEquals("stream setting \"x-queue-type\" is not supported", reason(stream("""{ "x-queue-type": "stream", "x-max-age": "7D" }""")))
    }

    // ---- the export keeps streams named like a connection queue ----

    @Test
    fun `a stream named like a connection queue is still exported, the prefix rule is for queues`() {
        val streams = listOf("amq.gen-abc", "mqtt-subscription-x", "conn-1").map { QueueDeclaration(it, "/", "stream", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"))) }
        val result = (TopologyExport.actions("/", streams, emptyList(), emptyList(), emptyList(), emptyList()) as TopologyExportOutcome.Written).result
        assertEquals(setOf("amq.gen-abc", "mqtt-subscription-x", "conn-1"), result.actions.filterIsInstance<MigrationAction.DeclareStream>().map { it.name }.toSet())
        assertNull(result.note)
    }

    @Test
    fun `five thousand streams with settings export in one go, all carry their settings, the note is empty`() {
        val streams = (1..5000).map { QueueDeclaration("s-$it", "/", "stream", arguments = mapOf("x-max-age" to ArgumentValue.Text("7D"), "x-queue-leader-locator" to ArgumentValue.Text("balanced"))) }
        val result = (TopologyExport.actions("/", streams, emptyList(), emptyList(), emptyList(), emptyList()) as TopologyExportOutcome.Written).result
        assertEquals(5000, result.actions.size)
        assertTrue(result.actions.all { (it as MigrationAction.DeclareStream).arguments.size == 2 })
        assertNull(result.note)
        val text = MigrationParser.exportToJson("0001", "export", result.actions)
        assertEquals(result.actions, (MigrationParser.parse(text) as MigrationValidationResult.Valid).migration.up, "the file parses back to the same 5000 actions")
    }

    // ---- the Create stream dialog still makes plain streams ----

    @Test
    fun `the Create stream dialog creates a plain stream with no settings`() = runBlocking<Unit> {
        val manager = FakeStreamManager()
        val vm = StreamManagementViewModel(
            manager = manager, connectionName = "uat-eu", vhost = "/", configurePermission = true, existingStreams = { emptySet<String>() },
            consumerCounts = { 0 }, onStreamCreated = {}, onStreamDeleted = {}, scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
        vm.openCreate(); vm.setCreateName("fresh")
        assertTrue(vm.confirmCreate())
        assertEquals(listOf(Triple("/", "fresh", dev.caerbannog.core.streams.StreamSettings.NONE)), manager.createdWithSettings)
    }
}
