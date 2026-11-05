import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike: `desktopApp` per docs/spec.md Module layout table - jvmMain, `main()`, window, menus,
// packaging.
//
// Build tool decision for this spike (docs/spec.md "Packaging" row, docs/tasks/M1-01...): the
// installed Kotlin CLI (`kotlin`, Homebrew v2.4.21) has no `new`, `run` or `package` subcommands at
// all - not the project-oriented Kotlin Toolchain the spec describes - so the project uses Gradle
// with the Compose Multiplatform Gradle plugin (`org.jetbrains.compose`) for build, run and
// packaging, per the spec's own fallback rule.
//
// M1-05: `main()` renders the real `ConnectionsScreen`; `createConnectionsViewModel` is the
// composition root (`core` for the view model, `data-rabbit` for the real implementations).
dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":data-rabbit"))
    implementation(compose.desktop.currentOs)
    // M2-05a: Dispatchers.Swing is the UI dispatcher the stream list view model runs on (the Compose Desktop UI thread).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    testImplementation(kotlin("test"))
    // M2-05c: CompositionRootStreamEventsTest drives the real stream list with the fake gateway and catalog of core-testing.
    testImplementation(project(":core-testing"))
    // M1-07: CompositionRootTest runs the suspend check with runBlocking.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.caerbannog.desktopapp.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Caerbannog"
            // jpackage requires MAJOR > 0 (dotted MAJOR[.MINOR][.PATCH]); this is a packaging-format
            // version, unrelated to any app-visible version string.
            packageVersion = "1.0.0"
        }
    }
}
