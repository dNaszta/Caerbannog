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
No Gherkin scenario: these are plain JUnit tests of the OS keychain adapter, not product behaviour. The proof is build output.
Baseline on main (data-rabbit XML, checked 2026-11-04): default `test` runs 19 tests in 5 classes, including
`KeyringRoundTripSpikeTest` (1) and `KeyringSecretStoreTest` (1). `brokerTest` is expected to stay at 11 (confirm the baseline once before changing).
- Default run: `./gradlew :data-rabbit:test --rerun-tasks` gives 17 tests in data-rabbit; neither Keyring class has an XML file.
- `./gradlew test --dry-run` lists `:data-rabbit:test`, `:core:test`, `:ui:test` and does not list `keychainTest`; it is also not a dependency (`finalizedBy`, `dependsOn`) of `test` or `check`. The dry run proves wiring only, not which tests run: the XML count is the real proof.
- Mutation check: remove `"keychain"` from `excludeTags` of `tasks.test`; `:data-rabbit:test` must go back to 19 and the Keyring XMLs reappear. Restore afterwards.
- Opt-in: `./gradlew :data-rabbit:keychainTest` runs exactly 2 tests (the two Keyring classes), both pass, nothing else runs, no Docker needed.
- Keychain tests are not run by `brokerTest` (stays 11) and `brokerTest` does not include them.
- TASKFILE-02 rule unchanged: `./gradlew test` still reaches `core` and `ui` (`:core:test`, `:ui:test` in the dry run).
- `task --list` shows `test:keychain`; `task test` is unchanged.
- Existing behaviour tests (fake secret store in acceptance, `CompositionRootTest`) unchanged: `CompositionRootTest` never uses the real store and `acceptance-tests` never constructs `JavaKeyringSecretStore`.

## Decisions (architect, 2026-11-04)
- Tag name: `keychain`, a JUnit 5 `@Tag("keychain")` on the two classes, the same mechanism as `@Tag("broker")` and `includeTags` in `brokerTest`. Class-level tag, no split of files.
- `tasks.test` becomes `excludeTags("broker", "keychain")`.
- New `tasks.register<Test>("keychainTest")` mirrors `brokerTest` (group verification, same `testClassesDirs` and `classpath`, `includeTags("keychain")`). It copies neither the `api.version` pin (Docker only) nor any broker setting. Add `outputs.upToDateWhen { false }` so a repeat run really touches the keychain instead of reporting UP-TO-DATE.
- Taskfile: `test:keychain` next to `test:broker`, one line `./gradlew :data-rabbit:keychainTest`, desc says it touches the real OS keychain and may show a prompt.
- No `-PwithKeychain` flag: it would make the default run depend on a project property and bring back the side effect the task removes. Not needed.
- No CI wiring now (no CI exists).
- Guard comment in `acceptance-tests/build.gradle.kts`: leave as is. It already forbids `JavaKeyringSecretStore` in acceptance steps (M1-11); the new task does not change that. Optional: one clause "real keychain tests run only in `:data-rabbit:keychainTest`".
- Docs to change on the task branch by the senior developer (wording only, no behaviour): Taskfile header/desc; the "Local dev commands" row in `docs/spec.md` line 75 gets `task test:keychain` in the command list plus "(touches the real OS keychain, run on demand: before a release or after changing `JavaKeyringSecretStore`, on a machine with an unlocked keychain)"; a short note under the Tags table in `tests/bdd/README.md` that the keychain JUnit tests are outside the Gherkin tags and run only by `keychainTest`. The architect reviews the spec wording at step 5; the spec stays the only place for the decision.

## Who runs what, and the user in the loop
`keychainTest` writes and deletes a throwaway entry in the real login keychain of this machine. The first run on macOS may show a keychain prompt ("allow java to access ..."), which the user answers; it may fail if the keychain is locked. Agents must not run it on their own.
- Recommended: the TEST agent runs `:data-rabbit:keychainTest` ONCE, only after the user has said yes, with the user at the machine. Optional: if the user declines, the task is verified by the other checks above and the keychain run stays a pending manual check, written in Status.
- Senior developer: does not run it; proves wiring with `--dry-run`, XML counts, and `./gradlew :data-rabbit:keychainTest --dry-run`.
- If the run prompts or fails, record what happened in Status; do not retry in a loop.

## Tests-first plan (build wiring)
1. Test developer (step 2): no new test code. Record the red state: on current main `./gradlew :data-rabbit:test --rerun-tasks` shows 19 tests with both Keyring XMLs, and `./gradlew :data-rabbit:keychainTest` does not exist (task not found). Save counts in Status.
2. Senior developer (step 3): add the `@Tag("keychain")` on the two classes, `excludeTags`, `keychainTest`, Taskfile target, doc lines.
3. Test developer (step 4): reruns the acceptance list above including the mutation check, then the full `./gradlew test --rerun-tasks --continue` (expected: core 66, data-rabbit 17, ui 39, acceptance 38, desktopApp 3, as of M1-11 minus the 2) and, with user approval, the single keychainTest run.

## Design notes
Follow the existing `brokerTest` pattern in `data-rabbit/build.gradle.kts`. No new library or build tool. Update the stale header comments there that name the two tests. Do not change `JavaKeyringSecretStore`.

## UX
None.

## Open points
- CI for `keychainTest`: decide when a CI runner with a keychain exists. Until then a developer-run check.
- Product decision, answered: no `-PwithKeychain` flag (see Decisions). Reopen only if the user wants the keychain tests to run by default on their own machine.
- Windows keychain verification stays open in the spec.

## Definition of done
Tests still prove the real keychain round trip when run on demand; default run is side-effect free; README
index updated.

## Status
- State: briefed and confirmed against main (2026-11-04); not started. Branch to create: `m1-08-keychain-test-opt-in`, based on main.
- Next: step 2 test engineer records the red state (19 in data-rabbit, no `keychainTest` task); step 3 senior developer builds; step 4 verifies; real keychain run once only with user approval.
