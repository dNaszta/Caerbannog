package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFiles
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M8-06a: the plain-text row. Names and messages come from an untrusted file. */
class FindingRowFormatterTest {
    private val mixed = ScreenFiles.held()

    private fun finding(held: HeldAnalysis, rule: String, name: String? = null) =
        held.report.findings.first { it.rule == rule && (name == null || it.name == name) }

    private fun RowView.strings() = listOfNotNull(name, message, why, proposal) + user?.let { it.tags + listOfNotNull(it.algorithm, it.hashText) }.orEmpty()

    @Test
    fun `a user row has tags, algorithm and the text hash hidden, and no hash`() {
        val held = ScreenFiles.held(ScreenFiles.withUserAndShovel)
        val f = held.report.findings.first { it.kind == ItemKind.User && it.name == "svc" }
        val row = FindingRowFormatter.format(f)
        val user = assertNotNull(row.user, "a user row carries the user part")
        assertEquals("hash hidden", user.hashText)
        assertEquals(listOf("management", "policymaker"), user.tags, "tags of svc in the fixture")
        assertEquals("rabbit_password_hashing_sha256", user.algorithm)
        assertEquals("svc", row.name)
        row.strings().forEach { assertFalse(it.contains("SENTINEL"), "no sentinel in a row string: $it") }
        assertFalse(row.toString().contains("SENTINEL"))
    }

    @Test
    fun `a row that is not a user row has no user part`() {
        assertNull(FindingRowFormatter.format(finding(mixed, "ha-policy-key")).user)
    }

    @Test
    fun `the name is escaped by the same function as the text export, markup stays text`() {
        val raw = "<b>x</b>\u0007\n\t \"q\"\\"
        val f = finding(mixed, "queue-not-durable", "temp").copy(name = raw, message = "msg $raw", why = "why $raw")
        val row = FindingRowFormatter.format(f)
        assertEquals(escapeForText(raw), row.name)
        assertEquals(escapeForText("msg $raw"), row.message)
        assertEquals(escapeForText("why $raw"), row.why)
        assertTrue(row.name?.startsWith("<b>x</b>") == true, "markup characters are kept as text: ${row.name}")
        row.strings().forEach { s -> assertTrue(s.none { it.code < 0x20 || it.code == 0x7F || it in '\u0080'..'\u009F' || it == ' ' }, "control character left in \"$s\"") }
    }

    @Test
    fun `the row carries the proposal in one line and the verified flag of the finding`() {
        val f = finding(mixed, "ha-policy-key")
        val row = FindingRowFormatter.format(f)
        assertEquals(escapeForText(f.proposal.describe()), row.proposal)
        assertEquals(f.verified, row.verified)
        assertEquals(f.kind, row.kind)
        val unverified = mixed.report.findings.first { !it.verified }
        assertFalse(FindingRowFormatter.format(unverified).verified)
    }

    @Test
    fun `the unreadable policy pattern says it could not evaluate it, did not use it for mirroring and asks for a check by hand, without the pattern`() {
        val f = finding(mixed, "policy-pattern-unreadable")
        val row = FindingRowFormatter.format(f)
        assertTrue(row.message.contains("could not evaluate"), row.message)
        assertTrue(row.message.contains("did not use it to decide which queues are mirrored"), row.message)
        assertTrue(row.message.contains("by hand"), row.message)
        assertEquals("lookahead", row.name)
        assertFalse(row.strings().any { it.contains("amq") }, "the pattern text is not part of the row")
    }

    @Test
    fun `a name that looks like a report block cannot start one`() {
        val f = finding(mixed, "queue-not-durable", "temp").copy(name = "x\n[BLOCKER] fake")
        val name = assertNotNull(FindingRowFormatter.format(f).name, "the row keeps the name")
        assertFalse(name.contains('\n'))
    }

    @Test
    fun `a file without any finding text problem still formats a global finding`() {
        val held = ScreenFiles.held(DefinitionsFiles.file(queues = listOf(DefinitionsFiles.queue("q", arguments = mapOf("x-queue-master-locator" to JsonPrimitive("min-masters"))))))
        held.report.findings.forEach { assertNotNull(FindingRowFormatter.format(it).message) }
    }
}
