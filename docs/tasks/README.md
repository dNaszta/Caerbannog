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
| M2-01b-session-single-state | One atomic public session state flow; remove the `StateFlow` inheritance waiver and the publish loop | M2 | done (architect: Accepted with follow-ups, step 5, 2026-11-04; branch `m2-01b-session-single-state`; merged into local `main`, not pushed, branch kept); follow-up F1 (document "observers must not throw") goes into M2-04; no Docker |
| M2-02-management-api-spike | Spike on a real broker (Testcontainers, TLS fixture): stream list fields, publisher and consumer fields, first/last offset source, stream search by name, super stream shape, non-admin view, Testcontainers deprecation; deliverable is knowledge in the brief Status and `docs/spec.md`, asserted by `@broker` spike tests | M2 | done (architect: Accepted with follow-ups, step 5, 2026-11-04; branch `m2-02-management-api-spike`; merged into local `main` 2026-11-04 together with the Q2 spike test stability fix (`m2-02c-spike-test-stability`), not pushed); findings in the brief Status and `docs/spec.md` "Management API facts"; S1 and S2 done, S3 open; needs Docker |
| M2-03-stream-gateway | Core `StreamCatalog` interface, fake, real management client for the stream list and stats (and stream client if the spike says offsets need it) | M2 | part 1 (`m2-03-connection-lifecycle`) done: architect Accepted with follow-ups (step 5, 2026-11-04), merged into local `main` (not pushed); follow-ups are the part 2 checklist in `M2-03-stream-gateway.md`; part 2 `m2-03-stream-catalog` starts from `main` after the merge |
| M2-TESTHYGIENE-01-undiscovered-tests | Audit all modules for tests JUnit silently never runs (non-Unit `@Test` return), fix them, add a permanent guard | M2 | briefed (2026-11-04, `M2-TESTHYGIENE-01-undiscovered-tests.md`); can run beside part 2; hidden failures handled as separate fix tasks |
| M2-03a-stream-stats-permissions | Mini-spike: stream client facts for M2-03: `queryStreamStats` for admin, monitoring, management and no-tag users (allowed and denied stream, exact exceptions), writes nothing, TLS with the profile CA without host name verification (D7), Failed credentials texts, unreachable stream port behaviour | M2 | done, architect review Accepted with follow-ups (2026-11-04, branch `m2-03a-stream-stats-permissions`, merged into local main, not pushed); spec and M2-03 brief updated; test developer; first step of M2-03; needs Docker |
| M2-04-stream-list-core | Stream list view model: derive counts, sort, filter by name and kind, page, refresh and `RefreshPolicy` (D3), empty state (scenarios in `03`) | M2 | briefed as an input note (`M2-04-stream-list-core.md`, refresh policy section added 2026-11-04), full brief after M2-03 |
| M2-05-stream-list-screen | Stream list screen per `docs/ux/Streams.dc.html`, app bar connection control (chip and popup, replaces the drawer card and the edit-panel Connect), refresh interval persistence, un-skip `Switch between connections` | M2 | input note with the chip UX (`M2-05-stream-list-screen.md`), full brief after M2-04; mockups updated 2026-11-04 |
| M2-06-publishers-consumers | Publishers and consumers tab (`04`), counts match the list | M2 | input note written (`M2-06-publishers-consumers.md`); full brief after M2-03, depends on M2-02 and M2-05 |
| M2-07-super-streams-in-list | Super streams as one entry with partitions (M2 part of `06`) | M2 | planned, depends on M2-04 |
| M2-08-topology-read-only | Topology screen (queues, exchanges, bindings, stats; M2 part of `09`) and the three `@needs-M2` text scenarios in `02`; may be split | M2 | planned, depends on M2-01 and M2-03 |
| M2-10-testcontainers-2x-migration | Optional: move broker tests to Testcontainers 2.x, drop the deprecated plugin call | M2 | briefed (optional, needs bump approval); no hurry |
| M2-09-gate-stats-match | `@gate-M2` scenario: statistics agree with the Management UI on a real broker | M2 | input note (`M2-09-gate-stats-match.md`); needs Docker |

## Later milestones (planned)

| ID | Title | Milestone | Status |
| --- | --- | --- | --- |
| M5-verify-host-name | Per-profile "Verify host name" switch for the management API and the stream client (default off, product decision D7 (a) of 2026-11-04); note in the connection panel is removed or changed when it lands | M5 | planned (hardening); brief not written |

## M2 decisions

Raised in the architect's M2 plan (2026-11-04). Spike decisions S1 to S3 live in `M2-02-management-api-spike.md`.

| ID | Decision | Status |
| --- | --- | --- |
| D1 | What "connected" means and how a connection is held (checked once, or a held session) | decided (b) (product owner, 2026-11-04): explicit Connect and Disconnect open and hold the clients; one held connection at a time (architect rule, see `docs/spec.md` "Connection lifecycle") |
| D2 | Reconnect behaviour after a lost connection | live again under D1 (b); proposed: state Failed("Connection lost") with a Retry button, last data kept and marked stale, no automatic reconnect in M2; awaiting product owner confirmation |
| D3 | Stream list refresh (manual, timed, or both) | answered in part (product owner, 2026-11-04): interval in seconds, set in the app bar connection control, plus manual Refresh and "Updated N s ago"; PROPOSED and awaiting confirmation: default Off, values Off/5/10/30/60 s, scope per app in a small `settings.json` (details in `M2-04-stream-list-core.md`, "Refresh policy"); does not block M2-03 or M2-04 start |
| UX1 | App bar connection control (fixed chip, top right, popup with connections, Connect/Disconnect, refresh interval); drawer Connection card removed; edit-panel Connect removed | decided direction (product owner, 2026-11-04); refined in `M2-05-stream-list-screen.md`; mockups updated |
| D4 | Where first and last offset come from (management API or stream protocol `queryStreamStats`) | decided (product owner, 2026-11-04): count = management `messages`, last written offset = first + messages - 1, first offset only from the stream protocol |
| D5 | When the stream client is used in production (only for browsing, or also for stats) | decided (b) (product owner, 2026-11-04): the M2 list shows first offset, so `stream-client` is a production dependency from M2-03 (version must pass the two-week age rule when added); first offset cell empty ("unavailable") when the stats call fails or is denied |
| D6 | Password during a session (the session never holds it today) | decided (a) (product owner, 2026-11-04): read from `SecretStore` once when the connection's clients are created, kept only in the `data-rabbit` client objects in memory, dropped when the connection is closed; never in `SessionState`, logs or error strings |
| D7 | TLS hostname verification for real data calls | decided (a) (product owner, 2026-11-04): keep M1 behaviour for M2 (CA honoured, host name NOT verified) with a one-line note in the connection panel; a per-profile "Verify host name" switch is the M5 task `M5-verify-host-name` |
| S1 | Test dependency `stream-client:1.12.0` for the M2-02 spike | decided (option A) |
| S2 | Testcontainers: reproduce on 1.21.3, trial 2.0.5 on a scratch branch, no bump without report | decided |
| S3 | Non-admin visibility of stream data | decided (product owner, 2026-11-04): S3a no management tag = clear message, no lists; S3b `management` tag only = lists with a fixed own-connections notice |
| N1 | Size column in the stream list has no data source | decided (c) (product owner, 2026-11-04): left blank until M3 |
| T1 | Testcontainers bump | no decision given; default stays "no bump now" (M2-10 optional) |
