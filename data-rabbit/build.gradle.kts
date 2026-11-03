plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike: `data-rabbit` per docs/spec.md Module layout table - jvmMain. Plain `kotlin("jvm")`
// (not Kotlin Multiplatform): per spec.md "Why JVM-only", a future Android/iOS client is served by a
// separate gateway module, not by this one, so data-rabbit never needs other KMP targets.
//
// M1-02: this is where data-rabbit stops being an empty shell - real implementations of core's
// ConnectionProfileRepository (JSON file), SecretStore (OS keychain), CaCertificateValidator (X.509)
// and BrokerReachabilityChecker (TLS reachability) now live in src/main. Test-tooling spikes from
// M1-01 stay as-is:
//   - RabbitMqContainerSpikeTest (Testcontainers proof, tagged "broker")
//   - KeyringRoundTripSpikeTest (keychain library proof, com.github.javakeyring:java-keyring)
dependencies {
    // M1-02: data-rabbit's real implementations (BrokerReachabilityChecker, ConnectionProfileRepository,
    // SecretStore, CaCertificateValidator) implement interfaces declared in `core` - see
    // docs/tasks/M1-02-connection-profiles.md's Design notes ("core holds ... repository interfaces").
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Connection-profile JSON file storage (JsonFileConnectionProfileRepository): kotlinx-serialization
    // is already the project's chosen JSON library (docs/spec.md tech-stack table, "Management API:
    // Ktor client with kotlinx-serialization") - reused here for file I/O instead of introducing a
    // second JSON library. A private DTO in this module carries the @Serializable annotation, not
    // core's ConnectionProfile itself, so core's domain model stays free of any serialization-library
    // import (see JsonFileConnectionProfileRepository's own header comment).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Management API reachability probe (StreamAndManagementReachabilityChecker): Ktor client per
    // docs/spec.md's tech-stack table ("Management API: Ktor client ..."), using the Java engine
    // (wraps java.net.http.HttpClient) because it is the simplest way to plug in a custom SSLContext
    // built from an optional per-profile CA certificate.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-java:3.0.3")

    // Password storage (JavaKeyringSecretStore): was test-only in the M1-01 spike, now a real
    // production dependency.
    implementation("com.github.javakeyring:java-keyring:1.0.4")

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
