package dev.caerbannog.datarabbit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-18: `brokerTest` runs `broker & !spike`, `brokerSpikeTest` runs `broker & spike`. The rule: a class carries `spike`
 * if and only if it carries `broker` and has "Spike" in its simple name. Without this guard a new spike class would
 * silently land in the regression half (and a mistagged product class would drop out of it).
 * Fast, no container: reads the compiled test classes by reflection.
 */
class BrokerTestSplitGuardTest {
    /** What the rule needs to know about one test class. */
    data class ClassTags(val name: String, val simpleName: String, val broker: Boolean, val spike: Boolean)

    @Test
    fun `every broker class with Spike in its name carries spike and no other class does`() {
        val all = compiledTestClasses()
        assertTrue(all.any { it.broker && it.spike }, "scan found no spike class; the scan is broken")
        assertTrue(all.any { it.broker && !it.spike }, "scan found no regression class; the scan is broken")
        val problems = violations(all)
        assertTrue(problems.isEmpty(), "Tag rule broken (M2-18):\n" + problems.joinToString("\n"))
    }

    @Test
    fun `rule flags a spike class without the spike tag`() {
        val problems = violations(listOf(ClassTags("p.RealBrokerXSpikeTest", "RealBrokerXSpikeTest", broker = true, spike = false)))
        assertEquals(1, problems.size)
        assertTrue("lacks @Tag(\"spike\")" in problems.single(), problems.single())
    }

    @Test
    fun `rule flags a non-spike class that carries the spike tag`() {
        val problems = violations(listOf(ClassTags("p.RealBrokerXTest", "RealBrokerXTest", broker = true, spike = true)))
        assertEquals(1, problems.size)
        assertTrue("must not carry @Tag(\"spike\")" in problems.single(), problems.single())
    }

    @Test
    fun `rule accepts correctly tagged classes and ignores non-broker classes`() {
        val ok = listOf(
            ClassTags("p.RealBrokerXSpikeTest", "RealBrokerXSpikeTest", broker = true, spike = true),
            ClassTags("p.RealBrokerXTest", "RealBrokerXTest", broker = true, spike = false),
            ClassTags("p.KeyringRoundTripSpikeTest", "KeyringRoundTripSpikeTest", broker = false, spike = false),
        )
        assertTrue(violations(ok).isEmpty())
    }

    private fun violations(classes: List<ClassTags>): List<String> = classes.mapNotNull { c ->
        val isSpikeByName = c.broker && "Spike" in c.simpleName
        when {
            isSpikeByName && !c.spike -> "${c.name} has Spike in its name and @Tag(\"broker\") but lacks @Tag(\"spike\")"
            !isSpikeByName && c.spike -> "${c.name} must not carry @Tag(\"spike\") (rule: broker and Spike in the simple name)"
            else -> null
        }
    }

    private fun compiledTestClasses(): List<ClassTags> {
        val root = File(BrokerTestSplitGuardTest::class.java.protectionDomain.codeSource.location.toURI())
        check(root.isDirectory) { "expected a classes directory, got $root" }
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "class" && '$' !in it.name }
            .map { root.toPath().relativize(it.toPath()).toString().removeSuffix(".class").replace(File.separatorChar, '.') }
            .mapNotNull { name -> runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull() }
            .map { c ->
                val tags = c.getAnnotationsByType(Tag::class.java).map { it.value }
                ClassTags(c.name, c.simpleName, "broker" in tags, "spike" in tags)
            }
            .toList()
    }
}
