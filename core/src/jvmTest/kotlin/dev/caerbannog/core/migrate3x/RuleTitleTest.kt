package dev.caerbannog.core.migrate3x

import kotlin.test.Test
import kotlin.test.assertEquals

/** M8-06a round 1: every rule the app can emit has its own group title (not the rule id). */
class RuleTitleTest {
    @Test
    fun `every rule of the catalog has a group title that is not the bare rule id`() {
        val missing = RuleCatalog.all.map { it.id }.filter { id -> MigrateFromThreeTexts.ruleTitle(id).let { it == id || it.isBlank() || !it.contains(' ') } }
        assertEquals(emptyList(), missing, "rules without a title in MigrateFromThreeTexts")
    }

    @Test
    fun `titles tell rules apart`() {
        val titles = RuleCatalog.all.map { MigrateFromThreeTexts.ruleTitle(it.id) }
        assertEquals(titles.size, titles.toSet().size, "two rules share a title: ${titles.groupBy { it }.filterValues { it.size > 1 }.keys}")
    }
}
