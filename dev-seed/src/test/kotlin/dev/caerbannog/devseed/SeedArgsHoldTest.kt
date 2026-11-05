package dev.caerbannog.devseed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** M2-06-F-A (docs/tasks/M2-06-f1-dev-seed-hold.md), tests first. `--hold` is a switch without a value, off by default. */
class SeedArgsHoldTest {
    private fun parse(vararg args: String) = SeedArgs.parse(args.toList())

    @Test
    fun `hold is off when the flag is absent`() = assertFalse(parse("--password", "pw").hold)

    @Test
    fun `hold is a switch without a value, first, middle or last`() {
        for (args in listOf(
            listOf("--hold", "--password", "pw", "--messages", "5"),
            listOf("--password", "pw", "--hold", "--messages", "5"),
            listOf("--password", "pw", "--messages", "5", "--hold"),
        )) {
            val a = SeedArgs.parse(args)
            assertTrue(a.hold, "hold for $args")
            assertEquals("pw", a.password)
            assertEquals(5, a.messages)
        }
    }

    @Test
    fun `hold works with no-tls and streams and keeps their meaning`() {
        val a = parse("--password", "pw", "--no-tls", "--hold", "--streams", "a,b")
        assertTrue(a.hold)
        assertFalse(a.tls)
        assertEquals(5552, a.streamPort)
        assertEquals(listOf("a", "b"), a.streams)
    }

    @Test
    fun `hold given twice is fine`() = assertTrue(parse("--password", "pw", "--hold", "--hold").hold)

    @Test
    fun `toString shows hold and never the password`() {
        val text = parse("--password", "s3cret-pw", "--hold").toString()
        assertTrue("hold=true" in text, "no hold in $text")
        assertFalse("s3cret-pw" in text, "password leaked in $text")
        assertTrue("hold=false" in parse("--password", "pw").toString())
    }
}
