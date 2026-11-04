// Build logic for the test discovery guard (see gradle/test-discovery-guard.gradle.kts). Uses only Gradle's own Kotlin DSL
// support: no third-party dependency.
plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}
