# Podman on Fedora local development and broker test support

Milestone: M2 (developer tooling and build support, not part of the shipped product)    Depends on: M2-12, M2-14b    Size: S

## Goal
A developer on Fedora Linux (with SELinux `Enforcing` and rootless Podman) can start, seed, and reset the local development RabbitMQ broker (`dev/broker/docker-compose.yml`) and run the real-broker Testcontainers suite (`:data-rabbit:brokerTest`) using `podman compose` and rootless Podman by default (while keeping Docker override support), with a step-by-step guide in `docs/DEVELOPING_ON_FEDORA.md`.

## Why
Caerbannog's local dev broker (`M2-12`) and `@broker` integration tests (`M1-01`) were wired assuming Docker Desktop on macOS or Windows. On Fedora Workstation (measured on Fedora 45, SELinux `Enforcing`, Podman 6.1.3), four platform differences block a developer using Podman out of the box:
1. **Healthcheck `root` `.erlang.cookie` ownership race on Podman:** In `rabbitmq:4-management`, `USER` is `root` (`uid 0`) and `docker-entrypoint.sh` switches to `gosu rabbitmq`. However, Podman's healthcheck runner executes `healthcheck.test` (`["CMD", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]`) immediately at container start as `root` before `rabbitmq-server` initializes `/var/lib/rabbitmq/.erlang.cookie`. The `root` healthcheck creates `.erlang.cookie` owned by `root:root` (`0400`), causing `rabbitmq-server` (running as `rabbitmq`) to crash on boot with `Error when reading /var/lib/rabbitmq/.erlang.cookie: eacces`. Running the healthcheck via `gosu rabbitmq` (`["CMD", "gosu", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]`) eliminates the race in both Podman and Docker.
2. **SELinux `Enforcing` on bind mounts:** `dev/broker/docker-compose.yml` mounts 7 configuration, certificate, definition, and plugin paths with `:ro` only. Without SELinux shared relabeling (`:ro,z`), rootless Podman containers (`container_t`) are denied read access (`EACCES`) to host files in the user's home directory (`user_home_t`), and RabbitMQ fails to boot.
3. **Podman unqualified image short names:** Fedora configures `/usr/share/containers/registries.conf.d/00-vendor.conf` with `short-name-mode="enforcing"`, and `rabbitmq` is not in `/usr/share/containers/registries.conf.d/000-shortnames.conf`. Using the fully qualified image name `docker.io/library/rabbitmq:4-management` works identically in both Podman and Docker without interactive TTY prompts.
4. **Rootless Podman socket & Testcontainers (`podman compose` preferred):** The product owner prefers `podman compose` as the default. `podman compose` (which delegates to `/usr/libexec/docker/cli-plugins/docker-compose`) and Testcontainers both talk to the user's rootless Podman API socket (`unix:///run/user/$UID/podman/podman.sock`, managed by `systemctl --user enable --now podman.socket`). `Taskfile.yml` should default to `podman compose` (`CONTAINER_ENGINE` defaulting to `podman`), and `:data-rabbit:brokerTest` should use the rootless Podman socket (`DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED=true`) whenever `podman.sock` is present (unless `CONTAINER_ENGINE=docker` / `-PcontainerEngine=docker` is explicitly requested).

## Scope
In:
- `dev/broker/docker-compose.yml`:
  - Qualify the image as `docker.io/library/rabbitmq:4-management` (still the floating 4.x management tag, compatible with both Podman and Docker).
  - Change all 7 host bind mounts from `:ro` to `:ro,z` so SELinux `Enforcing` labels them as shared container files (`container_file_t`, `s0`) without breaking non-SELinux hosts or Docker.
  - Change `healthcheck.test` to `["CMD", "gosu", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]` so the healthcheck never creates a `root`-owned `/var/lib/rabbitmq/.erlang.cookie`.
- `Taskfile.yml`:
  - Add a `vars:` block with `CONTAINER_ENGINE: '{{.CONTAINER_ENGINE | default "podman"}}'` and use `{{.CONTAINER_ENGINE}} compose -f dev/broker/docker-compose.yml ...` in `dev:up` and `dev:reset` (per product owner preference: "i prefer podman compose").
  - Add explicit convenience tasks `dev:up:podman`, `dev:reset:podman`, `test:broker:podman`, `dev:up:docker`, `dev:reset:docker`, and `test:broker:docker` so switching engines is a one-liner.
- `data-rabbit/build.gradle.kts` (`brokerTest`):
  - Support `-PcontainerEngine=podman|docker` and `CONTAINER_ENGINE=podman|docker`.
  - Default to rootless Podman whenever `$XDG_RUNTIME_DIR/podman/podman.sock` (or `/run/user/1000/podman/podman.sock`) exists (unless `docker` is explicitly requested) or when `podman` is explicitly requested.
  - When targeting Podman, configure the forked test JVM's environment with `DOCKER_HOST=unix://${podmanSocketPath}` (unless `DOCKER_HOST` is already set) and `TESTCONTAINERS_RYUK_DISABLED=true` (unless already set), while keeping the existing `systemProperty("api.version", "1.43")`.
- Documentation:
  - Create `docs/DEVELOPING_ON_FEDORA.md` (after the build changes are verified, per product owner instruction "create develop with fedora md after finish"), covering Fedora prerequisites (`dnf` packages, SDKMAN JDK 21 installation on Fedora 45, `go-task` binary and `alias task=go-task`), enabling `podman.socket`, running the desktop app, running fast and `@broker` tests with Podman, starting/seeding/resetting the local dev broker with `podman compose`, `libsecret` keychain notes, and Fedora/SELinux troubleshooting.
  - Update `dev/README.md` and `docs/spec.md` to reflect `podman compose` and rootless Podman support alongside Docker.

Out:
- Any changes to production code (`core`, `data-rabbit/src/main`, `ui`, `desktopApp`, `mcp-server`).
- Linux native installer packaging (`rpm`/`deb` targets in `desktopApp`; v1 packaging targets in `docs/spec.md` remain macOS `.dmg` and Windows `.msi`).
- Changing Testcontainers version (stays on 1.21.3 per `M2-10`).

## Acceptance
- BDD: none (developer tooling, build wiring, and documentation only; no product behavior changes).
- **C1 (Podman socket & compose up):** With `systemctl --user start podman.socket` active on Fedora (SELinux `Enforcing`), `go-task dev:up` (`podman compose` default) and `go-task dev:up:podman` start `caerbannog-dev-rabbitmq` via Podman, mount all TLS/definitions/plugin files without SELinux or `.erlang.cookie` `eacces` errors, and reach `healthy`. Both TLS (`https://localhost:15671/api/queues/%2F` with `ca.pem`) and plain (`http://localhost:15672/api/queues/%2F`) answer `200` with the 9 expected queues from `001-base.json` and `002-super-streams.json`.
- **C2 (Seed & reset with Podman):** `go-task dev:seed -- --password dev-password` and `go-task dev:seed:plain -- --password dev-password` succeed against the Podman-hosted dev broker; `go-task dev:reset` (`podman compose` default) tears down the container and volume and brings back a clean healthy broker with the 9 definitions queues.
- **C3 (`brokerTest` on Podman):** `go-task test:broker` / `go-task test:broker:podman` (`./gradlew :data-rabbit:brokerTest`) runs against rootless Podman (`/run/user/$UID/podman/podman.sock`) and passes on Fedora with SELinux `Enforcing`.
- **C4 (Docker override & fast test regression check):** `CONTAINER_ENGINE=docker go-task dev:up` / `go-task dev:reset:docker` still work with Docker, and `./gradlew test` (fast test suite) stays green with 0 failures.
- **C5 (Documentation):** `docs/DEVELOPING_ON_FEDORA.md` exists and is accurate; `dev/README.md` and `docs/spec.md` reflect the new commands.

## Design notes
- **Why `gosu rabbitmq` in `healthcheck.test`:**
  - `rabbitmq:4-management` runs PID 1 as `root` in `docker-entrypoint.sh`, which `chown`s `/var/lib/rabbitmq` and `exec`s `gosu rabbitmq rabbitmq-server`. Podman runs the first healthcheck probe immediately at container startup as `USER root`. Without `gosu rabbitmq`, `rabbitmq-diagnostics` creates `/var/lib/rabbitmq/.erlang.cookie` owned by `root:root` (`0400`) before `rabbitmq-server` starts, causing `rabbitmq-server` to fail with `eacces`.
- **Why `:ro,z` instead of `:ro,Z` in `dev/broker/docker-compose.yml`:**
  - `data-rabbit/src/test/resources/tls/*` is shared between the compose dev broker and test runs. `:z` applies the shared SELinux label (`system_u:object_r:container_file_t:s0`) so multiple containers or processes can read the files concurrently, whereas `:Z` applies a private MCS category restricted to a single container instance.

## UX
None.

## MCP
None (developer tooling, build scripts, and documentation only; no broker or application capability is added that an AI agent would call via `:mcp-server`).

## Open points
Closed in Step 2 / Step 2b:
- Product owner confirmed: `podman compose` is preferred (`CONTAINER_ENGINE` defaults to `podman`), and `docs/DEVELOPING_ON_FEDORA.md` is created after the implementation finishes.
- Spikes S-1 and S-2 completed and rulings R1–R4 issued by `solution-architect`.

## Definition of done
`dev/broker/docker-compose.yml`, `Taskfile.yml`, `data-rabbit/build.gradle.kts`, `docs/DEVELOPING_ON_FEDORA.md`, `dev/README.md`, and `docs/spec.md` updated; checks C1–C5 verified and recorded in `## Status`; `./gradlew test` green; no production code touched.

## Status
- **Step 1 (Brief, solution-architect, 2026-11-06):** Brief written (`docs/tasks/M2-15-podman-fedora-dev.md`), branch `m2-15-podman-fedora-dev`.
- **Step 2 (Tests first / RED state & Spikes S-1/S-2, test-developer, 2026-11-06):**
  - Recorded initial RED state for C1–C5 on unmodified `m2-15-podman-fedora-dev` (unmodified `podman compose up -d` exits 1; Podman tasks missing in `Taskfile.yml`; `brokerTest` lacks Podman socket & Ryuk env vars; `docs/DEVELOPING_ON_FEDORA.md` missing).
  - Spike S-1 uncovered the `.erlang.cookie` `eacces` race caused by Podman running `healthcheck` as `root` at container start and proved `["CMD", "gosu", "rabbitmq", "rabbitmq-diagnostics", "-q", "check_port_connectivity"]` + `:ro,z` fixes it cleanly (~2.3s boot to `healthy`, `git status` clean).
  - Spike S-2 confirmed `RabbitMQContainer("rabbitmq:4-management")` resolves over `podman.sock` and uses archive copy (`MountableFile`), and noted Fedora 45 `dnf` only ships OpenJDK 25 (JDK 21 `21.0.13.0+9-tem` installed via SDKMAN into `~/.sdkman/candidates/java/`).
- **Step 2b (Architect rulings R1–R4, solution-architect, 2026-11-06):** Approved `podman compose` default (`R1`), `gosu rabbitmq` healthcheck (`R2`), `:ro,z` + `docker.io/library/rabbitmq:4-management` (`R3`), and `docs/DEVELOPING_ON_FEDORA.md` after build (`R4`).
- **Step 3 (Build, senior-developer, 2026-11-06):** Updated `dev/broker/docker-compose.yml` (`docker.io/library/rabbitmq:4-management`, `:ro,z` on all 7 host bind mounts, `gosu rabbitmq` healthcheck), `Taskfile.yml` (`CONTAINER_ENGINE` default `podman`, plus `:podman` and `:docker` tasks), `data-rabbit/build.gradle.kts` (`brokerTest` rootless Podman auto-detection and `-PcontainerEngine` / `CONTAINER_ENGINE` support with `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED=true`), and `dev/README.md`. Self-check passed: C1 (`go-task dev:up` healthy in ~6.7s, 9 queues on TLS and plain), C2 (`dev:seed`, `dev:seed:plain`, `dev:reset`), C3 (`RabbitMqContainerSpikeTest` 1/1 and `RealBrokerReachabilityTest` 2/2 on Podman), and C4 (`./gradlew test` green, 0 failures).
- **Step 4 (Verify Round 1, test-developer, 2026-11-06):** Ran full multi-module suite and all `@Tag("broker")` classes on Fedora 45 + Podman (`--rerun-tasks`); 92/98 `@broker` tests passed, surfacing 4 platform findings: (1) missing root `gradle.properties` heap settings causing Kotlin daemon OOM on 32-thread CPU, (2) Fedora `podman.service` default `--time=5` idle timeout causing `Broken pipe` on pooled Testcontainers socket connections, (3) JDK 21.0.13 TLS alert prefix and Linux `epoll` `ConnectException` message text in `StreamClientSpikeTest.kt`, and (4) shared-stream `offset_lag` upper bound in `RealBrokerClientsTest.kt` (`S-A`).
- **Step 5 (Review Round 1, solution-architect, 2026-11-06):** Issued `Changes required` approving fixes for Findings #1–#4.
- **Step 3 / Step 4 (Round 2 fixes & full re-verify, senior-developer & test-developer, 2026-11-06):** Added root `gradle.properties` (`-Xmx2g`), configured `~/.config/systemd/user/podman.service.d/override.conf` (`--time=0`) and documented it in `dev/README.md`, and updated `StreamClientSpikeTest.kt` and `RealBrokerClientsTest.kt`. Re-verified: `./gradlew test :ui:screenshotTest --rerun-tasks` 48/48 tasks green (0 failures) and `./gradlew :data-rabbit:brokerTest` **98/98 green** on rootless Podman.
- **Step 5 (Final Review Round 2, solution-architect, 2026-11-06):** Verdict: **Accepted**. `docs/DEVELOPING_ON_FEDORA.md` created and `docs/spec.md` updated. Ready for product owner sign-off / merge into local `main`.
