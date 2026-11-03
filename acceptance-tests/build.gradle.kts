plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike scaffold: `acceptance-tests` - a new module proposed by this task's Design notes to
// host the throwaway Cucumber-JVM proof scenario (and later real @fake-broker / @broker step
// definitions from M1-02 onward), kept separate from the canonical tests/bdd/features/ tree.
//
// Test-only by design: there is no src/main, only src/test. Deliberately no Cucumber dependency
// declared yet, so CucumberRunnerSpikeTest fails to compile until the senior developer wires
// io.cucumber:cucumber-java and io.cucumber:cucumber-junit-platform-engine (plus the JUnit Platform
// test task configuration to discover it).
