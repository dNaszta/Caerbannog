plugins {
    kotlin("jvm") version "2.4.21"
}

kotlin {
    jvmToolchain(21)
}

// M1-01 spike scaffold: `desktopApp` per docs/spec.md Module layout table - jvmMain, `main()`,
// window, menus, packaging.
//
// Deliberately bare for now: no Compose Multiplatform desktop application plugin/dependency, no
// `main()` yet. Acceptance item 2 ("desktopApp launches an empty window") is the senior developer's
// manual proof once the build tool decision (item 1) and the Compose wiring are in place - it is not
// expressed as an automated test per the task brief.
