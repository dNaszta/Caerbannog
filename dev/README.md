# Local development broker (M2-12)

Developer tooling only, not part of the product or the installer. Everything here is **throwaway: the passwords
and the TLS files are test fixtures, never real, never reuse them anywhere**.

## Commands (need Podman or Docker, and go-task)

On Fedora, the `go-task` package installs the binary as `go-task` (run `go-task ...` or add `alias task=go-task`), and rootless `podman compose` and `@broker` Testcontainers require the user socket active (`systemctl --user enable --now podman.socket`) with the 5-second idle timeout disabled (`~/.config/systemd/user/podman.service.d/override.conf` setting `ExecStart=/usr/bin/podman $LOGGING system service --time=0`, followed by `systemctl --user daemon-reload && systemctl --user restart podman.socket podman.service`). See `docs/DEVELOPING_ON_FEDORA.md` for full Fedora setup.

`Taskfile.yml` defaults `CONTAINER_ENGINE` to `podman` (`podman compose`) on Linux (Fedora) and `docker` (`docker compose`) on macOS/Windows. To override the engine on any OS, pass `CONTAINER_ENGINE=podman|docker` or run the explicit `:podman` / `:docker` task variants (`dev:up:podman`, `dev:up:docker`, `dev:reset:podman`, `dev:reset:docker`).

| Command | What it does |
| --- | --- |
| `task dev:up` (or `dev:up:podman` / `dev:up:docker`) | Starts RabbitMQ (`docker.io/library/rabbitmq:4-management`) via `podman compose` (or `docker compose`) with TLS (default) and plain listeners and waits until it is healthy. |
| `task dev:seed -- --password dev-password` | Publishes 1000 messages into each of `dev-orders`, `dev-events`, `dev-audit` on `/`, so offsets and counts are not zero. |
| `task dev:seed:plain -- --password dev-password` | The same seed over plain (no TLS): stream 5552, management HTTP 15672, no CA. Passes `--no-tls` and no `--ca`. |
| `task dev:seed -- --password dev-password --queues 1000 --vhost many` | Declares 1000 quorum queues in the `many` vhost (the M2-04 refresh-cost trial); declares only queues, no publishing. No `--queues` means no queues. |
| `task dev:seed:plain -- --password dev-password --hold` (or `dev:seed`) | Seeds as usual, then keeps one named publisher (`dev-seed-publisher`) and one named consumer (`dev-seed-consumer`, stores its offset) connected per stream so the Publishers and consumers tab has rows. Press Ctrl+C to stop; the rows vanish on the next refresh. Run through `task` (Gradle), the stop ends with exit code 130 and a red "Failed to run task": that is the normal JVM exit after SIGINT, not an error; the connections were closed. |
| `task dev:reset` (or `dev:reset:podman` / `dev:reset:docker`) | `podman compose down -v` (or `docker compose down -v`: container and volume gone), then `up -d --wait`. Run it twice: same result. |

Seed flags: `--password` (required), `--user` (default `admin`), `--host`, `--stream-port`, `--management-port`,
`--vhost`, `--streams a,b`, `--messages N`, `--queues N`, `--max-age 7D` (RabbitMQ notation; sets a stream
retention policy `dev-seed-retention`), `--ca` (the Taskfile passes the fixture CA), `--no-tls` (a switch without a value: plain stream and
HTTP management, ports default to 5552 and 15672, `--ca` ignored). Messages are **appended on
every seed run**; queue declarations are idempotent. The password is never logged.

Behaviours worth knowing:

- `--streams x` **creates the stream when it does not exist**, then publishes into it.
- `--queues 0 --vhost many` declares no queues and **publishes into the streams of that vhost, creating them**.
- `--max-age` together with `--queues N` on a vhost other than `/` is **rejected** (such a run only declares queues
  and would ignore the retention policy); run them separately.
- **Practical maximum of `--queues`** (one import request, measured once on the dev broker): 10,000 queues take
  about 34 s, 50,000 about 3 min (the broker is busy for a while afterwards; a seed right after got a connect
  timeout), 100,000 fails with `400 Exceeded HTTP request body size limit` (so does 5,000,000). Treat **50,000** as
  the ceiling and use `task dev:reset` afterwards.
- A wrong `--ca` file shows up as `StreamException: null`; the tool then prints a hint that the file may not be the
  CA that signed the broker certificate.
## Image versions

The RabbitMQ images are pinned to explicit patch tags (M2-19), so a new release cannot turn `main` red without a commit.
The pins live in three places that must agree: `BrokerImages` in
`data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/RabbitContainers.kt` (all live tests and the spike ladder),
`dev/broker/docker-compose.yml` (4.x) and `dev/broker-3x/docker-compose.yml` (3.x). `BrokerImagePinTest` fails when a
literal or a floating tag appears anywhere else. A tag can be re-pushed by the registry; digests are not used.

Early warning: the non-blocking CI job "Live broker tests on the floating 4.x image" runs the regression half on
`rabbitmq:4-management` weekly and on demand. A red mark there blocks nobody; it means the next bump needs work.

To move to a newer release, deliberately:

1. Pick the new tag and check it exists: `docker manifest inspect docker.io/library/rabbitmq:<tag>` (or Docker Hub).
2. Change `BrokerImages`, the two compose files and the live-test-images line in the Risks of `docs/spec.md`.
3. Run `./gradlew :data-rabbit:brokerTest` and `./gradlew :data-rabbit:brokerSpikeTest` on a quiet machine, with the dev broker up for the first.
4. Fix what is red as its own commit per cause, with the broker's own words in the commit body.
5. Record newly measured facts in `docs/spec.md`.
6. Finish with one commit "Move the live tests to RabbitMQ x.y.z".

To try a version without a commit, edit the constant locally (there is no `BROKER_IMAGE` variable). To try the floating
tag once: `./gradlew :data-rabbit:brokerTest -PbrokerImage=rabbitmq:4-management` (regression half only).

The definitions in `dev/broker/definitions/` (`001-base.json`, `002-super-streams.json`) are **re-applied at every
boot**, in file name order; anything deleted by hand comes back, data is only cleared by `dev:reset`.

## Gate tests that need this broker

`RealBrokerM5GateTest` (`@gate-M5`, 5 tests) runs against this dev broker on `localhost`, not against Testcontainers. Start it first with `docker compose -f dev/broker/docker-compose.yml up -d --wait` (or `task dev:up`), otherwise it fails within a second with "The broker could not be reached". The M7, M8 and M9 gates start their own containers and need only Docker or rootless Podman.

## Ports and certificates

TLS (the default): stream **5551**, management API (HTTPS) **15671**, AMQP **5671**, MQTT **8883**. Plain, not encrypted:
stream **5552**, management API (HTTP) **15672**, MQTT **1883**; AMQP 5672 is not published. The CA is `data-rabbit/src/test/resources/tls/ca.pem` (the server cert and key next to it are mounted
read-only from there; one home, no copy). The host name is not verified, so `localhost` works.

## Users (all passwords: `dev-password`)

| User | Tag | Permissions (configure / write / read) | Notes |
| --- | --- | --- | --- |
| `admin` | administrator | `/` and `many`: all `.*` | The seed's default user. Full topology control. |
| `monitor` | monitoring | `/`: none / none / `.*` | Read-only state. |
| `manager` | management | `/`: none / `.*` / `.*` | No configure (read-only), may publish. |
| `notag` | none | `/`: all `.*` | The management API answers 401 "Not management user". |
| `multi` | management | `/` and `many`: all `.*` | Sees several vhosts (M2-04 vhost filter). |

## Topology

Vhosts `/` and `many` (empty until `--queues`). On `/`: streams `dev-orders`, `dev-events`, `dev-audit`; quorum
queues `dev-jobs`, `dev-mail`, `dev-retry`; exchanges `dev.events` (topic), `dev.commands` (direct), `dev.fanout`
(fanout) with bindings; super stream `orders` with partitions `orders-0`, `orders-1`, `orders-2`. On `many`: the
exchange `many.events`.

## The A1 profile for the app

Host `localhost`, stream port `5551`, management port `15671`, MQTT port `8883` (optional), vhost `/`, user `admin`, password `dev-password`,
CA file: the absolute path of `data-rabbit/src/test/resources/tls/ca.pem`. Check the list without the app:

```
curl --cacert data-rabbit/src/test/resources/tls/ca.pem -u admin:dev-password https://localhost:15671/api/queues/%2F
```

## The A2 profile for the app (plain, not encrypted)

Host `localhost`, stream port `5552`, management port `15672`, MQTT port `1883` (optional), vhost `/`, user `admin`, password `dev-password`,
**TLS switch off**; no CA file is needed. The app shows "Not encrypted" for this profile (the password travels in
clear text; fine for this throwaway broker, never for a real one). Seed it with `task dev:seed:plain -- --password
dev-password`; the TLS profile (A1, above) is seeded with `task dev:seed -- --password dev-password`. Check the list
without the app:

```
curl -u admin:dev-password http://localhost:15672/api/queues/%2F
```

## The 3.x broker (M8 hand trial)

A RabbitMQ **3.13** broker (`rabbitmq:3.13-management`, plain listeners only) to use as the **source** of "Migrate from 3.x",
with the 4.x dev broker above as the **destination**. Both run side by side: every host port differs.

| Command | What it does |
| --- | --- |
| `task dev:up-3x` | Starts the 3.13 broker (compose project `caerbannog-dev-3x`), waits until healthy, loads `dev/broker-3x/definitions.json` and publishes a few persistent messages with `dev/broker-3x/seed.sh` (needs `curl`). |
| `task dev:down-3x` | Removes the container and its volume. |
| `task dev:reset-3x` | `down -v`, `up -d --wait`, seed again. Use it after a Shovel moved messages. Run it twice: same result. |

Ports (host): AMQP **5673**, management API (HTTP) **15673**, stream **5553**. The 4.x broker keeps 5551, 5552, 15671, 15672, 5671, 1883, 8883.
Same engine rules as above (`CONTAINER_ENGINE=podman|docker`).

**The 4.x broker needs a restart once.** Its `enabled_plugins` now includes `rabbitmq_shovel` and `rabbitmq_shovel_management`, and its
compose file maps `host.docker.internal` to the host, so the Shovel on 4.x can reach the 3.x broker at `host.docker.internal:5673`. If the
4.x broker was started before this change, run `task dev:reset` (or `dev:up` after a `compose down`).

Users (all passwords `dev-password`): `legacy-admin` (administrator, `/` and `legacy`; not `admin`, which the 4.x dev broker already has with another password hash), `app` (management; configure only `^app\..*`; a topic permission on
`dev.events` for `orders.*`), `reader` (monitoring, read only), `worker` (no tag).

What the seed gives the analysis to find:

- **Version-1 classic queues** (the message-loss blocker for 4.3+): `orders` (declared without arguments, the 3.13 default), `invoices` (`x-queue-version: 1`),
  also `priority-jobs` (`x-max-priority: 5`), `audit.lazy` (`x-queue-mode: lazy`), `dead-letters`, `ha.reports`, `scratch-transient` (non-durable, deprecated on 4.x).
  `orders-v2` and `mail` (`x-queue-version: 2`, with dead-letter arguments) are the contrast: no blocker. `quorum-jobs` is a quorum queue.
  The version shows in the management API after the queue's first statistics, a few seconds after boot.
- **Mirrored (HA) policy** `ha-reports` on `^ha\.` (`ha-mode: all`), and a TTL policy `ttl-audit` on `^audit\.`.
- **Messages to forward**: `orders` 5, `invoices` 3, `priority-jobs` 4 (priorities 1 to 5), `ha.reports` 2, `quorum-jobs` 3.
- A second vhost `legacy` (queue `legacy-jobs`, fanout `legacy.events`), exchanges `dev.events` (topic), `dev.commands` (direct), `dev.dlx` (direct) with bindings.

Seeding is done only by this dev tooling (the 3.x management API with `legacy-admin`); the app never writes to a 3.x broker.

### Connection profiles for the trial

| | 3.x source | 4.x destination |
| --- | --- | --- |
| Host | `localhost` | `localhost` |
| Management port | `15673` | `15672` (plain) or `15671` (TLS, CA `data-rabbit/src/test/resources/tls/ca.pem`) |
| Stream port | `5553` (not needed for the migration) | `5552` / `5551` |
| Vhost, user, password | `/`, `legacy-admin`, `dev-password` | `/`, `admin`, `dev-password` |
| Use TLS | **off** (the app shows "Not encrypted"; fine for this broker) | off for the plain profile (A2), on for A1 |
| Level | read-only is enough: the app sends only GET requests to it | **Full control** / declare and edit (needed for apply and Shovel) |

Check without the app: `curl -u legacy-admin:dev-password http://localhost:15673/api/overview` (`rabbitmq_version` 3.13.x).

### Hand-trial checklist

1. `task dev:up-3x` and `task dev:up` (or `dev:reset` once, for the Shovel plugins); create the two profiles above.
2. Open the 3.x profile, **Migrate from 3.x**, live analysis: expect the message-loss block naming `orders`, `invoices`, `priority-jobs` and the other version-1 queues, the mirrored-queue finding for `ha.reports`, the transient queue finding.
3. Review: accept or skip each proposal. Generate, then **Save** to `dev/migrations-out/` (the app's migrations folder for the trial; everything in it except `.gitkeep` is gitignored, because the files hold password hashes: never commit them, and empty the folder between trials).
4. Open the 4.x profile and apply the topology migration (dry run first), so `orders`, `invoices` and the others exist there. A Shovel needs the destination queue to exist with the same name.
5. Forward one queue with the Shovel (for example `invoices`, 3 messages). The AMQP host prefilled for the 4.x broker to reach the 3.x one must be `host.docker.internal` and port `5673`; change it in the confirmation if the app proposes `localhost`. Expect "Moved about 3 of 3 messages"; the source queue stays.
6. Apply the access migration (users with hashes, vhost `legacy`, permissions, the topic permission) to 4.x; then check on 4.x: `curl -u app:dev-password http://localhost:15672/api/whoami`.
7. Start over: `task dev:reset-3x` (and `task dev:reset` for the 4.x broker).

A Shovel from this setup was verified by hand on 2026-11-12 (`PUT /api/parameters/shovel` on the dev 4.x broker with
`amqp://legacy-admin:dev-password@host.docker.internal:5673/%2F` as the source: 3 of 3 messages arrived and the Shovel removed itself).
