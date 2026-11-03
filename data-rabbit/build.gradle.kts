plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike: `data-rabbit` per docs/spec.md Module layout table - jvmMain. Plain `kotlin("jvm")`
// (not Kotlin Multiplatform): per spec.md "Why JVM-only", a future Android/iOS client is served by a
// separate gateway module, not by this one, so data-rabbit never needs other KMP targets.
//
// This task's Scope keeps data-rabbit an "empty jvmMain module with just the build wiring" - no
// RabbitMQ client, no management HTTP client yet. Only test-tooling spikes live here for now:
//   - RabbitMqContainerSpikeTest (Testcontainers proof, tagged "broker")
//   - KeyringRoundTripSpikeTest (keychain library proof, com.github.javakeyring:java-keyring)
dependencies {
    // M1-02: data-rabbit's real implementations (BrokerReachabilityChecker, ConnectionProfileRepository,
    // SecretStore, CaCertificateValidator) implement interfaces declared in `core` - see
    // docs/tasks/M1-02-connection-profiles.md's Design notes ("core holds ... repository interfaces").
    // No src/main exists yet, so this dependency only matters for the test source set until the
    // senior developer adds one.
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // M1-02: RealBrokerReachabilityTest calls the (suspend) BrokerReachabilityChecker.check() from a
    // plain JUnit test via runBlocking.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:rabbitmq")

    testImplementation("com.github.javakeyring:java-keyring:1.0.4")
}

tasks.test {
    useJUnitPlatform {
        // M1-01 Design notes: the Testcontainers proof is tagged "broker" (mirrors the BDD @broker
        // tag) and excluded from the default fast test run; run `./gradlew :data-rabbit:brokerTest`
        // to opt in once Docker Desktop is available.
        excludeTags("broker")
    }
}

tasks.register<Test>("brokerTest") {
    description = "Runs the data-rabbit tests tagged \"broker\" (needs Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("broker")
    }
    // M1-01 finding: Testcontainers 1.21.3 (latest as of this spike) probes Docker with a
    // hardcoded fallback API version of 1.32 (org.testcontainers.dockerclient.
    // DockerClientProviderStrategy#getTransportConfig) whenever no api.version is resolved from
    // the JVM's own system properties/environment. The Docker Desktop engine on this machine
    // (29.8.2, API 1.56) reports MinAPIVersion 1.40 and rejects 1.32 with HTTP 400, so every
    // provider strategy fails and the container never starts - confirmed by bytecode inspection
    // and by curling the daemon socket directly at /v1.32/info (400) vs /v1.40/info (200).
    // Pinning a supported version here (set directly on the forked test JVM, since a `-D` on the
    // `gradlew` command line only reaches the long-lived Gradle daemon, not the test process)
    // works around it until Testcontainers ships a fix. Record of the finding: docs/spec.md Risks.
    systemProperty("api.version", "1.43")
}
