# Make the documented test command run every module's fast tests

Milestone: M1    Depends on: none (any branch; touches build files and Taskfile only)    Size: S

## Status
- State: not started (briefed by the architect, M1-10 review).
- Round: 0

## Goal
`./gradlew test` and `task test` run the tests of `core` and `ui` (30 tests on the M1-10 branch) as well as
`acceptance-tests`, `data-rabbit` (fast, no `@broker`) and `desktopApp`. One command, no extra arguments.

## Why
Spec "Local dev commands" and every brief's Definition of done say `./gradlew test` is the full fast suite. It is
not: `core` and `ui` are Kotlin Multiplatform modules whose tests are `jvmTest`, and the lifecycle `test` task does
not reach them (`./gradlew test --dry-run` lists only acceptance-tests, data-rabbit, desktopApp). All view model
and Compose UI tests can pass unseen. Earlier briefs show the gap was partly worked around by hand: M1-02 and M1-04
reports name `core:jvmTest` and `ui:jvmTest` explicitly, but M1-05's report quotes plain `./gradlew test` with core 6
and ui 6, so counts there came from somewhere else; do not assume M1-03 or M1-05 verification covered core/ui via
the documented command. Re-run `:core:jvmTest :ui:jvmTest` once on those branches before merge (see Acceptance).

## Scope
In:
- Fix in Gradle, not only in the Taskfile: in `core/build.gradle.kts` and `ui/build.gradle.kts` register a `test`
  task that depends on `jvmTest` (for example `tasks.register("test") { dependsOn("jvmTest") }`; check it does not
  clash with an existing lifecycle task, and that `desktopApp`, which has none of this, stays as is). Rationale: the
  plain Gradle command and `task test` then agree, and later KMP modules copy the pattern. Taskfile `test` stays
  `./gradlew test`. If a Gradle-level fix proves awkward, the fallback is `Taskfile.yml`
  `./gradlew test :core:jvmTest :ui:jvmTest`, and the spec line must then say plain `./gradlew test` is not enough.
- Any later KMP module with tests gets the same wiring (note it in the build file comment).
- Update the `docs/spec.md` "Local dev commands" sentence if the chosen fix differs from the one assumed there.
Out: CI (none exists), `brokerTest` (stays separate), any test content change.

## Acceptance
- `./gradlew test --dry-run` lists `:core:jvmTest` and `:ui:jvmTest` (through `test`) besides the existing modules.
- `./gradlew test --rerun-tasks` and `task test` run core and ui tests; the report counts match the per-module
  runs. Mutation check: break one core test on purpose, `task test` fails, restore.
- Neither `JavaKeyringSecretStore` tests nor any `@broker` test is pulled in (see `M1-06-keychain-test-opt-in`).
- No BDD scenario is needed (build wiring).

## Design notes
Build files only. Check the same on `m1-03-permission-check` after merge order is known: it adds tests to core.

## UX
None.

## Open points
- Confirm which of M1-03 and M1-05 verification runs included core/ui; if unsure, re-run once before merging.

## Definition of done
Dry run and real run show core and ui tests; spec sentence correct; `docs/tasks/README.md` updated; no unrelated changes.
