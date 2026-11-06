# Local development broker (M2-12)

Developer tooling only, not part of the product or the installer. Everything here is **throwaway: the passwords
and the TLS files are test fixtures, never real, never reuse them anywhere**.

## Commands (need Podman or Docker, and go-task)

On Fedora, the `go-task` package installs the binary as `go-task` (run `go-task ...` or add `alias task=go-task`), and rootless `podman compose` and `@broker` Testcontainers require the user socket active (`systemctl --user enable --now podman.socket`) with the 5-second idle timeout disabled (`~/.config/systemd/user/podman.service.d/override.conf` setting `ExecStart=/usr/bin/podman $LOGGING system service --time=0`, followed by `systemctl --user daemon-reload && systemctl --user restart podman.socket podman.service`). See `docs/DEVELOPING_ON_FEDORA.md` for full Fedora setup.

`Taskfile.yml` defaults to `podman compose` (`CONTAINER_ENGINE=podman`). To use Docker instead, either pass `CONTAINER_ENGINE=docker` or run the `:docker` task variants (`dev:up:docker`, `dev:reset:docker`).

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
- The image tag `rabbitmq:4-management` is **floating on purpose**: the dev broker should follow the current 4.x
  release, so a breaking change shows up here early. It is not pinned by digest.

The definitions in `dev/broker/definitions/` (`001-base.json`, `002-super-streams.json`) are **re-applied at every
boot**, in file name order; anything deleted by hand comes back, data is only cleared by `dev:reset`.

## Ports and certificates

TLS (the default): stream **5551**, management API (HTTPS) **15671**, AMQP **5671**. Plain, not encrypted:
stream **5552**, management API (HTTP) **15672**; AMQP 5672 is not published. The CA is `data-rabbit/src/test/resources/tls/ca.pem` (the server cert and key next to it are mounted
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

Host `localhost`, stream port `5551`, management port `15671`, vhost `/`, user `admin`, password `dev-password`,
CA file: the absolute path of `data-rabbit/src/test/resources/tls/ca.pem`. Check the list without the app:

```
curl --cacert data-rabbit/src/test/resources/tls/ca.pem -u admin:dev-password https://localhost:15671/api/queues/%2F
```

## The A2 profile for the app (plain, not encrypted)

Host `localhost`, stream port `5552`, management port `15672`, vhost `/`, user `admin`, password `dev-password`,
**TLS switch off**; no CA file is needed. The app shows "Not encrypted" for this profile (the password travels in
clear text; fine for this throwaway broker, never for a real one). Seed it with `task dev:seed:plain -- --password
dev-password`; the TLS profile (A1, above) is seeded with `task dev:seed -- --password dev-password`. Check the list
without the app:

```
curl -u admin:dev-password http://localhost:15672/api/queues/%2F
```
