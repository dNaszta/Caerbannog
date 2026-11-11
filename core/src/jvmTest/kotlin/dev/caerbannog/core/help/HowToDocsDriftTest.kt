package dev.caerbannog.core.help

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** M8-14: the in-app how-tos are the short version of `docs/MIGRATING_FROM_3X.md`; renaming a button in one and not the other fails here. */
class HowToDocsDriftTest {
    private val doc: String = listOf("../docs/MIGRATING_FROM_3X.md", "docs/MIGRATING_FROM_3X.md").map(::File).firstOrNull { it.exists() }?.readText()
        ?: throw AssertionError("docs/MIGRATING_FROM_3X.md not found from ${File(".").absoluteFile}")

    /** The text of the `## <heading>` section, up to the next `## ` heading, or null when the heading does not exist. */
    private fun section(heading: String): String? {
        val start = doc.indexOf("\n## $heading\n").takeIf { it >= 0 } ?: return null
        val from = start + 1
        val next = doc.indexOf("\n## ", from + 3)
        return if (next < 0) doc.substring(from) else doc.substring(from, next)
    }

    @Test
    fun `every how-to names doc sections that exist`() {
        HowToTexts.all.forEach { h ->
            assertTrue(h.docHeadings.isNotEmpty(), "${h.id} names no doc heading")
            h.docHeadings.forEach { assertTrue(section(it) != null, "${h.id}: no \"## $it\" in the guide") }
        }
    }

    @Test
    fun `every key phrase of a how-to is in its doc sections and in its own steps`() {
        HowToTexts.all.forEach { h ->
            val docText = h.docHeadings.mapNotNull(::section).joinToString("\n")
            val guiText = (h.needs + h.steps + h.ifWrong).joinToString("\n")
            assertTrue(h.keyPhrases.isNotEmpty(), "${h.id} has no key phrases")
            h.keyPhrases.forEach { phrase ->
                assertTrue(docText.contains(phrase), "${h.id}: \"$phrase\" is not in the guide sections ${h.docHeadings}")
                assertTrue(guiText.contains(phrase), "${h.id}: \"$phrase\" is not in the in-app how-to")
            }
        }
    }
}
