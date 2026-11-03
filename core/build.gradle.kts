plugins {
    kotlin("multiplatform") version "2.4.21"
}

// M1-01 spike scaffold: `core` per docs/spec.md Module layout table - commonMain, no RabbitMQ imports.
//
// Deliberately bare: no test framework dependency is declared yet (Kotest vs JUnit 5 is still an
// open engineering call per the task brief's Open points). The trivial unit test in
// src/jvmTest/kotlin uses JUnit 5 directly; until the senior developer adds the junit-jupiter
// dependency (and wires `useJUnitPlatform()` on the jvm test task) it must fail to compile.
kotlin {
    jvmToolchain(21)
    jvm()
}
