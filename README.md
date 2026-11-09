# Caerbannog

*Behind the rabbit.* A free desktop app for exploring **RabbitMQ Streams**: connect to a broker, list streams, and page through their messages by offset or time. It also shows quorum-queue topology and statistics, but never reads messages from queues.

Built with Kotlin and Compose Multiplatform (JVM desktop). Supports RabbitMQ 4.x.

## Features

- **Streams:** list with first/last offset, approximate message count, publishers and consumers; super streams appear as one entry with their partitions.
- **Message browser:** start from the first message, the last page, an offset or a timestamp; next, previous and jump; filter inside a bounded scan.
- **Message details:** offset, timestamp, exchange and routing key (when the broker records them), properties and application properties; the body as text, pretty JSON or hex; copy body, offset or metadata.
- **Publish:** send a test message to a stream, exchange or queue, or over MQTT with QoS, retain flag and headers (MQTT 5 user properties).
- **Topology:** quorum queues, exchanges and bindings with statistics; declare and edit them where your permissions allow; export and apply topology as versioned JSON migrations.
- **Global search** across streams, queues and exchanges by name.
- **MCP server** (`mcp-server`): exposes profiles, stream catalogs and inspection to AI agents over stdio, with the same safety rules.

Safety first: browsing uses unnamed stream consumers, so it never changes offsets or tracking on the broker. Passwords stay in your OS keychain. Connections use TLS by default; turning it off shows "Not encrypted" everywhere and never falls back silently.

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

Design and decisions live in [`docs/spec.md`](docs/spec.md); task briefs in `docs/tasks/`; UX mockups in `docs/ux/`. Platform guides: [Fedora](docs/DEVELOPING_ON_FEDORA.md), [Windows](docs/DEVELOPING_ON_WINDOWS.md). Contributor and agent conventions are in [`CLAUDE.md`](CLAUDE.md).

## License

[MIT](LICENSE). Third-party components and their licenses are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
