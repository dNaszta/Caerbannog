plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

// M2-12: developer tooling, NOT part of the product. Nothing in core, ui, data-rabbit or desktopApp depends on it,
// and it depends on nothing of the product (data-rabbit's TLS and management classes are internal).
// Libraries: the same stream-client artifact and version data-rabbit already uses; JDK HttpClient for the
// management API; no argument-parsing library.
dependencies {
    implementation("com.rabbitmq:stream-client:1.12.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Tests parse the generated definitions body; same version the project already uses.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}
