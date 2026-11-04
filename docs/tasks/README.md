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
| M1-07-permission-state-ui | Permission state in the UI: sidebar pill, banner, declared vs verified wording, edit panel scrollbar and small layout fixes | M1 | done (accepted with follow-ups, round 2); merged into local main (not pushed); follow-ups in M1-12; M2 prerequisite on `ConnectionSession.open` in the spec; manual look by the user pending; branch `m1-07-permission-state-ui` |
| M1-12a-connections-layout-and-cards | Connections screen layout: drawer, app bar, two-column field grid with read-only ports, card and pill styling, pill test replaced, late `checkConnection` result guard, opt-in screenshot task | M1 | done (accepted with follow-up, round 2, 2026-11-04); follow-up R1 (Password hint text) is the first commit of M1-12b; manual look by the user pending; branch `m1-12a-connections-layout-and-cards`; merged into local `main` (not pushed) |
| M1-12b-connections-icons | Material icons (`material-icons-extended:1.7.3`, decided by the product owner) and password reveal toggle | M1 | done (accepted, architect review 2026-11-04, no blocker or major; minor: import order); mockup CA label updated to match; manual look by the user pending; branch `m1-12b-connections-icons`; merged into local `main` (not pushed) |
| M1-09-permission-check-hardening | Permission check hardening: no exception leaves the check, thread-safe results | M1 | done (accepted with follow-ups, round 0; merged into local `main` with --no-ff, not pushed; adds `UnreadableCause.UNEXPECTED`) |
| M1-08-keychain-test-opt-in | Move real-keychain tests out of the default `test` task | M1 | briefed and confirmed (sharpened 2026-11-04: tag `keychain`, `keychainTest` task, `task test:keychain`, no flag; proof by XML count 19 to 17; real run once only with user approval); red state recorded (step 2: 19 tests in data-rabbit incl. both Keyring classes, no keychainTest task, brokerTest 11); build done (step 3); verified (step 4, pass, no findings; keychainTest run once: 2 tests pass; literal mutation not run to avoid a second keychain touch); renamed from M1-06 to avoid clash with M1-06-profile-declared-permissions; done (architect: Accepted, step 5, 2026-11-04; branch `m1-08-keychain-test-opt-in`, merged into local main 2026-11-04 with --no-ff, not pushed) |
| M1-10-topology-level-on-new-connection | Choose the topology control level while creating a connection (apply on Save when editing; confirm Full control) | M1 | done (accepted with follow-ups, round 0; merged into local `main`, not pushed) |
| M1-11-declared-permissions-polish | Declared permissions polish: title wording, acceptance-tests keychain guard comment, mockup radios | M1 | briefed and confirmed; mockup radios done; title test written (red), built (step 3), step 4 verify passed (test engineer), done (accepted, round 0; merged into local `main`, not pushed) |

## Milestone M2 (started 2026-11-04; M1 accepted by the product owner the same day, see `docs/spec.md` "Milestone status")

| ID | Title | Milestone | Status |
| --- | --- | --- | --- |
| M2-01-connection-session | Connection session as the single source of truth: latest-started check wins, current connection, drawer card from the session | M2 | done (architect: Accepted with follow-ups, step 5, 2026-11-04); merged into local `main` (not pushed); branch `m2-01-connection-session` kept; follow-up `M2-01b-session-single-state`; no Docker |
| M2-01b-session-single-state | One atomic public session state flow; remove the `StateFlow` inheritance waiver and the publish loop | M2 | briefed (2026-11-04); do before M2-03 and M2-04; no Docker |
| M2-02-management-api-spike | Spike on a real broker (Testcontainers, TLS fixture): stream list fields, publisher and consumer fields, first/last offset source, stream search by name, super stream shape, non-admin view, Testcontainers deprecation; deliverable is knowledge in the brief Status and `docs/spec.md`, asserted by `@broker` spike tests | M2 | implemented 2026-11-04, ready for review (branch `m2-02-management-api-spike`); findings in the brief Status; S1 and S2 done, S3 open; needs Docker |
| M2-03-stream-gateway | Core `StreamCatalog` interface, fake, real management client for the stream list and stats (and stream client if the spike says offsets need it) | M2 | planned, depends on M2-02 and decisions D3 to D5 (see "M2 decisions"); brief not written yet |
| M2-04-stream-list-core | Stream list view model: derive counts, sort, filter by name and kind, page, refresh, empty state (scenarios in `03`) | M2 | planned, depends on M2-01 and M2-03 |
| M2-05-stream-list-screen | Stream list screen per `docs/ux/Streams.dc.html`, drawer navigation, un-skip `Switch between connections` | M2 | planned, depends on M2-04 |
| M2-06-publishers-consumers | Publishers and consumers tab (`04`), counts match the list | M2 | planned, depends on M2-02 and M2-05 |
| M2-07-super-streams-in-list | Super streams as one entry with partitions (M2 part of `06`) | M2 | planned, depends on M2-04 |
| M2-08-topology-read-only | Topology screen (queues, exchanges, bindings, stats; M2 part of `09`) and the three `@needs-M2` text scenarios in `02`; may be split | M2 | planned, depends on M2-01 and M2-03 |
| M2-09-gate-stats-match | `@gate-M2` scenario: statistics agree with the Management UI on a real broker | M2 | planned, last; needs Docker |

## M2 decisions

Raised in the architect's M2 plan (2026-11-04). Spike decisions S1 to S3 live in `M2-02-management-api-spike.md`.

| ID | Decision | Status |
| --- | --- | --- |
| D1 | What "connected" means and how a connection is held (checked once, or a held session) | open |
| D2 | Reconnect behaviour after a lost connection | open |
| D3 | Stream list refresh (manual, timed, or both) | open |
| D4 | Where first and last offset come from (management API or stream protocol `queryStreamStats`) | open; partly answered by S1 (stream-client allowed in tests), final answer from the M2-02 spike, question 3 |
| D5 | When the stream client is used in production (only for browsing, or also for stats) | open; depends on D4 and the spike |
| D6 | Password during a session (the session never holds it today) | open |
| D7 | TLS hostname verification for real data calls | open |
| S1 | Test dependency `stream-client:1.12.0` for the M2-02 spike | decided (option A) |
| S2 | Testcontainers: reproduce on 1.21.3, trial 2.0.5 on a scratch branch, no bump without report | decided |
| S3 | Non-admin visibility of stream data | open until spike findings |
