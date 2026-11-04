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
// `main()` renders `App()` from `ui`'s commonMain (the task brief's Design notes: a placeholder
// screen only, app name and version text, proving the window opens end to end).
dependencies {
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)

    // M1-05 (test side only): the composition-root test needs core's types on its classpath. The
    // production `implementation(project(":core"))` / `(":data-rabbit")` lines are the senior
    // developer's part of the task.
    testImplementation(project(":core"))
    testImplementation(kotlin("test"))
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
