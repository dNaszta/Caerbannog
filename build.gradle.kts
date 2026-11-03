// Root build file for the M1-01 skeleton.
//
// Plugin versions are declared once here (with `apply false`) and applied without a version in each
// subproject's own build.gradle.kts: the Kotlin Gradle plugin warns (and risks breaking) if the same
// version is loaded independently in more than one subproject's own `plugins {}` block. Each
// subproject still applies only the plugins it actually needs and owns its own dependencies/test
// wiring; this file stays otherwise empty.
plugins {
    kotlin("multiplatform") version "2.4.21" apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.9.3" apply false
}
