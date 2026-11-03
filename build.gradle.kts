// Root build file for the M1-01 skeleton spike.
//
// Deliberately empty of plugin application / dependency wiring: each subproject below applies its
// own plugins. This spike's test scaffolding (tests/bdd ignored - see tests/bdd/README.md) lives in
// this file's sibling modules: core, data-rabbit, ui, desktopApp, acceptance-tests.
//
// The acceptance proofs for this task (docs/tasks/M1-01-skeleton-and-spike.md, "Acceptance" items 3-7)
// are expressed as test sources in those modules. As of this commit, each module's build.gradle.kts
// intentionally applies ONLY the bare Kotlin plugin (no test framework, no Testcontainers, no Compose
// UI test, no keychain library, no Cucumber) so the tests fail to *compile* - the right kind of red
// for this spike, since the spike's own deliverable is exactly that build wiring.
