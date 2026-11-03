plugins {
    kotlin("multiplatform") version "2.4.21"
}

// M1-01 spike scaffold: `ui` per docs/spec.md Module layout table - commonMain, no broker/network
// imports.
//
// Deliberately bare: no Compose Multiplatform plugin/compiler, no Compose UI test dependency yet.
// src/jvmTest/kotlin/.../AppUiTest.kt exercises the (not yet written) `@Composable fun App()` and
// the Compose Multiplatform UI test API - both are missing right now, so it must fail to compile
// for two independent reasons until the senior developer wires the Compose plugin AND writes the
// placeholder App() composable (see the task brief's Design notes).
kotlin {
    jvmToolchain(21)
    jvm()
}
