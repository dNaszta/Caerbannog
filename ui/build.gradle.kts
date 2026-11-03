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
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.junit.jupiter:junit-jupiter:5.11.4")
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
