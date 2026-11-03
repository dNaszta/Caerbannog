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

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("io.cucumber:cucumber-java:$cucumberVersion")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:$cucumberVersion")
    testImplementation("org.junit.platform:junit-platform-suite:1.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
