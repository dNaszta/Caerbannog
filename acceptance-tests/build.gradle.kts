plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike: `acceptance-tests` - a new module proposed by this task's Design notes to host the
// throwaway Cucumber-JVM proof scenario (and later real @fake-broker / @broker step definitions from
// M1-02 onward), kept separate from the canonical tests/bdd/features/ tree.
//
// Test-only by design: there is no src/main, only src/test. Cucumber-JVM with Kotlin step
// definitions, driven through the JUnit Platform (cucumber-junit-platform-engine), per
// docs/spec.md's Testing table - this is what lets `./gradlew test` discover .feature files the same
// way it discovers plain JUnit 5 tests, with no separate Cucumber CLI/runner to configure.
val cucumberVersion = "7.20.1"

// M1-02: step definitions and fakes for tests/bdd/features/01_connections.feature now live here too
// (RunConnectionsAcceptanceTest.kt), alongside the M1-01 scratch scenario (untouched, see its own
// file header). Depends on `core` (main, not test) for the real domain types/interfaces/view model
// this task adds (ConnectionProfile, ConnectionProfileRepository, SecretStore,
// BrokerReachabilityChecker, ConnectionCheckResult, CaCertificateValidator, ConnectionsViewModel) -
// per the task brief's Design notes, the fakes for ConnectionProfileRepository/SecretStore/
// BrokerReachabilityChecker live in THIS module, not in core or data-rabbit, so acceptance-tests
// does not need a dependency on data-rabbit for this feature file.
//
// M1-06 changes that for ONE scenario: "A profile saved before declarations existed has none" needs a real
// profile file, so acceptance-tests now depends on data-rabbit (project-internal, no new third-party
// library) and loads the M1-02 format fixture with the real JsonFileConnectionProfileRepository. Every
// other scenario still uses the in-memory fakes above.
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":core"))
    testImplementation(project(":core-testing"))
    testImplementation(project(":mcp-server"))
    // Guard: acceptance steps must not instantiate the keychain-backed JavaKeyringSecretStore.
    // Keyring and JNA native libs are on the classpath only because the file repository needs data-rabbit.
    // The real-keychain tests run only via :data-rabbit:keychainTest.
    testImplementation(project(":data-rabbit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // M2-04: the stream list steps run the view model in virtual time ("30 seconds pass" advances a test scheduler).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // M5-08a: the real migration executor of data-rabbit runs over a Ktor mock engine in front of FakeManagementBroker (core-testing).
    // Same versions as data-rabbit (no new library); test scope only.
    testImplementation("io.ktor:ktor-client-core:3.0.3")
    testImplementation("io.ktor:ktor-client-mock:3.0.3")
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// tests/bdd/features/ is the canonical Gherkin source (per tests/bdd/README.md); it is NOT copied
// into this module's own src/test/resources. Instead it is added as an extra resources root so the
// Cucumber JUnit Platform Engine can discover individual feature files from the classpath by name
// (see RunConnectionsAcceptanceTest.kt's @SelectClasspathResource). This intentionally exposes every
// file under tests/bdd/features/ (01 through 12) on the test classpath, but each Suite class only
// selects the one feature file it has glue for, so unimplemented features (02-12) are never run.
sourceSets {
    test {
        resources.srcDir(rootProject.projectDir.resolve("tests/bdd/features"))
    }
}

tasks.test {
    useJUnitPlatform()
}
