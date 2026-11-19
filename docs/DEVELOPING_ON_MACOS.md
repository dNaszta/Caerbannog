# Developing and Building Caerbannog on macOS

This guide sets up a Mac (Apple silicon or Intel) to build, run and test Caerbannog, start a local RabbitMQ and build the `.dmg` installer. The Linux and Windows equivalents are [DEVELOPING_ON_FEDORA.md](DEVELOPING_ON_FEDORA.md) and [DEVELOPING_ON_WINDOWS.md](DEVELOPING_ON_WINDOWS.md).

---

## 1. What is Caerbannog?

Caerbannog ("behind the rabbit") is a desktop app for browsing **RabbitMQ Streams** and inspecting quorum-queue topology, built with Kotlin, Compose Multiplatform and Gradle. See the [README](../README.md) for the features and [`spec.md`](spec.md) for the design.

---

## 2. Tools and prerequisites

| Component | Purpose | Install |
| :--- | :--- | :--- |
| **Xcode command line tools** | git, and the tools `jpackage` uses to build the `.dmg` | `xcode-select --install` |
| **Homebrew** | package manager | <https://brew.sh> |
| **JDK 21 (Temurin)** | the build requires a JDK 21 toolchain (`jvmToolchain(21)`) | `brew install --cask temurin@21` |
| **go-task** | task runner for `Taskfile.yml` (binary is `task`) | `brew install go-task` |
| **Docker Desktop** | local dev broker and the `@broker` tests (Testcontainers) | `brew install --cask docker` |

IntelliJ IDEA (Community or Ultimate) is the recommended IDE: open the project folder and let it import the Gradle build.

### 2.1 The JDK: Gradle needs a 21 toolchain

The project pins its Kotlin toolchain to JDK 21 and has no auto-download resolver, so **a JDK 21 must be installed**. Your default `java` can be a different version; Gradle itself may run on it, but compiles with the 21 toolchain it finds.

```sh
/usr/libexec/java_home -V          # lists installed JDKs; a 21.x must be among them
java -version                      # the default, may be newer than 21
```

If a build fails with "No matching toolchain found", install Temurin 21 as above. To make 21 the default in a shell: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.

---

## 3. Verify your environment

```sh
git --version
task --version
docker version          # Docker Desktop must be running
/usr/libexec/java_home -V
```

---

## 4. Clone, build, run and test

```sh
git clone https://github.com/dNaszta/Caerbannog.git
cd Caerbannog
```

### Run the desktop app

```sh
task run                          # or: ./gradlew :desktopApp:run
```

### Run the fast test suite (no Docker needed)

```sh
task test                         # or: ./gradlew test
```


### Run the live-broker (`@broker`) tests

These start RabbitMQ in Testcontainers and need Docker Desktop running:

```sh
task test:broker                  # or: ./gradlew :data-rabbit:brokerTest
./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM7GateTest*'    # one class
```

Since `M2-10` (Testcontainers 2.0.5) `brokerTest` no longer pins `api.version`: the 2.x default starts on Docker Desktop 29.8.2 without it (see the Risks section of `spec.md`). If a Docker engine ever rejects that default, put `systemProperty("api.version", ...)` back in `brokerTest`. If Docker connection errors appear, check that Docker Desktop is running before anything else.

### Opt-in tests

```sh
task test:keychain                # touches the real macOS keychain and may show a prompt
task test:screenshots             # writes screenshots to ui/build/screenshots/
```

Both are excluded from `task test` on purpose.

---

## 5. Local RabbitMQ for development

`Taskfile.yml` uses `docker compose` on macOS by default, with throwaway credentials from `dev/` (never reuse them anywhere):

```sh
task dev:up                                        # start RabbitMQ 4 and wait until healthy
task dev:seed -- --password dev-password           # 1000 messages into dev-orders, dev-events, dev-audit (TLS)
task dev:seed:plain -- --password dev-password     # same, without TLS (ports 5552 and 15672)
task dev:seed -- --password dev-password --queues 1000 --vhost many   # 1000 quorum queues
task dev:reset                                     # remove the broker and its volume, start again
```

Details and the seed options are in [`dev/README.md`](../dev/README.md). On macOS the container engine is Docker Desktop (the Taskfile default); Podman is the Linux default and is not used here.

---

## 6. Packaging the installer (`.dmg`)

```sh
task package:dmg                  # or: ./gradlew :desktopApp:packageDmg
```

The `.dmg` is written under `desktopApp/build/compose/binaries/main/dmg/` (about 125 MB). It is **not signed or notarized**, so macOS Gatekeeper warns when you open it on another Mac; open it with right-click and Open, or sign and notarize it before distributing. The Windows `.msi` and the Linux `.rpm` and `.deb` cannot be cross-built from a Mac: use the CI workflow `Package installers` or a machine of that OS.

---

## 7. OS keychain

Connection passwords are stored in the macOS Keychain through `java-keyring`. The first save or read can show a Keychain access prompt; allow it. `task test:keychain` writes and deletes test entries in your real login keychain.

---

## 8. Troubleshooting

- **"No matching toolchain found" / wrong JDK:** install Temurin 21 and check `/usr/libexec/java_home -V` (see section 2.1).
- **Docker errors in broker tests or `task dev:up`:** start Docker Desktop and wait until it reports running; check `docker ps`.
- **Apple silicon vs Intel:** the broker images and the app run natively on both. The dev broker and `@broker` tests use multi-architecture images, so no emulation is needed on Apple silicon.
- **Gradle daemon problems or stale state:** `./gradlew --stop`, then retry.
