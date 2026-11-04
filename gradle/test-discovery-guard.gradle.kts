// Test discovery guard (M2-TESTHYGIENE-01): wiring. The logic and task classes live in
// buildSrc/src/main/kotlin/TestDiscoveryGuard.kt (Gradle needs real classes, not script classes, for typed tasks).
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

import dev.caerbannog.build.VerifyTestDiscovery
import dev.caerbannog.build.VerifyTestDiscoveryFixtures

val guardRoot = rootProject.layout.projectDirectory
val moduleNames = rootProject.subprojects.map { it.name }
val testSourceDirs = moduleNames.flatMap { m -> listOf("jvmTest", "commonTest", "test").map { "$m/src/$it" } }
val resultRoots = moduleNames.map { m -> rootProject.file("$m/build/test-results") }
val allResultDirs = resultRoots.flatMap { root ->
    listOf("test", "jvmTest", "brokerTest", "keychainTest", "screenshotTest").map { root.resolve(it) }
}

val fixtureTask = rootProject.tasks.register<VerifyTestDiscoveryFixtures>("verifyTestDiscoveryFixtures") {
    group = "verification"
    description = "Proves the test discovery guard rejects deliberately bad fixtures (tests/guard-fixtures)."
    fixtures.from(rootProject.fileTree("tests/guard-fixtures"))
    fixtureRoot.set(rootProject.layout.projectDirectory.dir("tests/guard-fixtures"))
    rootDirectory.set(guardRoot)
    marker.set(rootProject.layout.buildDirectory.file("reports/test-discovery/fixtures-ok.txt"))
}

val guardTask = rootProject.tasks.register<VerifyTestDiscovery>("verifyTestDiscovery") {
    group = "verification"
    description = "Fails when a test method is not discovered by JUnit (source count != XML testcase count) or is " +
        "written in a way JUnit 5 skips silently. Runs the fast tests of every module first."
    sources.from(testSourceDirs.map { d -> rootProject.fileTree(d) { include("**/*.kt") } })
    results.from(allResultDirs.map { d -> rootProject.fileTree(d) { include("TEST-*.xml") } })
    resultDirs.set(allResultDirs)
    rootDirectory.set(guardRoot)
    reportFile.set(rootProject.layout.buildDirectory.file("reports/test-discovery/report.txt"))
    dependsOn(fixtureTask)
    moduleNames.forEach { dependsOn(":$it:test") }
}

// Root `./gradlew test` (and `task test`) and `./gradlew check` now run the guard after every module's tests.
rootProject.apply(plugin = "base")
rootProject.tasks.register("test") {
    group = "verification"
    description = "Root lifecycle: runs the guard (which runs every module's fast tests first)."
    dependsOn(guardTask)
}
rootProject.tasks.named("check") { dependsOn(guardTask) }
