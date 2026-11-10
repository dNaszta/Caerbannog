package dev.caerbannog.core.migrate3x

import dev.caerbannog.core.migrate3x.fakes.DefinitionsFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M8-06e items 1 and 6: the shape of the [GroupIndex] (brief: built in one pass, groups in display order, escaped loss names). Red against the stub. */
class GroupIndexTest {
    private fun key(f: Finding) = "${f.rule}:${f.severity.name.lowercase()}"

    private val mixed = GroupingFixtures.mixed(copies = 20)

    @Test
    fun `the index is built in exactly one pass over the findings`() {
        val counting = CountingList(mixed.report.findings)
        val index = GroupIndex.of(counting)
        assertEquals(1, counting.total, "one iteration expected, the passes were: ${counting.describe()}")
        assertEquals(mixed.report.findings.size, index.groups.sumOf { it.findings.size }, "every finding sits in exactly one group")
    }

    @Test
    fun `the groups hold the findings of one rule and severity in report order, keyed rule colon severity`() {
        val index = GroupIndex.of(mixed.report.findings)
        assertTrue(index.groups.isNotEmpty(), "the index has no groups")
        for (g in index.groups) {
            assertTrue(g.findings.isNotEmpty(), "an empty group ${g.key}")
            assertTrue(g.findings.all { key(it) == g.key && it.rule == g.rule && it.severity == g.severity && it.findingClass == g.findingClass }, "mixed findings in ${g.key}")
            assertEquals(mixed.report.findings.filter { key(it) == g.key }, g.findings, "report order in ${g.key}")
        }
        assertEquals(index.groups.size, index.groups.map { it.key }.toSet().size, "keys are unique")
    }

    @Test
    fun `the groups are in display order - message loss first, then severity, then key`() {
        val index = GroupIndex.of(mixed.report.findings)
        val order = index.groups.map { Triple(if (it.findingClass == FindingClass.MessageLoss) 0 else 1, it.severity.ordinal, it.key) }
        assertEquals(order.sortedWith(compareBy({ it.first }, { it.second }, { it.third })), order, "order of ${index.groups.map { it.key }}")
        assertEquals(FindingClass.MessageLoss, index.groups.first().findingClass)
    }

    @Test
    fun `the loss names are escaped, in report order, and only for findings of class message loss`() {
        val index = GroupIndex.of(mixed.report.findings)
        val expected = mixed.report.findings.filter { it.findingClass == FindingClass.MessageLoss }.map { escapeForText(it.name.orEmpty()) }
        assertTrue(expected.isNotEmpty() && expected.any { it.contains("\\u000A") && it.contains("\\u202E") }, "the premise: an odd name is in the fixture")
        assertEquals(expected, index.lossNames)
    }

    @Test
    fun `an empty report has an empty index`() {
        val index = GroupIndex.of(emptyList())
        assertEquals(emptyList(), index.groups.map { it.key })
        assertEquals(emptyList(), index.lossNames)
    }

    @Test
    fun `a held analysis builds its index once, with its review`() {
        val held = mixed
        assertEquals(held.report.findings.size, held.groupIndex.groups.sumOf { it.findings.size })
        assertTrue(held.groupIndex === held.groupIndex, "the index is a value of the held analysis, not rebuilt on each read")
    }

    @Test
    fun `the class of a rule does not depend on the findings that remain - one class and one severity per key over the whole catalogue of a large fixture`() {
        val findings = listOf(
            mixed.report.findings,
            ScreenFiles.held(DefinitionsFixtures.read(DefinitionsFixtures.FULL)).report.findings,
            ScreenFiles.held(DefinitionsFixtures.read(DefinitionsFixtures.SHOVEL_PARAMETER)).report.findings,
        ).flatten()
        val rules = findings.map { it.rule }.toSet()
        assertTrue(rules.size >= 12, "the premise: a large fixture covers many rules, got ${rules.size}: $rules")
        for ((key, group) in findings.groupBy { key(it) }) {
            assertEquals(1, group.map { it.findingClass }.toSet().size, "the key $key has more than one class: ${group.map { it.findingClass }.toSet()}")
            assertEquals(1, group.map { it.severity }.toSet().size, "the key $key has more than one severity")
        }
        for ((rule, group) in findings.groupBy { it.rule }) {
            assertEquals(1, group.map { it.findingClass }.toSet().size, "the rule $rule has more than one class: ${group.map { it.findingClass }.toSet()}")
        }
        // the sort does not change when findings are removed: the key order of the full index holds for any subset
        val full = GroupIndex.of(findings).groups.map { it.key }
        val half = GroupIndex.of(findings.filterIndexed { i, _ -> i % 2 == 0 }).groups.map { it.key }
        assertTrue(full.size >= 10 && half.isNotEmpty(), "the index has groups: $full / $half")
        assertEquals(full.filter { it in half }, half)
    }
}
