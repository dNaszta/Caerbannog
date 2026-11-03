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
