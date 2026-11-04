# Task index

One line per task. Status: `in progress`, `done`, `blocked`. Keep in sync with each task brief.

| ID | Title | Milestone | Status |
| --- | --- | --- | --- |
| M1-01-skeleton-and-spike | Skeleton and M1 tooling spike | M1 | done |
| M1-TASKFILE-01-local-dev-commands | Taskfile for local developer commands (fast tests, @broker tests) | M1 | done |
| M1-02-connection-profiles | Connection profiles | M1 | done |
| M1-04-connections-screen-feedback-gaps | Connections screen: surface save errors, clear stale banners | M1 | done (accepted, round 1; merged into local `main`, not pushed) |
| M1-05-wire-desktopapp-connections | Wire desktopApp to the real Connections screen | M1 | done (accepted with follow-ups, round 1; merged into local `main`, not pushed; manual `:desktopApp:run` by human still pending) |
| M1-TASKFILE-02-test-task-runs-all-modules | `./gradlew test` / `task test` must also run core and ui `jvmTest` | M1 | done (architect: Accepted, 2026-11-04; merged into local `main`, not pushed) |
| M1-03-permission-check | Permission check: read the user's vhost permissions, lock topology and test publishing, safe fallback | M1 | done (accepted with follow-ups, round 1; merged into local `main`, not pushed) |
| M1-06-profile-declared-permissions | Declared permissions on the profile: persistence (back-compat), `ConnectionProfileInput` Keep/Clear/Set on Save, edit panel fields | M1 | done (accepted with follow-ups, round 0; merged into local `main`, not pushed; follow-up `M1-11`) |
| M1-07-permission-state-ui | Permission state in the UI: sidebar pill, banner, declared vs verified wording | M1 | not started (after M1-03, M1-05) |
| M1-09-permission-check-hardening | Permission check hardening: no exception leaves the check, thread-safe results | M1 | done (accepted with follow-ups, round 0; merged into local `main` with --no-ff, not pushed; adds `UnreadableCause.UNEXPECTED`) |
| M1-08-keychain-test-opt-in | Move real-keychain tests out of the default `test` task | M1 | briefed and confirmed; not started (renamed from M1-06 to avoid clash with M1-06-profile-declared-permissions) |
| M1-10-topology-level-on-new-connection | Choose the topology control level while creating a connection (apply on Save when editing; confirm Full control) | M1 | done (accepted with follow-ups, round 0; merged into local `main`, not pushed) |
| M1-11-declared-permissions-polish | Declared permissions polish: title wording, acceptance-tests keychain guard comment, mockup radios | M1 | briefed and confirmed; mockup radios done; title test written (red), built (step 3), waiting for verify and review |
