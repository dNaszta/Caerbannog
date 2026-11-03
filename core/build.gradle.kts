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
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
