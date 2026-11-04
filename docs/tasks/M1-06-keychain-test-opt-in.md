# Move the real-keychain tests out of the default `test` task

Milestone: M1    Depends on: M1-02-connection-profiles (done)    Size: S

## Goal
`./gradlew test` never touches the developer's real OS keychain. The real-keychain round trips run only in an
explicit opt-in Gradle task (like `:data-rabbit:brokerTest` for the real broker).

## Why
Raised in the M1-05 review. `KeyringSecretStoreTest` (M1-02) and `KeyringRoundTripSpikeTest` (M1-01) use the
real `JavaKeyringSecretStore` on every `test` run. They clean up and use throwaway names, but they can show OS
prompts, fail on headless CI, and write to a personal keychain. The spec rule "secrets never in logs or plain
files" is not broken, but a default test run should have no side effects outside the build directory.

## Scope
In: tag or split these tests (for example a JUnit 5 tag `keychain`, excluded from `test`, run by a new
`:data-rabbit:keychainTest` task); add a Taskfile entry next to the broker one; document in `tests/bdd/README.md`
or the Taskfile comments when to run it (before releases, on a developer machine with an unlocked keychain).
Out: changing `JavaKeyringSecretStore`; Windows keychain verification (still open in the spec).

## Acceptance
- `./gradlew test` runs no test that constructs `JavaKeyringSecretStore` (check by reading and by the test report).
- `./gradlew :data-rabbit:keychainTest` runs both tests and they pass on a machine with a keychain.
- Existing behaviour tests (fake secret store in acceptance, `CompositionRootTest`) unchanged.

## Design notes
Follow the existing `brokerTest` pattern in `data-rabbit/build.gradle.kts`. No new library.

## UX
None.

## Open points
Whether CI should run `keychainTest` anywhere: decide when a CI runner with a keychain exists. Until then it is
a developer-run check.

## Definition of done
Tests still prove the real keychain round trip when run on demand; default run is side-effect free; README
index updated.
