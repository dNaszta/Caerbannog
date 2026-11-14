plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

// M1-01 spike: `ui` per docs/spec.md Module layout table - commonMain, no broker/network imports.
//
// Compose Multiplatform (Desktop) with Material 3, per docs/spec.md's tech-stack table. The Compose
// compiler is a separate Gradle plugin since Kotlin 2.0 (org.jetbrains.kotlin.plugin.compose),
// pinned to the exact Kotlin version in use to avoid compiler/IR mismatches.
//
// `App()` below is the one piece of real production code this spike needs (see the task brief's
// Design notes: "a single `@Composable fun App()` with no view model") - a placeholder screen only,
// proven by AppUiTest.kt via the Compose Multiplatform UI test API.
kotlin {
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.runtime)
            // M1-12b: Material icons (frozen last release; see docs/tasks/M1-12b-connections-icons.md). Import confined to ConnectionsIcons.kt.
            implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
            // M1-02: the Connections screen calls core's ConnectionsViewModel/ConnectionProfile etc.
            // - interfaces and a view model only, no RabbitMQ/network import ever reaches `ui` through
            // this (see docs/tasks/M1-02-connection-profiles.md's Design notes, module boundaries).
            implementation(project(":core"))
        }
        jvmTest.dependencies {
            // M8-06a: definitions file text builders and fixtures for the Migrate from 3.x screen tests (test scope only; core-testing depends on core alone).
            implementation(project(":core-testing"))
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:6.1.3")
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform {
        // M1-12a: the screenshot helper is opt-in (`./gradlew :ui:screenshotTest`), like keychainTest.
        excludeTags("screenshot")
    }
}

// Renders the Connections screen to PNGs in ui/build/screenshots/ for a by-eye comparison with docs/ux/Main.dc.html.
// Not wired from `test` or `check`. Same classpath as jvmTest; the working dir is the project dir (the test writes
// to user.dir/build/screenshots).
tasks.register<Test>("screenshotTest") {
    group = "verification"
    description = "Writes Connections screen screenshots to ui/build/screenshots/ (opt-in, not part of test)."
    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    workingDir = projectDir
    useJUnitPlatform { includeTags("screenshot") }
    outputs.upToDateWhen { false }
}

// Root `./gradlew test` (and `task test`) runs the same-named task in every project, but a Kotlin
// Multiplatform module only has `jvmTest`. This lifecycle `test` delegates to `jvmTest` (not `allTests`,
// so a future non-JVM target does not slow the fast local run). Any later KMP module with tests needs the
// same wiring. See docs/tasks/M1-TASKFILE-02-test-task-runs-all-modules.md.
tasks.register("test") {
    group = "verification"
    description = "Runs the fast JVM tests (delegates to jvmTest) so the root `test` task reaches this module."
    dependsOn("jvmTest")
}
