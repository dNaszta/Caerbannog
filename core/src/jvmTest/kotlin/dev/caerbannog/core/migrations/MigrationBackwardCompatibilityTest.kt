package dev.caerbannog.core.migrations

import dev.caerbannog.core.migrations.fakes.MigrationFixtures
import dev.caerbannog.core.json.ArgumentValue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8-04: the whole risk of widening the format is that an old file changes meaning or checksum. The checksum is computed over the raw
 * text; the golden file and its checksum were fixed before the change (fixtures/m8-04, MigrationFixtures).
 */
class MigrationBackwardCompatibilityTest {

    private val oldText = MigrationFixtures.read(MigrationFixtures.OLD_FORMAT)

    private fun parsed(text: String): MigrationFile =
        (MigrationParser.parse(text) as? MigrationValidationResult.Valid)?.migration
            ?: error("the golden old-format file must stay valid: ${MigrationParser.parse(text)}")

    @Test
    fun `the golden old-format file parses to the same actions as before`() {
        assertEquals(MigrationFixtures.defaultOnlyActions(), parsed(oldText).up)
    }

    @Test
    fun `every action of the old file keeps every new field at its default`() {
        val up = parsed(oldText).up
        assertTrue(up.filterIsInstance<MigrationAction.DeclareQueue>().all { it.arguments.isEmpty() && !it.autoDelete })
        assertTrue(up.filterIsInstance<MigrationAction.DeclareExchange>().all { it.arguments.isEmpty() && !it.autoDelete && !it.internal })
        assertTrue(
            up.filterIsInstance<MigrationAction.DeclareBinding>().all {
                it.destinationType == dev.caerbannog.core.topology.BindingDestinationType.QUEUE && it.arguments.isEmpty()
            },
        )
        assertTrue(up.filterIsInstance<MigrationAction.DeclarePolicy>().all { it.definition.isEmpty() && it.applyTo == "all" && it.priority == 0 })
    }

    @Test
    fun `the checksum of the golden old-format file is the one computed before the change`() {
        assertEquals(MigrationFixtures.OLD_FORMAT_CHECKSUM, parsed(oldText).checksum)
        assertEquals(MigrationFixtures.OLD_FORMAT_CHECKSUM, MigrationParser.computeChecksum(oldText))
    }

    @Test
    fun `the checksum ignores line-ending style and outer whitespace as before`() {
        assertEquals(MigrationFixtures.OLD_FORMAT_CHECKSUM, MigrationParser.computeChecksum("\n" + oldText.replace("\n", "\r\n") + "\n\n"))
    }

    @Test
    fun `the checksum is over the raw text, not over the re-exported actions`() {
        val file = parsed(oldText)
        val reExported = MigrationParser.exportToJson(file.version, file.description, file.up)
        assertTrue(reExported != oldText, "the fixture must differ from its re-export, or this test proves nothing")
        assertEquals(MigrationFixtures.OLD_FORMAT_CHECKSUM, file.checksum, "checksum of the raw text")
        assertTrue(MigrationParser.computeChecksum(reExported) != file.checksum)
    }

    @Test
    fun `exporting actions that use no new feature is byte-identical to the export of the first release`() {
        val exported = MigrationParser.exportToJson("0001", "Exported", MigrationFixtures.defaultOnlyActions())
        assertEquals(MigrationFixtures.read(MigrationFixtures.EXPORT_GOLDEN), exported)
        assertEquals(
            MigrationParser.computeChecksum(MigrationFixtures.read(MigrationFixtures.EXPORT_GOLDEN)),
            MigrationParser.computeChecksum(exported),
        )
    }

    @Test
    fun `an applied old file is Applied and not Modified after the change`() = runBlocking {
        val files = InMemoryMigrationFileRepository()
        val history = InMemoryMigrationHistoryRepository()
        files.importFile(oldText)
        // What the first release recorded when it applied the file: the checksum of the raw text, fixed above.
        history.recordApplied("uat-eu", "0001", MigrationFixtures.OLD_FORMAT_CHECKSUM)
        val entries = MigrationRunner(history, NoopExecutor).resolveStatuses("uat-eu", files.listFiles())
        assertEquals(MigrationStatus.Applied(MigrationFixtures.OLD_FORMAT_CHECKSUM), entries.single().status)
    }

    @Test
    fun `an old hand-written file with a stray arguments object now parses it and keeps its checksum`() {
        val text = MigrationTestSupport.fileWith("""{ "action": "declareQueue", "name": "orders", "arguments": { "x-delivery-limit": 5 } }""")
        val file = parsed(text)
        val queue = file.up.single() as MigrationAction.DeclareQueue
        assertEquals(mapOf("x-delivery-limit" to ArgumentValue.Whole(5)), queue.arguments)
        assertEquals(MigrationParser.computeChecksum(text), file.checksum)
        assertEquals("3613cc8d2b6418f4", file.checksum, "FNV-1a of the raw text, computed independently of the parser")
    }

    private object NoopExecutor : TopologyMigrationExecutor {
        override suspend fun execute(action: MigrationAction): ActionResult = ActionResult.Success
    }
}
