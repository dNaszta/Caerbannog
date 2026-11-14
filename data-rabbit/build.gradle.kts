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
//   - KeyringRoundTripSpikeTest (keychain library proof, com.github.javakeyring:java-keyring; tagged
//     "keychain", run only via `keychainTest` because it touches the real OS keychain, as does
//     KeyringSecretStoreTest)
dependencies {
    // M1-02: data-rabbit's real implementations (BrokerReachabilityChecker, ConnectionProfileRepository,
    // SecretStore, CaCertificateValidator) implement interfaces declared in `core` - see
    // docs/tasks/M1-02-connection-profiles.md's Design notes ("core holds ... repository interfaces").
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Connection-profile JSON file storage (JsonFileConnectionProfileRepository): kotlinx-serialization
    // is already the project's chosen JSON library (docs/spec.md tech-stack table, "Management API:
    // Ktor client with kotlinx-serialization") - reused here for file I/O instead of introducing a
    // second JSON library. A private DTO in this module carries the @Serializable annotation, not
    // core's ConnectionProfile itself, so core's domain model stays free of any serialization-library
    // import (see JsonFileConnectionProfileRepository's own header comment).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // Management API reachability probe (StreamAndManagementReachabilityChecker): Ktor client per
    // docs/spec.md's tech-stack table ("Management API: Ktor client ..."), using the Java engine
    // (wraps java.net.http.HttpClient) because it is the simplest way to plug in a custom SSLContext
    // built from an optional per-profile CA certificate.
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-java:3.0.3")

    // Password storage (JavaKeyringSecretStore): was test-only in the M1-01 spike, now a real
    // production dependency.
    implementation("com.github.javakeyring:java-keyring:1.0.4")

    // M2-03 part 2: the held connection reads the first offset with queryStreamStats (M2-02 spike
    // decision S1 had it in test scope only). Production scope, and it brings Netty along.
    implementation("com.rabbitmq:stream-client:1.12.0")

    // M6-04: MQTT message publisher (Eclipse Paho 1.2.5, published 2020, passes two-week age rule;
    // zero transitive dependencies; EPL-2.0 / EDL-1.0).
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    testImplementation(kotlin("test"))
    // M5-08a: FakeManagementBroker (a scripted, honest model of the management API) and FakeStreamManager.
    testImplementation(project(":core-testing"))
    // RealBrokerMqttIntegrationTest subscribes with the MQTT 3.1.1 client (a v3 subscriber also receives v5 publishes).
    testImplementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // M2-14c: ManagementHttp classification tests throw constructed SSL exceptions from a mock engine (no server can produce a cause loop).
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // M1-02: RealBrokerReachabilityTest calls the (suspend) BrokerReachabilityChecker.check() from a
    // plain JUnit test via runBlocking.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:rabbitmq")
    // M8-13 (S-O): AMQP 0-9-1 client for the spike that needs real consumers (exclusive consumers, redelivery, global QoS).
    // TEST-ONLY, owner approved 2026-11-09. Licence: the POM offers AL 2.0 / MPL 2.0 / GPL v2; used under Apache-2.0.
    // 5.37.0 (2026-10-01) is the newest release older than two weeks; 5.37.1 (2026-11-06) is not.
    testImplementation("com.rabbitmq:amqp-client:5.37.0")

}

tasks.test {
    useJUnitPlatform {
        // M1-01 Design notes: the Testcontainers proof is tagged "broker" (mirrors the BDD @broker
        // tag) and excluded from the default fast test run; run `./gradlew :data-rabbit:brokerTest`
        // to opt in once Docker Desktop is available.
        //
        // M1-08: the two real-keychain classes (KeyringSecretStoreTest, KeyringRoundTripSpikeTest) are
        // tagged "keychain" and excluded too: they write to the developer's real OS keychain and may
        // prompt, so the default run must stay side-effect free. Opt in with `keychainTest`.
        excludeTags("broker", "keychain")
    }
}

tasks.register<Test>("brokerTest") {
    description = "Runs the data-rabbit tests tagged \"broker\" (uses rootless Podman socket when present, or Docker)."
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

    // M2-15: Rootless Podman support on Fedora / Linux.
    // Precedence: `-PcontainerEngine=podman|docker` > `CONTAINER_ENGINE=podman|docker` > auto-detect `$XDG_RUNTIME_DIR/podman/podman.sock`.
    // When targeting rootless Podman:
    //   1. Point Testcontainers at `unix://$XDG_RUNTIME_DIR/podman/podman.sock` (unless DOCKER_HOST is already set).
    //   2. Disable the Ryuk reaper container (`TESTCONTAINERS_RYUK_DISABLED=true`, unless already set), because
    //      rootless Podman + SELinux Enforcing blocks Ryuk from mounting the Podman socket inside the reaper container.
    val requestedEngine = (project.findProperty("containerEngine") as String?)
        ?.trim()
        ?.lowercase()
        ?: System.getenv("CONTAINER_ENGINE")?.trim()?.lowercase()
    val xdgRuntimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: "/run/user/1000"
    val podmanSocketFile = file("$xdgRuntimeDir/podman/podman.sock")
    val usePodman = when (requestedEngine) {
        "podman" -> true
        "docker" -> false
        else -> podmanSocketFile.exists()
    }
    if (usePodman) {
        if (System.getenv("DOCKER_HOST").isNullOrBlank()) {
            environment("DOCKER_HOST", "unix://${podmanSocketFile.absolutePath}")
        }
        if (System.getenv("TESTCONTAINERS_RYUK_DISABLED").isNullOrBlank()) {
            environment("TESTCONTAINERS_RYUK_DISABLED", "true")
        }
    }
}

// M1-08: opt-in run of the real-keychain tests. Deliberately not wired via dependsOn/finalizedBy from
// `test` or `check`. No Docker api.version pin: that workaround is only for Testcontainers.
tasks.register<Test>("keychainTest") {
    description = "Runs the data-rabbit tests tagged \"keychain\". Touches the real OS keychain and may prompt; run on demand."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("keychain")
    }
    outputs.upToDateWhen { false }
}
