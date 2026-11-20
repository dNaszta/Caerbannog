# Developing and Building Caerbannog on Fedora Linux (with Podman)

Welcome to Caerbannog! This guide walks you through setting up a Fedora Linux workstation (tested on Fedora 45 Workstation Edition with SELinux `Enforcing` and rootless Podman 6.x), building the project, running the desktop application, running the fast and real-broker test suites, and managing the local RabbitMQ development broker with `podman compose`.

---

## 1. What is Caerbannog?

Caerbannog ("behind the rabbit") is a desktop management application for **RabbitMQ Streams** and quorum queue topology, built with:
- **Kotlin**: Modern statically-typed programming language targeting the JVM (JDK 21 LTS).
- **Compose Multiplatform**: Declarative desktop UI framework by JetBrains based on Material 3.
- **Gradle**: Build automation tool (`./gradlew`) used to compile, test, and run the code.
- **go-task (`Taskfile.yml`)**: Convenient task runner for daily developer workflows (`go-task` / `task`).
- **Podman & `podman compose`**: Rootless container engine used by default for the local RabbitMQ development broker and Testcontainers `@broker` integration tests.

---

## 2. Tools & Prerequisites

| Component | Purpose | Fedora Package / Installation Source |
| :--- | :--- | :--- |
| **Git** | Source control | `sudo dnf install git` |
| **JDK 21 LTS (Temurin)** | Java Runtime & Compiler (required by `jvmToolchain(21)`) | Installed via [SDKMAN!](https://sdkman.io/) (`sdk install java 21.0.13.0+9-tem` or latest `21.x-tem`) |
| **Taskfile (`go-task`)** | CLI task runner (`Taskfile.yml`) | `sudo dnf install go-task` *(installs binary as `/usr/bin/go-task`)* |
| **Podman** | Rootless container engine for dev broker & Testcontainers | `sudo dnf install podman` |
| **Docker Compose CLI plugin** | Compose provider invoked by `podman compose` | `sudo dnf install docker-compose-plugin` *(or `podman-compose`)* |
| **IntelliJ IDEA** | Recommended IDE with Kotlin & Compose support | [JetBrains Toolbox](https://www.jetbrains.com/toolbox-app/) or [Download](https://www.jetbrains.com/idea/download/) |

---

## 3. Quick Setup on Fedora

### 3.1 Install System Packages via `dnf`

```bash
sudo dnf install -y git go-task podman curl unzip zip
```

> [!TIP]
> On Fedora, the `go-task` RPM installs the binary as **`go-task`** (rather than `task`). You can either run `go-task <command>` directly or add an alias to `~/.bashrc` (or `~/.zshrc`):
> ```bash
> echo 'alias task=go-task' >> ~/.bashrc
> source ~/.bashrc
> ```

### 3.2 Install JDK 21 (LTS) via SDKMAN!

Recent Fedora releases (such as Fedora 45) ship `java-25-openjdk-devel` in `dnf` rather than `java-21-openjdk-devel`. Because Caerbannog pins `jvmToolchain(21)`, install Eclipse Temurin 21 via **SDKMAN!**:

```bash
# 1. Install SDKMAN! (if not already installed)
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"

# 2. Install Eclipse Temurin JDK 21 LTS
sdk install java 21.0.13.0+9-tem   # or run `sdk list java` and pick the latest 21.x-tem
```

Gradle automatically discovers JDK 21 in `~/.sdkman/candidates/java/`.

---

## 4. Configuring Rootless Podman for Compose and Testcontainers

Both `podman compose` and Testcontainers (`:data-rabbit:brokerTest`) talk to the user's rootless Podman API socket at `/run/user/$UID/podman/podman.sock`.

### 4.1 Enable the User `podman.socket`

```bash
systemctl --user enable --now podman.socket
```

### 4.2 Disable the 5-Second Idle Timeout on `podman.service`

By default on Fedora, `/usr/lib/systemd/user/podman.service` starts `podman system service` with a **5-second** idle timeout (`--time=5`). During multi-minute `@broker` Testcontainers runs, a test class may communicate with RabbitMQ over ports `5551`/`15671` for more than 5 seconds without making a Podman API call; if `podman.service` exits in the meantime, Testcontainers' pooled Unix socket connection fails with `java.io.IOException: Broken pipe`.

Create a systemd user drop-in so `podman.service` stays alive (`--time=0`):

```bash
mkdir -p ~/.config/systemd/user/podman.service.d
cat << 'EOF' > ~/.config/systemd/user/podman.service.d/override.conf
[Service]
ExecStart=
ExecStart=/usr/bin/podman $LOGGING system service --time=0
EOF

systemctl --user daemon-reload
systemctl --user restart podman.socket podman.service
```

### 4.3 Verify Your Podman & SELinux Setup

```bash
# Check Podman and compose provider
podman --version
podman compose version

# Check SELinux (Enforcing is fully supported)
getenforce

# Verify the rootless Podman API socket responds
curl -s --unix-socket "/run/user/$(id -u)/podman/podman.sock" http://d/v1.43/info | head -c 200 && echo ""
```

---

## 5. How to Build, Run, and Test

### Run the Desktop Application

```bash
# Using go-task (or `task` if aliased):
go-task run

# Or using the Gradle Wrapper directly:
./gradlew :desktopApp:run
```

### Run the Fast Test Suite (No Container Engine Needed)

Runs all unit, UI, MCP, and in-memory BDD acceptance tests across all modules (`core`, `data-rabbit`, `ui`, `desktopApp`, `dev-seed`, `mcp-server`, `acceptance-tests`):

```bash
go-task test
# Or:
./gradlew test
```

### Run the Screenshot Test Suite

Writes Compose Multiplatform screen PNGs to `ui/build/screenshots/`:

```bash
go-task test:screenshots
# Or:
./gradlew :ui:screenshotTest
```

### Run the Real-Broker (`@broker`) Testcontainers Suite on Podman

`data-rabbit/build.gradle.kts` automatically detects `/run/user/$UID/podman/podman.sock`, points Testcontainers at `DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock`, and sets `TESTCONTAINERS_RYUK_DISABLED=true` (required on rootless Podman with SELinux `Enforcing`):

```bash
# Uses rootless Podman automatically when podman.sock is active:
go-task test:broker

# Or explicitly force Podman (even if Docker is also running):
go-task test:broker:podman
# Equivalent Gradle command:
./gradlew :data-rabbit:brokerTest -PcontainerEngine=podman
```

*(If you also have Docker installed and want to run `brokerTest` against Docker instead, run `go-task test:broker:docker` or `./gradlew :data-rabbit:brokerTest -PcontainerEngine=docker`).*

---

## 6. Local RabbitMQ Development Environment (`podman compose`)

On Linux (Fedora), `Taskfile.yml` defaults `CONTAINER_ENGINE` to `podman` (`podman compose`), while on macOS and Windows it defaults to `docker` (`docker compose`), and provides explicit `:podman` and `:docker` task variants on every platform. The compose file (`dev/broker/docker-compose.yml`) is pre-configured for Fedora with SELinux `Enforcing`:
- Uses the fully qualified image `docker.io/library/rabbitmq:4.4.1-management` (satisfying Fedora's `short-name-mode="enforcing"`).
- Mounts all 7 configuration, TLS certificate, definitions, and plugin files with `:ro,z` so SELinux labels them with the shared `container_file_t` context.
- Runs the healthcheck via `gosu rabbitmq rabbitmq-diagnostics -q check_port_connectivity` so Podman's initial root healthcheck never creates a `root`-owned `/var/lib/rabbitmq/.erlang.cookie`.

### Start the Local Dev Broker

```bash
go-task dev:up
# Or explicitly:
go-task dev:up:podman
```

This starts `caerbannog-dev-rabbitmq` in Podman and waits until it is `healthy`:
- **Stream TLS listener:** `localhost:5551` (CA: `data-rabbit/src/test/resources/tls/ca.pem`)
- **Stream plain listener:** `localhost:5552`
- **Management HTTPS UI & API:** `https://localhost:15671` (user: `admin`, password: `dev-password`)
- **Management HTTP UI & API:** `http://localhost:15672`

### Seed the Broker with Streams, Queues, and Live Clients

```bash
# Publish 1,000 messages into each of dev-orders, dev-events, dev-audit over TLS:
go-task dev:seed -- --password dev-password

# Same over plain (unencrypted, port 5552 / 15672):
go-task dev:seed:plain -- --password dev-password

# Keep a named publisher and consumer connected per stream until Ctrl+C:
go-task dev:seed -- --password dev-password --hold

# Generate 1,000 quorum queues in the `many` vhost (for refresh-cost testing):
go-task dev:seed -- --password dev-password --queues 1000 --vhost many
```

### Reset the Broker

Wipes the container and its `rabbitmq-data` volume and recreates a clean broker from `dev/broker/definitions/`:

```bash
go-task dev:reset
```

*(To use Docker instead of Podman for the dev broker, run `go-task dev:up:docker` / `go-task dev:reset:docker` or pass `CONTAINER_ENGINE=docker`).*

---

## 7. OS Keychain on Fedora (`libsecret` / GNOME Keyring)

Caerbannog stores connection passwords in the OS keychain via `java-keyring` (`libsecret` on Fedora GNOME Workstation).
- The default `go-task test` suite uses in-memory fakes and **never touches your real keychain**.
- To run the opt-in keychain integration tests on an unlocked desktop session:
  ```bash
  go-task test:keychain
  ```

---

## 8. Troubleshooting on Fedora & Podman

| Symptom | Cause | Solution |
| :--- | :--- | :--- |
| `dial unix /run/user/1000/podman/podman.sock: connect: no such file or directory` | The user `podman.socket` systemd unit is not active. | Run `systemctl --user enable --now podman.socket`. |
| `java.io.IOException: Broken pipe` mid-way through `./gradlew :data-rabbit:brokerTest` | `podman.service` exited after its default 5-second idle timeout (`--time=5`) while a test was talking to RabbitMQ. | Add `~/.config/systemd/user/podman.service.d/override.conf` with `--time=0` (see **Section 4.2**) and run `systemctl --user daemon-reload && systemctl --user restart podman.socket podman.service`. |
| `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces` | A custom healthcheck ran `rabbitmq-diagnostics` as `root` before `rabbitmq-server` initialized `.erlang.cookie`. | Keep `["CMD", "gosu", "rabbitmq", "rabbitmq-diagnostics", ...]` in `dev/broker/docker-compose.yml` and run `go-task dev:reset` to recreate the volume. |
| `Permission denied` reading `/etc/rabbitmq/enabled_plugins` or `/etc/rabbitmq/conf.d/*` | Host bind mounts lack the SELinux `:z` relabeling flag under SELinux `Enforcing`. | Ensure bind mounts in `dev/broker/docker-compose.yml` end with `:ro,z`. |
| `OutOfMemoryError: Java heap space` in Kotlin compiler daemon | High thread count on multi-core workstations exhausting default daemon heap. | Ensure root `gradle.properties` sets `org.gradle.jvmargs=-Xmx2g -XX:MaxMetaspaceSize=512m` and `kotlin.daemon.jvmargs=-Xmx2g`. |
