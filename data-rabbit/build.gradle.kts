plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike scaffold: `data-rabbit` per docs/spec.md Module layout table - jvmMain. Plain
// `kotlin("jvm")` (not Kotlin Multiplatform): per spec.md "Why JVM-only", a future Android/iOS
// client is served by a separate gateway module, not by this one, so data-rabbit never needs other
// KMP targets.
//
// This task's Scope keeps data-rabbit an "empty jvmMain module with just the build wiring" - no
// RabbitMQ client, no management HTTP client yet. Only test-tooling spikes live here for now:
//   - RabbitMqContainerSpikeTest (Testcontainers proof, tagged "broker")
//   - KeyringRoundTripSpikeTest (keychain library proof)
// Deliberately no test dependencies declared yet, so both fail to compile until the senior
// developer adds org.testcontainers:*, org.junit.jupiter:*, and the chosen keychain library.
