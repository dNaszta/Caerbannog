// Root build file for the M1-01 skeleton.
//
// Plugin versions are declared once here (with `apply false`) and applied without a version in each
// subproject's own build.gradle.kts: the Kotlin Gradle plugin warns (and risks breaking) if the same
// version is loaded independently in more than one subproject's own `plugins {}` block. Each
// subproject still applies only the plugins it actually needs and owns its own dependencies/test
// wiring; this file stays otherwise empty.
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

// M2-TESTHYGIENE-01: tests that JUnit never discovers fail the build (verifyTestDiscovery, wired into test and check).
apply(from = "gradle/test-discovery-guard.gradle.kts")

// Name slow or hung tests. Gradle prints nothing while a Test task runs, so a stuck test made the CI step look hung
// until the step timeout. Every Test task now logs one line when a test class starts and one when it ends (with the
// duration), plus a line for any single test slower than 30 s. Each Test task also gets a task timeout, so Gradle
// fails the task and names the test instead of Actions killing the step silently. Broker, keychain and screenshot
// tasks get a larger limit (broker tests take about 17 minutes on CI). Test behaviour is unchanged.
subprojects {
    tasks.withType<Test>().configureEach {
        val slowTestMillis = 30_000L
        val taskPath = path
        val log = logger
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {
                if (suite.className != null) log.lifecycle("TEST START  ${suite.className}")
            }

            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                if (suite.className != null) {
                    val seconds = (result.endTime - result.startTime) / 1000.0
                    log.lifecycle(
                        "TEST DONE   ${suite.className} (%.1f s, %d tests, %d failed, %d skipped)"
                            .format(seconds, result.testCount, result.failedTestCount, result.skippedTestCount),
                    )
                }
            }

            override fun beforeTest(testDescriptor: TestDescriptor) = Unit

            override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                val millis = result.endTime - result.startTime
                if (millis >= slowTestMillis) {
                    log.lifecycle("SLOW TEST   ${testDescriptor.className}.${testDescriptor.name} took ${millis / 1000} s ($taskPath)")
                }
            }
        })
        val slowTaskNames = setOf("brokerTest", "keychainTest", "screenshotTest")
        timeout.set(java.time.Duration.ofMinutes(if (name in slowTaskNames) 40 else 15))
    }
}

// Security pins for TEST classpaths only (nothing here reaches the installers: guava and commons-compress are not on the
// runtime classpath of desktopApp or mcp-server). Dependabot raised alerts for both, but cannot fix them because they are
// transitive: guava 28.1-android arrives with Compose's ui-test (through Truth), commons-compress 1.24.0 with Testcontainers.
// Remove a pin once the library that brings the dependency ships a fixed version itself.
subprojects {
    configurations.configureEach {
        if (name.contains("test", ignoreCase = true)) {
            resolutionStrategy.eachDependency {
                when ("${requested.group}:${requested.name}") {
                    "com.google.guava:guava" -> {
                        useVersion("33.8.0-android")
                        because("security alerts for guava before 33.7.2 (transitive via Compose ui-test and Truth, tests only)")
                    }
                    "org.apache.commons:commons-compress" -> {
                        useVersion("1.28.0")
                        because("security alerts for commons-compress before 1.26.0 (transitive via Testcontainers, tests only)")
                    }
                }
            }
        }
    }
}
