# Caerbannog

*Behind the rabbit.* A free desktop app for exploring **RabbitMQ Streams**: connect to a broker, list streams, and page through their messages by offset or time. It also shows quorum-queue topology and statistics, and can peek at queue messages without consuming them (see Safety).

Built with Kotlin and Compose Multiplatform (JVM desktop). Supports RabbitMQ 4.x; a RabbitMQ 3.x broker is supported as a read-only migration source.

## Features

- **Connections:** profiles with host, ports, virtual host and user; TLS on by default (a per-profile switch turns it off and then says "Not encrypted" everywhere); passwords stay in the OS keychain; a permission check runs before the first connection, and each profile can be limited to read-only, "declare and edit" or full control.
- **Streams:** list with first/last offset, approximate message count, publishers and consumers; super streams appear as one entry with their partitions; create and delete streams where your permissions allow.
- **Message browser:** start from the first message, the last page, an offset or a timestamp; next, previous and jump; filter inside a bounded scan.
- **Message details:** offset, timestamp, exchange and routing key (when the broker records them), properties and application properties; the body as text, pretty JSON or hex; copy body, offset or metadata.
- **Monitoring views:** a log tape of a stream's offsets and retention, sparklines and lag trends for publishers and consumers, a pipeline graph from inbound routes to consumers, and partition balance for super streams.
- **Publish:** send a test message to a stream, exchange or queue, or over MQTT with QoS, retain flag and headers (MQTT 5 user properties).
- **MQTT:** the broker's MQTT client sessions and subscriptions (needs the `rabbitmq_mqtt` plugin).
- **Topology and queues:** quorum queues, exchanges and bindings with statistics (and a bindings graph); declare and edit them where your permissions allow.
- **Migrations:** export and apply topology as versioned JSON migration files from a folder you choose; dry run first, history per connection, conflicts never overwrite anything.
- **Queue message inspection:** peek at the first messages of a queue through the management API in requeue mode; they are returned to the queue and shown as "Not acknowledged (requeued)".
- **Global search** across streams, queues and exchanges by name.
- **Migrate from RabbitMQ 3.x:** analyse a 3.x broker (live, or from an exported `definitions.json`), read a compatibility report, review the proposed 4.x fixes, generate ordinary migration files (including virtual hosts, users and permissions), and move the messages of a queue with a broker-side Shovel. See [Migrating from RabbitMQ 3.x to 4.x](#migrating-from-rabbitmq-3x-to-4x) and [`docs/MIGRATING_FROM_3X.md`](docs/MIGRATING_FROM_3X.md).
- **MCP server** (`mcp-server`): exposes profiles, stream and queue catalogs, inspection and the read-only 3.x tools to AI agents over stdio, with the same safety rules (see [MCP server](#mcp-server)).

## Safety

What the app guarantees, in plain words:

- **Browsing a stream changes nothing.** It uses short-lived unnamed consumers, so it never moves offsets or tracking on the broker.
- **The app never consumes your queue messages.** The only way it shows them is the requeue peek (`ack_requeue_true`): they go back to the queue, and are never acknowledged, deleted or purged. The one other way messages move is a broker-side Shovel that you start yourself (below); the app does not touch a message then either.
- **Passwords stay in your OS keychain** (and in memory while connected). They are never written to logs, error texts, migration files or MCP output.
- **No silent TLS fallback.** TLS is the default; if you turn it off for a profile, the app says "Not encrypted" on the connection chip, the profile card and the popup.
- **Permissions are checked before the first connection.** Without the configure permission the app is read-only for topology; without the write permission test publishing is off.
- **A RabbitMQ 3.x broker is read-only to the app.** Every request sent to a 3.x broker is a GET.
- **Agents cannot do the risky things.** No MCP tool creates or removes a Shovel, applies a migration or returns a password hash.

## Migrating from RabbitMQ 3.x to 4.x

Caerbannog can help you move an existing 3.x installation to a new 4.x broker. It reads the old topology, tells you what 4.x will not accept as it is, writes the fixes you accept as migration files, and can carry the messages of a queue across. It does **not** upgrade a broker in place, and it never changes the 3.x broker.

The short version:

1. **Connect** the 3.x broker (the app detects 3.x and treats it as a migration source only), or choose an exported `definitions.json`. Reading the live broker needs a user with the administrator tag.
2. **Read the compatibility report.** Findings are blockers (will not work on 4.x as it is), warnings (works, but behaves differently) and info. If classic queues would silently lose their messages on an in-place upgrade to RabbitMQ 4.3 or later, a red block at the top says so and names the queues; the recommended path is a new 4.x broker plus a migration plus a Shovel.
3. **Review** each item that needs a decision: accept the proposal (some need a tick, for example "Ordering by priority may change" when a classic queue becomes a quorum queue), pick an alternative, skip it, or replace it with your own actions.
4. **Generate** the migration files into your migrations folder. You get one topology file per virtual host and, if the export holds users and permissions, one access file.
5. **Apply** them from the Migrations screen on a 4.x connection, with a dry run first.
6. Optionally **move the messages** of a queue with a Shovel (next section).

**Users and permissions.** The access file declares virtual hosts, users (with their existing password hash passed through, so people keep their old passwords), permissions and topic permissions. `guest` is never migrated; users whose password hash uses an algorithm 4.x does not accept are listed as "not migrated" and need a new password set on the broker; permissions of such users are listed as left out. Applying the access file needs the **administrator tag** and the level **Full control** on the 4.x connection.

**The access file is a secret.** It holds password hashes. Anyone who has it can try to guess the passwords offline, so keep it out of git and shared or synced folders. The app writes it readable by its owner only and shows a warning when it is saved or exported.

Step-by-step how-tos (what you need, the steps, what you should see, what to do if it goes wrong) are in [`docs/MIGRATING_FROM_3X.md`](docs/MIGRATING_FROM_3X.md):

1. [Analyse a 3.x broker from a definitions file](docs/MIGRATING_FROM_3X.md#1-analyse-a-3x-broker-from-a-definitions-file)
2. [Analyse a live 3.x broker](docs/MIGRATING_FROM_3X.md#2-analyse-a-live-3x-broker)
3. [Review the report and generate migration files](docs/MIGRATING_FROM_3X.md#3-review-the-report-and-generate-migration-files)
4. [Apply the migration to a 4.x broker](docs/MIGRATING_FROM_3X.md#4-apply-the-migration-to-a-4x-broker)
5. [Migrate users, virtual hosts and permissions](docs/MIGRATING_FROM_3X.md#5-migrate-users-virtual-hosts-and-permissions)
6. [Move the messages of a queue with a Shovel](docs/MIGRATING_FROM_3X.md#6-move-the-messages-of-a-queue-with-a-shovel)
7. [Check that a Shovel finished, or remove a failed one](docs/MIGRATING_FROM_3X.md#7-check-that-a-shovel-finished-or-remove-a-failed-one)
8. [A suggested order for a blue-green move](docs/MIGRATING_FROM_3X.md#8-a-suggested-order-for-a-blue-green-move)

## Moving messages with a Shovel

From a live 3.x analysis you can move the messages of one queue into a queue on the 4.x broker. The app creates a **dynamic Shovel on the 4.x broker**, which pulls from the 3.x queue; the broker moves the messages, not the app. What you will see and should know:

- It **moves** messages. They leave the source queue once the destination confirms; this is not a copy and the app cannot undo it. The source queue itself is never deleted.
- A **dry run** (read-only) shows the message counts, whether the destination queue exists and its type, whether the Shovel plugins are on, and whether a Shovel of that name already exists.
- You **confirm by typing the source queue name**. The confirmation names the source and destination and repeats the points on this page.
- The destination queue must **already exist** on the 4.x broker (apply the migration first); the Shovel plugins (`rabbitmq_shovel`, `rabbitmq_shovel_management`) must be enabled; the 4.x user needs the policymaker tag to create and the monitoring tag to read the status (or the administrator tag for both), and the level "Declare and edit". **Removing** a Shovel needs "Full control".
- The Shovel stores the 3.x user's **credentials in clear text on the 4.x broker** until it finishes or is removed; any administrator of that broker can read them. By default it moves the messages that are there when it starts and removes itself; "Keep the shovel running" keeps it forwarding until you remove it.
- Progress shows while the screen is open. Leaving the screen stops the display, not the Shovel.
- If the Shovel fails (for example a wrong password), the app shows the broker's reason and removes the Shovel so no credentials stay behind; if it cannot (for example the level is too low), it tells you the Shovel remains and needs a Full-control user to remove it.

Details and limits: [`docs/MIGRATING_FROM_3X.md`](docs/MIGRATING_FROM_3X.md#moving-messages-with-a-shovel).

## MCP server

`mcp-server` speaks the Model Context Protocol over stdio, so an AI agent can look at your brokers with the same safety rules as the app. Tools:

| Tool | What it does |
| --- | --- |
| `caerbannog_list_profiles` | lists the saved connection profiles, without passwords |
| `caerbannog_check_connection` | tests reachability, TLS and permission levels of a profile |
| `caerbannog_list_streams`, `caerbannog_get_stream_details` | streams of a virtual host (counts, offsets, retention) and the details of one |
| `caerbannog_browse_stream` | reads a page of stream messages with an ephemeral, non-tracking consumer |
| `caerbannog_list_queues`, `caerbannog_list_exchanges`, `caerbannog_list_bindings` | topology of a virtual host, read only (queue messages are never read or purged) |
| `caerbannog_publish_test_message` | publishes one test message to a stream, exchange or queue |
| `caerbannog_list_mqtt_clients`, `caerbannog_publish_mqtt` | MQTT client sessions and subscriptions; publishes a test message over MQTT |
| `caerbannog_analyze_3x_definitions` | the 3.x compatibility report of a 3.x profile or of a definitions file, one page of findings at a time; read only |
| `caerbannog_list_shovels` | the Shovels of a 4.x connection with state and counts; never an address or credential |

There is no MCP tool that creates or removes a Shovel, applies a migration or returns a password hash. The server is started over stdio (`./gradlew :mcp-server:run`); wire it into your agent as a stdio command in the way your client documents.

## Known limits

- RabbitMQ 3.x support was measured against 3.13 only.
- The Shovel reaches the 3.x broker with the `amqps` scheme when the 3.x profile has TLS on, but TLS options (custom CA, verification settings) are not supported and not verified in v1; if the 4.x broker cannot verify the 3.x listener the Shovel fails and is removed. With TLS off the dialog says "Not encrypted".
- The access migration needs a full definitions export (the live broker, or a whole-broker file). A per-virtual-host export holds no users or permissions.
- "Readable by its owner only" for the access file is best effort: POSIX permissions where the system has them; on Windows it is not verified.
- There is no in-place upgrade support, and no way to choose single users in the access migration.
- The Windows installer (`.msi`) has not been built or run yet.

## Install

Installers are built by the [Package installers](.github/workflows/package.yml) workflow: macOS `.dmg`, Windows `.msi`, Ubuntu/Debian `.deb` and Fedora `.rpm`. Download them from the workflow run's artifacts, or from the release page once a `v*` tag is published.

The `.deb` and `.rpm` were verified as built in clean containers; the `.msi` has not been built or run yet.

## Run from source

Needs JDK 21 and, for the helper commands, [go-task](https://taskfile.dev) (`brew install go-task`, or `sudo dnf install go-task` on Fedora, where the binary is `go-task`).

```sh
task run            # or: ./gradlew :desktopApp:run
```

To try it against a real broker, start the throwaway dev broker (Docker or Podman) and fill it with messages:

```sh
task dev:up
task dev:seed -- --password dev-password
```

See [`dev/README.md`](dev/README.md) for the dev users and options.

## Build installers

jpackage cannot cross-build, so run each on its own OS:

```sh
task package        # .dmg on macOS, .msi on Windows, .rpm on Linux
task package:deb    # Debian/Ubuntu, on Linux
```

The `.rpm` needs `rpm-build` and `java-21-openjdk-jmods` on Fedora; the `.deb` needs `dpkg-dev` and `fakeroot`.

## Tests

```sh
task test           # fast suite, no containers
task test:broker    # live-broker tests (Docker or rootless Podman)
```

The project is built test-first: unit tests with fakes, BDD scenarios in `tests/bdd/features`, Compose UI tests, and Testcontainers tests for real broker behaviour.

## Project layout

| Module | Holds |
| --- | --- |
| `core` | domain models, view models, interfaces (no RabbitMQ imports) |
| `data-rabbit` | stream client, management API, MQTT publisher, profile and keychain storage |
| `ui` | Compose screens |
| `desktopApp` | `main()`, wiring, packaging |
| `mcp-server` | MCP server over stdio |

Design and decisions live in [`docs/spec.md`](docs/spec.md); task briefs in `docs/tasks/`; UX mockups in `docs/ux/`. Platform guides: [macOS](docs/DEVELOPING_ON_MACOS.md), [Fedora](docs/DEVELOPING_ON_FEDORA.md), [Windows](docs/DEVELOPING_ON_WINDOWS.md). Contributor and agent conventions are in [`CLAUDE.md`](CLAUDE.md).

## License

[MIT](LICENSE). Third-party components and their licenses are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
