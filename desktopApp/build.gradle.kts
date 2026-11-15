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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    testImplementation(kotlin("test"))
    // Compose wiring tests of Main.kt's window contents (WindowContentWiringTest): same test API as the ui module.
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.material3)
    // M2-05c: CompositionRootStreamEventsTest drives the real stream list with the fake gateway and catalog of core-testing.
    testImplementation(project(":core-testing"))
    // M1-07: CompositionRootTest runs the suspend check with runBlocking.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "dev.caerbannog.desktopapp.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Rpm, TargetFormat.Deb)
            packageName = "Caerbannog"
            // jpackage requires MAJOR > 0 (dotted MAJOR[.MINOR][.PATCH]); this is a packaging-format
            // version, unrelated to any app-visible version string.
            packageVersion = "1.0.1"
            licenseFile.set(rootProject.file("LICENSE"))
            // Add required Java modules that are not included by default in the bundled JRE.
            // java.net.http is required by Ktor client for HTTP requests (HttpClient, HttpRequest, etc.)
            // Note: javax.net.ssl.* classes are in java.base in Java 11+, so no need for java.ssl module.
            modules("java.net.http")
            // Add JVM arguments to open packages for reflection access required by Paho MQTT client.
            // Paho uses reflection to access java.net.URI.userInfo, which is not opened by default.
            jvmArgs(
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/sun.security.ssl=ALL-UNNAMED",
                "--add-opens", "java.base/sun.net.www.protocol.http=ALL-UNNAMED"
            )
            // Application icon: original artwork for this project (source: desktopApp/icon/Icon.java), not the RabbitMQ logo
            macOS.iconFile.set(file("src/main/resources/rabbit.icns"))
            // Linux (Fedora .rpm, Debian/Ubuntu .deb): jpackage cannot cross-build, so these run on a Linux host.
            // The .rpm needs `rpm-build` installed (`sudo dnf install rpm-build`); the .deb needs `dpkg-dev` and `fakeroot`.
            linux {
                iconFile.set(file("src/main/resources/rabbit.png"))
                packageName = "caerbannog"
                menuGroup = "Development"
                appCategory = "Development"
                shortcut = true
                rpmLicenseType = "MIT"
            }
        }
    }
}
