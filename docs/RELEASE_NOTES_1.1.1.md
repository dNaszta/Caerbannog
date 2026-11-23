# Caerbannog 1.1.1

Changes since 1.1.0. Source: `git log v1.1.0..main` and the task briefs in `docs/tasks/`. The safety rules in the README are unchanged: no new write path, no new read path for queue messages, no MCP tool was added.

## What you can see in the app

- **Forwarding with a Shovel on RabbitMQ 4.4.1 and later (`M8-15`).** From 4.4.1 the broker refuses a shovel whose destination is the broker itself (`dest-uri` `amqp://`) when the user is not an administrator. Before this release the screen showed only "The broker did not accept the shovel definition". Now the dry run stops with a plain message: only an administrator can create such a shovel, so use an administrator user for the connection or ask the administrator of the destination to create it. The version comes from one extra read-only `GET /api/overview`. If the version cannot be read, the start is tried and the same message is shown when the broker answers with that refusal. The destination URI is unchanged (still without credentials), an administrator forwards as before, and brokers older than 4.4.1 are not affected (owner decision of 2026-11-19, option 3: block, do not work around).
- **Forwarding screen defect fixed (`M2-19`, own commit).** When the broker refused the start but the dry run had not blocked, the screen went back to the confirmation with no text. It now shows the blocks the start returned.
- **Migrations screen layout at large fonts and narrow windows (`M2-21`, `M2-21b`).** The app bar buttons are 2 dp apart and keep to one line; the action row and the file bar wrap instead of running off the screen at font scale 1.5; the title gives way before the buttons are squeezed. Under 672 dp of page width the Migrations page stacks the list above the detail panel instead of squeezing the detail panel to about 100 px; the threshold follows the font scale (752 dp at scale 1.5, which is a window under 1032 px at that scale). The refusal text keeps its Copy button whole.
- **Deeply nested property values (`3e4cd99c`).** The text rendering of nested list, map and byte property values no longer recurses, so very deep values cannot overflow the thread stack (it failed at 1,000 levels on the Linux CI). The output for ordinary values is the same.

## Test and build infrastructure (not visible in the installers)

- **Live-broker tests split (`M2-18`).** `brokerTest` (gates and product classes) and `brokerSpikeTest` (classes that record broker facts, tag `spike`) are separate Gradle tasks with their own CI steps and time limits (25 and 45 minutes, CI job 80), a guard test for the naming rule, and a clock point for the Q3 test. Two CI dispatches on main were green (runs 45142874050 and 45149416954). This fixes the red "Live broker tests" job named in the 1.1.0 notes.
- **Pinned live-test images (`M2-19`).** All live tests and the dev compose files use explicit tags from one `BrokerImages` object (4.4.1, 3.13.7 and a ladder 4.0.9 to 4.3.8), with a guard test, a bump procedure in `dev/README.md` and one non-blocking CI job that runs against the floating tag. A developer note: `task dev:up` now starts the pinned version.
- **Two live tests follow RabbitMQ 4.4** (`053738b5`).
- **UI layout tests on CI (`M2-21`).** A font is pinned in the UI test rig (DejaVu Sans, with its copyright file under `ui/src/jvmTest/resources/fonts`), a wide-font layout guard and an intactness assertion were added, and the Migrations hang suspected in `M2-21b` was a test artefact (a stand-in that doubled the drawer), not a product defect.
- **M9 gate (`M9-13`)** was already in 1.1.0 (`cc4b1b40`) and the Testcontainers 2.0.5 move (`M2-10`) too; neither is new here.

## Other

- The windows packaging version is 1.1.1 (`packageVersion` in `desktopApp/build.gradle.kts`). The MCP server still reports the server version `1.0.0` in its initialize answer, as in 1.1.0; the owner has not answered whether to bump it.

## Not part of this release

- `M2-20` (clear failure message when the local dev broker is missing for `RealBrokerM5GateTest` and `RealBrokerMqttIntegrationTest`) is briefed, not built.
- `M2-18` is not fully closed: the red-step demonstration and the run on Podman are still open.
- The owner's formal acceptance of M9 is not recorded.
- The follow-up briefs listed in `docs/tasks/README.md` (for example `M9-08c` to `M9-08f`, `M9-09b`, `M9-12b`) are not built.
