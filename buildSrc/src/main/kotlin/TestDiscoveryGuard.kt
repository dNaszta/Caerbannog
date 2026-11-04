package dev.caerbannog.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

// Test discovery guard (M2-TESTHYGIENE-01).
//
// Why: JUnit 5 silently skips a @Test function whose return type is not Unit (for example
// `fun x() = runBlocking { ...; assertIs<T>(x) }`). It is not reported, not even as skipped, so the build stays green
// while the test never runs. Two checks, both in task `verifyTestDiscovery`:
//   1. Counts (the real guard): for every test class in the sources, the number of @Test-family methods must equal
//      the `testcase` count in the JUnit XML of the test run. Catches every reason a test is not discovered.
//   2. Style (fast, clear message): file, line and name of every @Test function with a declared return type other
//      than Unit, or an expression body `= runBlocking { ... }` (without <Unit>) whose last statement is a known
//      value-returning assertion (assertIs, assertFailsWith, assertThrows, assertNotNull, ...).
// Classes tagged @Tag("broker" | "keychain" | "screenshot") only produce XML in their own task (`<tag>Test`). They
// are compared when that XML exists and is newer than the source, otherwise reported as "unchecked".
// `verifyTestDiscoveryFixtures` runs the same checker over tests/guard-fixtures/ (deliberately bad sources and a
// result file with a missing testcase) and fails unless the checker rejects exactly what it must: the proof that the
// guard can fail. No dependencies; configuration cache compatible (file inputs only, no Project at execution).

private val testAnnotation = Regex("""(?m)^[ \t]*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b""")
private val valueReturningStart =
    Regex("""^(?:\w+\.)*(assertIs|assertFailsWith|assertThrows|assertNotNull|assertInstanceOf|assertDoesNotThrow|assertTimeout)\b""")

class TestFunction(val file: String, val line: Int, val name: String, val problem: String?)
class TestClass(
    val fqn: String,
    val file: String,
    val tag: String?,
    val methods: Int,
    val dynamic: Boolean,
    val lastModified: Long,
)

fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1

// Index of the brace closing the one at `open`; line comments are skipped. Strings are not, so a lone brace inside a
// string literal gives -1, which is reported as "cannot parse" instead of passing silently.
fun matchingBrace(text: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < text.length) {
        val c = text[i]
        if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
            while (i < text.length && text[i] != '\n') i++
            continue
        }
        if (c == '{') depth++
        if (c == '}') {
            depth--
            if (depth == 0) return i
        }
        i++
    }
    return -1
}

// The last top-level statement of a lambda body: from the last line that starts at bracket depth 0.
fun lastStatement(body: String): String {
    var depth = 0
    var last = -1
    var offset = 0
    for (line in body.split('\n')) {
        val t = line.trim()
        if (t.isNotEmpty() && !t.startsWith("//") && depth == 0) last = offset
        depth += line.count { it == '{' || it == '(' || it == '[' } - line.count { it == '}' || it == ')' || it == ']' }
        offset += line.length + 1
    }
    return if (last < 0) "" else body.substring(last).trim()
}

// Every test-annotated function in `text`, with the reason it is suspect (null when fine).
fun scanFunctions(file: String, text: String): List<TestFunction> {
    val result = ArrayList<TestFunction>()
    for (m in testAnnotation.findAll(text)) {
        val funAt = text.indexOf("fun ", m.range.last)
        if (funAt < 0) continue
        val line = lineOf(text, funAt)
        var p = funAt + 4
        while (text[p] == ' ') p++
        if (text[p] == '<') p = text.indexOf('>', p) + 1
        while (text[p] == ' ') p++
        val name: String
        if (text[p] == '`') {
            val end = text.indexOf('`', p + 1)
            name = text.substring(p + 1, end)
            p = end + 1
        } else {
            val end = Regex("""\w+""").find(text, p)!!.range.last + 1
            name = text.substring(p, end)
            p = end
        }
        val open = text.indexOf('(', p)
        var depth = 0
        var q = open
        while (q < text.length) {
            if (text[q] == '(') depth++
            if (text[q] == ')') { depth--; if (depth == 0) break }
            q++
        }
        var r = q + 1
        while (text[r].isWhitespace()) r++
        var problem: String? = null
        if (text[r] == ':') {
            val declaredMatch = Regex("""\s*:\s*([\w.<>?, ]+?)\s*(?=[={])""").find(text, r)!!
            val declared = declaredMatch.groupValues[1]
            if (declared != "Unit") problem = "declared return type $declared instead of Unit (JUnit 5 skips it silently)"
            r = declaredMatch.range.last + 1
            while (text[r].isWhitespace()) r++
        }
        if (problem == null && text[r] == '=') {
            r++
            while (text[r].isWhitespace()) r++
            val call = Regex("""(?:\w+\.)*runBlocking\s*\{""").find(text, r)
            if (call != null && call.range.first == r) {
                val open2 = call.range.last
                val close = matchingBrace(text, open2)
                if (close < 0) {
                    problem = "cannot parse the body of the runBlocking expression"
                } else {
                    val last = lastStatement(text.substring(open2 + 1, close))
                    if (valueReturningStart.containsMatchIn(last)) {
                        problem = "expression body `= runBlocking { ... }` ends in a value-returning call " +
                            "(`${last.lineSequence().first().take(60)}`): JUnit 5 skips it silently; " +
                            "use `runBlocking<Unit> { ... }` or a block body"
                    }
                }
            }
        }
        result.add(TestFunction(file, line, name, problem))
    }
    return result
}

fun scanClass(file: File, text: String, relative: String): TestClass? {
    val annotations = testAnnotation.findAll(text).toList()
    if (annotations.isEmpty()) return null
    val pkg = Regex("""(?m)^package\s+(\S+)""").find(text)?.groupValues?.get(1)
    val cls = Regex("""(?m)^(?:(?:internal|private|open|abstract|public)\s+)*class\s+(\w+)""").find(text)?.groupValues?.get(1)
        ?: return null
    val tag = Regex("""(?m)^\s*@Tag\("(\w+)"\)""").find(text)?.groupValues?.get(1)
    val dynamic = annotations.any { it.groupValues[1] != "Test" }
    return TestClass(
        (if (pkg == null) "" else "$pkg.") + cls, relative, tag, annotations.size, dynamic, file.lastModified(),
    )
}

// testcase counts per class FQN, per result directory name ("test", "jvmTest", "brokerTest", ...).
fun readResults(resultDirs: List<File>): Map<String, Map<String, Int>> {
    val out = HashMap<String, MutableMap<String, Int>>()
    for (dir in resultDirs) {
        val files = dir.listFiles { f -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") } ?: continue
        for (f in files) {
            val head = f.readText()
            val cls = f.name.removePrefix("TEST-").removeSuffix(".xml")
            out.getOrPut(dir.name) { HashMap() }[cls] = Regex("""<testcase\b""").findAll(head).count()
        }
    }
    return out
}

class GuardResult(val violations: List<String>, val report: List<String>)

// sources: .kt (or .kt.txt) files; resultDirs: directories holding TEST-*.xml, named after the Gradle test task.
fun runGuard(sources: List<File>, resultDirs: List<File>, rootDir: File): GuardResult {
    val violations = ArrayList<String>()
    val report = ArrayList<String>()
    val results = readResults(resultDirs)
    val classes = ArrayList<TestClass>()
    for (f in sources.sortedBy { it.path }) {
        val text = f.readText()
        val rel = f.relativeTo(rootDir).path
        for (fn in scanFunctions(rel, text)) {
            if (fn.problem != null) violations.add("$rel:${fn.line}: test `${fn.name}`: ${fn.problem}")
        }
        scanClass(f, text, rel)?.let { classes.add(it) }
    }
    report.add("%-70s %6s %6s  %s".format("class", "source", "xml", "result"))
    for (c in classes.sortedBy { it.fqn }) {
        val taskDir = if (c.tag == null) null else "${c.tag}Test"
        val candidates = if (taskDir != null) listOf(taskDir) else listOf("test", "jvmTest")
        val xml = candidates.firstNotNullOfOrNull { dir -> results[dir]?.get(c.fqn) }
        val resultFile = candidates.firstNotNullOfOrNull { dir ->
            resultDirs.firstOrNull { it.name == dir }?.resolve("TEST-${c.fqn}.xml")?.takeIf { it.exists() }
        }
        val verdict: String
        if (taskDir != null && (xml == null || (resultFile != null && resultFile.lastModified() < c.lastModified))) {
            verdict = "unchecked (tag ${c.tag}: run $taskDir to check)"
        } else if (xml == null) {
            verdict = "FAIL no result: class ${c.fqn} has ${c.methods} test methods but no test run reported any"
            violations.add("${c.file}: ${c.fqn} has ${c.methods} test methods and no XML result at all: " +
                "not discovered (private/internal class, wrong annotation import, or the test task did not run)")
        } else if (c.dynamic && xml >= c.methods || !c.dynamic && xml == c.methods) {
            verdict = "ok"
        } else {
            verdict = "FAIL"
            violations.add("${c.file}: ${c.fqn} has ${c.methods} test methods but the run reported $xml testcases: " +
                "${c.methods - xml} test(s) not discovered (a @Test function returning a value, wrong @Test import, " +
                "private function?)")
        }
        report.add("%-70s %6d %6s  %s".format(c.fqn, c.methods, xml?.toString() ?: "-", verdict))
    }
    return GuardResult(violations, report)
}

abstract class VerifyTestDiscovery : DefaultTask() {
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:InputFiles abstract val results: ConfigurableFileCollection
    @get:Internal abstract val resultDirs: ListProperty<File>
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val r = runGuard(sources.files.toList(), resultDirs.get(), rootDirectory.get().asFile)
        reportFile.get().asFile.apply { parentFile.mkdirs() }.writeText(r.report.joinToString("\n") + "\n")
        r.report.forEach { logger.lifecycle(it) }
        if (r.violations.isNotEmpty()) {
            throw GradleException(
                "Test discovery guard: ${r.violations.size} problem(s). A test JUnit does not discover never runs.\n" +
                    r.violations.joinToString("\n") { " - $it" },
            )
        }
    }
}

abstract class VerifyTestDiscoveryFixtures : DefaultTask() {
    @get:InputFiles abstract val fixtures: ConfigurableFileCollection
    @get:Internal abstract val fixtureRoot: DirectoryProperty
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:OutputFile abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val base = fixtureRoot.get().asFile
        val sources = base.resolve("sources").listFiles { f -> f.name.endsWith(".kt.txt") }!!.toList()
        val r = runGuard(sources, listOf(base.resolve("results/test")), rootDirectory.get().asFile)
        // Every bad test below must be named by the guard; the good one must not be.
        val mustFlag = listOf(
            "declared return type is rejected",
            "runBlocking ending in assertIs is rejected",
            "runBlocking ending in assertFailsWith is rejected",
            "BadNonUnitFixtureTest has 3 test methods but the run reported 0 testcases",
            "HiddenCountFixtureTest has 3 test methods but the run reported 2 testcases",
        )
        val mustNotFlag = listOf("a fine test returns Unit", "runBlocking Unit is accepted", "block body is accepted")
        val text = r.violations.joinToString("\n")
        val missed = mustFlag.filterNot { text.contains(it) }
        val falseAlarm = mustNotFlag.filter { text.contains(it) }
        if (missed.isNotEmpty() || falseAlarm.isNotEmpty() || r.violations.size != mustFlag.size) {
            throw GradleException(
                "Guard self-test failed. Not rejected: $missed. Wrongly rejected: $falseAlarm. " +
                    "Violations found (${r.violations.size}, expected ${mustFlag.size}):\n$text",
            )
        }
        logger.lifecycle("Test discovery guard self-test: rejected all ${mustFlag.size} bad fixtures, accepted the good ones.")
        marker.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
