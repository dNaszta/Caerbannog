# Make the documented test command run every module's fast tests

Milestone: M1    Depends on: none (any branch; touches build files and Taskfile only)    Size: S

## Status
- State: step 2 done (tests first, red established on unfixed `main` base); ready for the senior developer.
- Round: 0
- Branch: `m1-taskfile-02-test-task-runs-all-modules`, created from `main` (not from the M1-10 branch; see Base branch).
- Step 2, red evidence (no production code, no Gherkin; acceptance is by command output; no extra script or Taskfile
  target added, since the brief asks for none and forbids touching `Taskfile.yml`):
  - `./gradlew test --dry-run`: the only `test` tasks listed are `:acceptance-tests:test`, `:data-rabbit:test`,
    `:desktopApp:test`. `:core:jvmTest` and `:ui:jvmTest` are NOT listed (grep count of `jvmTest` = 0). Core and ui
    only appear as compile prerequisites. Exit 0, which is the bug.
  - `./gradlew test --rerun-tasks --continue`: BUILD SUCCESSFUL; XML results only under
    `acceptance-tests/build/test-results/test` and `data-rabbit/build/test-results/test`. Nothing for core or ui.
  - Baseline for the fixed `./gradlew test` (from `./gradlew test :core:jvmTest :ui:jvmTest --rerun-tasks --continue`,
    BUILD SUCCESSFUL, test-results dirs cleaned before the run; tests / failures / errors / skipped):
    | Module (task) | Tests | Fail | Err | Skip |
    | --- | --- | --- | --- | --- |
    | acceptance-tests (test) | 17 | 0 | 0 | 1 |
    | core (jvmTest) | 6 | 0 | 0 | 0 |
    | data-rabbit (test) | 8 | 0 | 0 | 0 |
    | ui (jvmTest) | 3 | 0 | 0 | 0 |
    | desktopApp (test) | no results written (no tests on `main`) | | | |
    Total 34 on `main`. The "30 tests" in the Goal is the count on the M1-10 branch (ui has 3 here), so compare per module
    against this table when verifying on this branch.
  - The fix is correct when: dry run lists both jvmTest tasks, and a plain `./gradlew test --rerun-tasks` yields the
    same per-module numbers as the table, with results under `core/build/test-results/jvmTest` and
    `ui/build/test-results/jvmTest`.

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
- No BDD scenario is needed (build wiring). Automatic demonstration, by command output in the status block:
  1. `./gradlew test --dry-run` contains `:core:jvmTest` and `:ui:jvmTest` (before the fix it does not).
  2. Mutation check: make one core test fail, `./gradlew test` exits non-zero naming `:core:jvmTest`; same for one ui
     test; restore both.
  3. `./gradlew :data-rabbit:brokerTest` and the Taskfile are unchanged (`git diff main -- Taskfile.yml` is empty).
- `git diff main --stat` shows only the two build files and docs.

## Base branch and carry-over
Base on `main`: `Taskfile.yml` (with `test: ./gradlew test`), `core/build.gradle.kts` and `ui/build.gradle.kts` are on
`main` unchanged in the relevant parts (core and ui have `jvmTest` only; no `test` task), so the bug reproduces there
and the fix does not depend on any M1-03/M1-05/M1-10 work. Create the branch from `main`, then copy only these docs
from commit `4fbb6fd` (on `m1-10-topology-level-on-new-connection`); do not cherry-pick the whole commit, which also
holds M1-10 review text and `docs/ux/Main.dc.html`:
- `docs/tasks/M1-TASKFILE-02-test-task-runs-all-modules.md` (this brief, new file): `git checkout m1-10-topology-level-on-new-connection -- docs/tasks/M1-TASKFILE-02-test-task-runs-all-modules.md`.
- `docs/tasks/README.md`: add only the row `M1-TASKFILE-02-test-task-runs-all-modules` (main's file lacks the M1-03, M1-06 and M1-10 rows; do not copy the file).
- `docs/spec.md` line 75 ("Local dev commands" row): add only the sentence starting "`task test` must run every module's fast tests". Do not take the M1-10 paragraph at spec line ~146 (topology level on the form).
Nothing else from the M1-10 branch is needed. This brief lives on the M1-10 branch only until the test developer copies it.

## Design notes
Build files only. Confirmed on this repo: `:core:tasks --all` lists `allTests`, `check` and `jvmTest` but no `test`, so
the root `./gradlew test` (which runs the same-named task in every project) cannot reach core or ui. Register `test` in
both KMP modules and make it depend on `jvmTest`, not `allTests`: `allTests` would pull in any future non-JVM target
(not wanted for a fast local run). No new plugin, dependency or build tool: this is a plain Gradle `tasks.register`.
No change to `Taskfile.yml`, so `task test` and `./gradlew test` stay identical. `check` and `jvmTest` behave as today. Check the same on `m1-03-permission-check` after merge order is known: it adds tests to core.

## UX
None.

## Open points
- Confirm which of M1-03 and M1-05 verification runs included core/ui; if unsure, re-run once before merging.
- Product decisions: none. No dependency or build-tool choice is introduced; if the fix needs one, stop and flag it.
- Spec wording: the row on line 75 is a sentence missing a full stop before "Installed"; the test developer rewrites
  it once the fix lands, to say that `test` in core and ui delegates to `jvmTest` (drop "does not reach").

## Definition of done
Dry run and real run show core and ui tests; spec sentence correct; `docs/tasks/README.md` updated; no unrelated changes.
