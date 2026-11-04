plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// M2-04: fakes shared by the test source sets of core, acceptance-tests and (later) ui. One copy, no srcDir
// sharing. Only test source sets may depend on this module: desktopApp and the installer must never see it.
dependencies {
    api(project(":core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
