plugins {
    kotlin("multiplatform")
}

// M1-01 spike: `core` per docs/spec.md Module layout table - commonMain, no RabbitMQ imports.
//
// Test framework: JUnit 5 (+ kotlinx-coroutines-test later, once there is async code to test), per
// docs/spec.md's Testing table - chosen over Kotest to keep one test runner across every module
// (acceptance-tests and data-rabbit already need JUnit 5/JUnit Platform for Cucumber and
// Testcontainers, so using it here too avoids running two different test engines).
kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        // M1-02: ConnectionsViewModel exposes state as a StateFlow (Async and state row,
        // docs/spec.md tech-stack table) - this is a pure-Kotlin multiplatform artifact, no JVM-only
        // or network code, so it stays within core's "zero RabbitMQ/network imports" rule.
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
        }
        // M10-04 (owner decision 2026-11-27, PD-2 b, OQ-1 option A): RE2/J reads the permission patterns for the match preview, behind
        // `PatternEngine` (`expect fun defaultPatternEngine()`). jvmMain only: commonMain stays free of the library. BSD-3-Clause, see
        // THIRD_PARTY_NOTICES.md; its runtime dependency org.jspecify:jspecify:1.0.1 (Apache-2.0, annotations only) comes transitively.
        jvmMain.dependencies {
            implementation("com.google.re2j:re2j:1.9")
        }
        jvmTest.dependencies {
            implementation(project(":core-testing"))
            implementation(kotlin("test"))
            // M2-04: virtual time for the refresh timer tests (no sleeps).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            implementation("org.junit.jupiter:junit-jupiter:5.14.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// Root `./gradlew test` (and `task test`) runs the same-named task in every project, but a Kotlin
// Multiplatform module only has `jvmTest`. This lifecycle `test` delegates to `jvmTest` (not `allTests`,
// so a future non-JVM target does not slow the fast local run). Any later KMP module with tests needs the
// same wiring. See docs/tasks/M1-TASKFILE-02-test-task-runs-all-modules.md.
tasks.register("test") {
    group = "verification"
    description = "Runs the fast JVM tests (delegates to jvmTest) so the root `test` task reaches this module."
    dependsOn("jvmTest")
}
