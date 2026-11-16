package dev.caerbannog.datarabbit

import dev.caerbannog.core.migrations.MigrationAction
import dev.caerbannog.core.migrations.MigrationParser
import dev.caerbannog.core.migrations.NowProvider
import dev.caerbannog.core.planner.DraftLoad
import dev.caerbannog.core.planner.DraftMoveResult
import dev.caerbannog.core.planner.TopologyPlan
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M9-02: `planner-draft.json`, the one draft of this machine (spec A3), on a temp folder. Contract (class `FilePlanDraftStore`, package
 * `dev.caerbannog.datarabbit`, implements `core.planner.PlanDraftStore`):
 *   FilePlanDraftStore(file: Path, now: NowProvider, mover: FileMover = NioFileMover, zone: ZoneId = ZoneId.systemDefault())
 *   companion fun defaultFile(): Path    // ~/.caerbannog/planner-draft.json
 * The text is `MigrationParser.exportToJson("draft", description, actions)` and is read with `MigrationParser.parse` (no new format). A missing
 * file is no draft. A file that exists and does not parse is [DraftLoad.Unreadable] with a fixed reason (never file content) and is never written
 * over, cleared or deleted: the only way out is `moveAside`, which renames it (timestamp name, never deleting, never overwriting). Writes go
 * through a temp file and the mover, so a crash leaves the old bytes. No secret is in a draft (A8), so the file has the default permissions.
 */
class FilePlanDraftStoreTest {

    @TempDir
    lateinit var root: File

    private val zone: ZoneId = ZoneId.of("UTC")
    private val file: Path get() = root.toPath().resolve("data/planner-draft.json")
    private val clock = LocalDateTime.of(2026, 11, 16, 10, 0, 0)
    private val now = NowProvider { clock.atZone(zone).toInstant().toEpochMilli() }
    private val crash = FileMover { _, _ -> throw IOException("simulated crash between the temp file and the move") }

    private fun store(mover: FileMover = NioFileMover) = FilePlanDraftStore(file, now, mover, zone)
    private fun put(text: String) { Files.createDirectories(file.parent); Files.write(file, text.toByteArray(Charsets.UTF_8)) }
    private fun putBytes(bytes: ByteArray) { Files.createDirectories(file.parent); Files.write(file, bytes) }
    private fun bytes() = Files.readAllBytes(file)
    private fun names(): Set<String> = Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList().toSet() }
    private fun load(s: FilePlanDraftStore = store()) = runBlocking { s.load() }
    private fun save(plan: TopologyPlan, s: FilePlanDraftStore = store()) = runBlocking { s.save(plan) }

    private val queue = MigrationAction.DeclareQueue("orders", "quorum", vhost = "/orders")
    private val exchange = MigrationAction.DeclareExchange("events", "direct", vhost = "/orders")
    private val plan = TopologyPlan.of("Add orders", listOf(queue, exchange), defaultVhost = "/")

    // ---- round trip ----

    @Test
    fun `a missing file is no draft and reading creates nothing`() {
        assertEquals(DraftLoad.None, load())
        assertFalse(Files.exists(file))
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `a saved plan round-trips through a new instance`() {
        assertTrue(save(plan))
        val loaded = assertIs<DraftLoad.Loaded>(load(store())).plan
        assertEquals("Add orders", loaded.description)
        assertEquals(listOf(queue, exchange), loaded.steps.map { it.action })
        assertEquals(listOf(1, 2), loaded.steps.map { it.id })
        assertEquals("/orders", loaded.vhost, "the vhost of a draft with steps is the first action's")
    }

    @Test
    fun `the file is the migration text with the version draft and nothing else`() {
        save(plan)
        assertEquals(MigrationParser.exportToJson("draft", "Add orders", listOf(queue, exchange)), String(bytes(), Charsets.UTF_8))
    }

    @Test
    fun `save creates its own parent folder`() {
        assertFalse(Files.exists(file.parent))
        assertTrue(save(plan))
        assertTrue(Files.isRegularFile(file))
    }

    @Test
    fun `a description alone round-trips and has no steps`() {
        assertTrue(save(TopologyPlan(description = "later", vhost = "/orders")))
        val loaded = assertIs<DraftLoad.Loaded>(load()).plan
        assertEquals("later", loaded.description)
        assertEquals(emptyList(), loaded.steps)
    }

    @Test
    fun `a second save replaces the first and leaves no temp file`() {
        save(plan)
        save(TopologyPlan.of("two", listOf(queue), defaultVhost = "/"))
        assertEquals("two", assertIs<DraftLoad.Loaded>(load()).plan.description)
        assertEquals(setOf("planner-draft.json"), names())
    }

    @Test
    fun `unicode and quotes in a description and names survive`() {
        val tricky = TopologyPlan.of("naïve \"quoted\" \\ line\nbreak ✓", listOf(MigrationAction.DeclareQueue("ordérs-ü", "quorum", "/")), defaultVhost = "/")
        save(tricky)
        assertEquals(tricky.description, assertIs<DraftLoad.Loaded>(load()).plan.description)
        assertEquals(tricky.steps.map { it.action }, assertIs<DraftLoad.Loaded>(load()).plan.steps.map { it.action })
    }

    // ---- atomic write ----

    @Test
    fun `a crash between the temp file and the move leaves the old bytes, no temp file, and answers false`() {
        save(plan)
        val before = bytes()
        assertFalse(save(TopologyPlan.of("new", listOf(queue), defaultVhost = "/"), store(crash)))
        assertEquals(before.toList(), bytes().toList())
        assertEquals(setOf("planner-draft.json"), names())
    }

    @Test
    fun `a crash on the first write leaves no file at all`() {
        assertFalse(save(plan, store(crash)))
        assertEquals(DraftLoad.None, load())
        assertEquals(emptySet(), names())
    }

    @Test
    fun `a parent that is a file answers false and does not throw`() {
        Files.createDirectories(file.parent.parent)
        Files.write(file.parent, "x".toByteArray())
        assertFalse(save(plan))
    }

    // ---- clear ----

    @Test
    fun `clear removes the draft and clearing nothing is fine`() {
        save(plan)
        runBlocking { store().clear() }
        assertFalse(Files.exists(file))
        runBlocking { store().clear() }
        assertEquals(DraftLoad.None, load())
    }

    // ---- unreadable shapes ----

    private val sentinelHash = "S3NTINEL-HASH-77ab"
    private val userBody: ByteArray = MigrationParser.exportToJson(
        "draft", "by hand",
        listOf(MigrationAction.DeclareUser("svc", dev.caerbannog.core.json.SecretValue.of(sentinelHash), "rabbit_password_hashing_sha256")),
    ).toByteArray()

    private val unreadableBodies: List<Pair<String, ByteArray>> = listOf(
        "an empty file" to ByteArray(0),
        "not JSON" to "this is not json".toByteArray(),
        "truncated JSON" to "{\"version\": \"draft\", \"up\": [".toByteArray(),
        "a JSON array" to "[1, 2]".toByteArray(),
        "a JSON string" to "\"text\"".toByteArray(),
        "no up array" to "{\"version\": \"draft\", \"description\": \"d\"}".toByteArray(),
        "an action that is not supported" to "{\"version\": \"draft\", \"up\": [{\"action\": \"declareNothing\"}]}".toByteArray(),
        "an up that is not an array" to "{\"version\": \"draft\", \"up\": 7}".toByteArray(),
        "binary bytes" to byteArrayOf(0, 1, 2, -1, -2, -3, 127),
        "invalid UTF-8 inside JSON" to byteArrayOf('{'.code.toByte(), '"'.code.toByte(), -61, '"'.code.toByte(), '}'.code.toByte()),
        "a declareUser with a password hash" to userBody,
        "a declareVhost" to "{\"version\": \"draft\", \"up\": [{\"action\": \"declareVhost\", \"name\": \"orders\"}]}".toByteArray(),
        "nested too deep" to ("[".repeat(5000) + "]".repeat(5000)).toByteArray(),
    )

    @Test
    fun `every unreadable shape is reported with the path and a fixed reason that holds no content, and the bytes are kept`() {
        unreadableBodies.forEach { (what, body) ->
            putBytes(body)
            val result = load()
            val unreadable = assertIs<DraftLoad.Unreadable>(result, what)
            assertEquals(file.toString(), unreadable.path, what)
            assertTrue(unreadable.reason.isNotBlank(), what)
            assertTrue(unreadable.reason.length < 80 && !unreadable.reason.contains("declareNothing") && !unreadable.reason.contains("draft"), "$what: the reason must not echo content: ${unreadable.reason}")
            assertFalse(unreadable.reason.contains(sentinelHash), "$what: the reason must not hold the hash")
            assertEquals(body.toList(), bytes().toList(), "$what: reading must not change the file")
        }
    }

    @Test
    fun `a draft with an access action says the file holds password hashes`() {
        putBytes(userBody)
        assertEquals(
            DraftLoad.Unreadable(file.toString(), "This file holds password hashes. Edit it outside the app."),
            load(),
        )
    }

    @Test
    fun `save never writes over an unreadable file and says false`() {
        put("garbage")
        assertFalse(save(plan))
        assertEquals("garbage", String(bytes()))
        assertEquals(setOf("planner-draft.json"), names())
    }

    @Test
    fun `clear never deletes an unreadable file`() {
        put("garbage")
        runBlocking { store().clear() }
        assertEquals("garbage", String(bytes()))
    }

    // ---- aside ----

    @Test
    fun `the aside name is the file name, unreadable and the timestamp`() {
        assertEquals("planner-draft.json.unreadable-20261116-100000", store().asideFileName())
    }

    @Test
    fun `a name that exists gets a counter`() {
        Files.createDirectories(file.parent)
        Files.write(file.resolveSibling("planner-draft.json.unreadable-20261116-100000"), "older".toByteArray())
        assertEquals("planner-draft.json.unreadable-20261116-100000-2", store().asideFileName())
        Files.write(file.resolveSibling("planner-draft.json.unreadable-20261116-100000-2"), "older too".toByteArray())
        assertEquals("planner-draft.json.unreadable-20261116-100000-3", store().asideFileName())
    }

    @Test
    fun `moving aside keeps the bytes under the new name and the draft is then none`() {
        val body = byteArrayOf(0, 1, 2, -1, 80)
        putBytes(body)
        val moved = assertIs<DraftMoveResult.Moved>(runBlocking { store().moveAside() })
        assertEquals("planner-draft.json.unreadable-20261116-100000", moved.fileName)
        assertEquals(body.toList(), Files.readAllBytes(file.resolveSibling(moved.fileName)).toList())
        assertFalse(Files.exists(file))
        assertEquals(DraftLoad.None, load())
    }

    @Test
    fun `moving aside never overwrites an earlier aside file`() {
        Files.createDirectories(file.parent)
        val older = file.resolveSibling("planner-draft.json.unreadable-20261116-100000")
        Files.write(older, "older".toByteArray())
        put("garbage")
        val moved = assertIs<DraftMoveResult.Moved>(runBlocking { store().moveAside() })
        assertEquals("planner-draft.json.unreadable-20261116-100000-2", moved.fileName)
        assertEquals("older", String(Files.readAllBytes(older)))
        assertEquals("garbage", String(Files.readAllBytes(file.resolveSibling(moved.fileName))))
    }

    @Test
    fun `a failed move says Failed and the file stays where it was`() {
        put("garbage")
        val result = runBlocking { store(crash).moveAside() }
        assertIs<DraftMoveResult.Failed>(result)
        assertTrue(result.reason.isNotBlank())
        assertEquals("garbage", String(bytes()))
        assertEquals(setOf("planner-draft.json"), names())
    }

    @Test
    fun `a readable draft is not moved aside, and neither is a missing one`() {
        save(plan)
        assertIs<DraftMoveResult.Failed>(runBlocking { store().moveAside() })
        assertTrue(Files.exists(file), "a good draft must not be taken away")
        Files.delete(file)
        assertIs<DraftMoveResult.Failed>(runBlocking { store().moveAside() })
    }

    // ---- where it lives, and what it does not know ----

    @Test
    fun `the default file is planner-draft json in the app's own folder, next to settings json`() {
        val default = FilePlanDraftStore.defaultFile()
        assertEquals("planner-draft.json", default.fileName.toString())
        assertEquals(".caerbannog", default.parent.fileName.toString())
        assertEquals(JsonFileAppSettings.defaultFile().parentFile.toPath(), default.parent)
    }

    @Test
    fun `the draft store names no management client and no migrations folder`() {
        val source = File("src/main/kotlin/dev/caerbannog/datarabbit/FilePlanDraftStore.kt")
        assertTrue(source.isFile, "cannot find ${source.absolutePath}; the guard must not pass by finding nothing")
        val code = source.readLines().joinToString("\n") { it.substringBefore("//") }
        listOf("ManagementHttp", "HttpClient", "FolderMigrationFileRepository", "MigrationFileRepository", "BrokerTransport").forEach {
            assertFalse(code.contains(it), "FilePlanDraftStore must not name $it (the draft is not a migration file and not a broker call)")
        }
    }

    @Test
    fun `concurrent saves and loads never see half a file`() {
        save(plan)
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val writer = Thread {
            repeat(200) { i -> save(TopologyPlan.of("v$i", listOf(queue), defaultVhost = "/")) }
        }
        val reader = Thread {
            repeat(400) { if (load() !is DraftLoad.Loaded) failures += "a load did not see a whole draft" }
        }
        writer.start(); reader.start()
        writer.join(60_000); reader.join(60_000)
        assertEquals(emptyList(), failures.toList())
    }
}
