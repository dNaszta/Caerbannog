# Caerbannog 1.1.0

Changes since 1.0.1. Source: `git log v1.0.1..main` and the task briefs in `docs/tasks/`. The safety rules in the README are unchanged: the planner writes no file and no topology by itself, and no MCP tool was added.

## Topology planner, recipes, check, migrations

- **Topology planner.** A new screen builds a plan step by step: queues, exchanges, bindings, with a naming style that gives hints and derived names. The plan is a draft that you save as an ordinary migration file or dismiss. A review card shows it as a graph or as the migration JSON (read only, with Copy). Briefs `M9-01` to `M9-04`; the form refuses names RabbitMQ refuses (`amq.` prefix, queue names over 255 bytes, `M9-01b`).
- **Check.** A read-only check compares the plan with the broker (GET requests only) and says what will be created and what is already there. It follows the rule measured on a live 4.x broker for extra arguments (`M9-05`, `M9-05b`, `M9-06`).
- **Streams.** A stream step with an optional response stream, and optional MQTT routes for its answers and requests (`M9-07`, `M9-07b`, `M9-07c`). The note on the response stream says that RabbitMQ does not move messages into it by itself.
- **Policies.** A policy step, with notes for settings that would have no effect. Stream retention set by a policy is now read and shown in the streams list (`M9-08`, `M9-08b`).
- **Dead-lettering recipe.** Adds the dead-letter exchange, the dead-letter queue, the binding and, last, the policy on the source queue. At-least-once is offered only for a known quorum queue, with an explicit tick (`M9-09`).
- **MQTT routes.** A step turns an MQTT topic filter into one binding on `amq.topic`, with the topic translation shown in the step list, the graph and the Migrations rows. "Send a test message" on a route row opens a dialog with sample values and publishes over MQTT (`M9-10`, `M9-10b`).
- **Headers bindings.** A binding form for headers exchanges with typed headers and the match rule (`M9-11`).
- **Edit steps.** A pending migration file can be opened in the planner as steps, or copied into a new plan; a file the planner may not edit locks the planner (`M9-12`).
- **Migrations screen.** The action row and app bar keep to one line, and New plan opens the planner.

## Streams and policies

- The streams list shows retention that comes from a policy (`M9-08b`).
- Stream settings (retention, leader locator) are written, read first, and checked; the apply never overwrites a different setting (`M9-14c`).
- Message viewer, property filters and MCP stream tools render list, map and byte property values as readable text, for example the dead-letter death annotations (`M9-00b-c`).
- Consumer rows without a lag value are treated as still settling instead of showing a wrong number (`M2-17c`).

## MQTT

- The Publish MQTT dialog keeps Publish and Cancel pinned, with the notice and feedback banners above them and the error banner bounded (`M9-10b`, `M9-10e`).
- Without the write permission the four Publish MQTT controls and the older MQTT entry points are disabled (`M9-10e`).
- The AMQP publish dialog footer is pinned (`M9-10e`).

## Export

- The export keeps queue and stream arguments, flags, streams, policies and operator policies; it reads declarations, policies and operator policies from the management API (`M9-14`).
- The export note names only what is present, one entry per super stream, at most ten entries, and says why a setting cannot be written (`M9-14b`).
- Stream retention and leader locator are exported (`M9-14c`; the leader locator is always written as `x-queue-leader-locator`, owner decision O18).

## Other

- The hint popup bottom row was fixed (`M2-17d`).
- The windows packaging version is 1.1.0 (`packageVersion` in `desktopApp/build.gradle.kts`). The MCP server still reports the server version `1.0.0` in its initialize answer; it was not changed.

## Dependencies and tests

- The live-broker tests moved from Testcontainers 1.21.x to 2.0.5 (MIT; `org.testcontainers.rabbitmq.RabbitMQContainer`, plugins enabled through a copied `enabled_plugins` file) and the Docker `api.version` pin was removed (`M2-10`). Test-only: nothing in the installers changed because of it.
- Known: the CI job "Live broker tests" on main exceeds its 40 minute task timeout and is red; `M2-18` is briefed to fix it. It is not a product defect.
- Not part of this release: the owner's formal acceptance of M9, and the follow-ups listed in `docs/tasks/README.md` (`M9-08c` to `M9-08f`, `M9-09b`, `M9-12b` and others).
