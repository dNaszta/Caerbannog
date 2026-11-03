rootProject.name = "caerbannog"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// M1-01 spike: module skeleton per docs/spec.md "Module layout and tech stack".
// core and ui are Kotlin Multiplatform (commonMain) with only a jvm() target active for now.
// data-rabbit, desktopApp are JVM-only (source set `jvmMain` per the spec table, implemented here
// as plain `kotlin("jvm")` modules since neither is ever expected to need other KMP targets -
// see docs/spec.md "Why JVM-only").
// acceptance-tests is a new, test-only module proposed by this spike's Design notes: it is NOT one
// of the four modules from the spec's module layout table.
include(
    ":core",
    ":data-rabbit",
    ":ui",
    ":desktopApp",
    ":acceptance-tests",
)
