# Local development broker (M2-12)

Developer tooling only, not part of the product or the installer. Everything here is **throwaway: the passwords
and the TLS files are test fixtures, never real, never reuse them anywhere**.

## Commands (need Docker and go-task)

| Command | What it does |
| --- | --- |
| `task dev:up` | Starts RabbitMQ (`rabbitmq:4-management`) with TLS and waits until it is healthy. |
| `task dev:seed -- --password dev-password` | Publishes 1000 messages into each of `dev-orders`, `dev-events`, `dev-audit` on `/`, so offsets and counts are not zero. |
| `task dev:seed -- --password dev-password --queues 1000 --vhost many` | Declares 1000 quorum queues in the `many` vhost (the M2-04 refresh-cost trial); declares only queues, no publishing. No `--queues` means no queues. |
| `task dev:reset` | `docker compose down -v` (container and volume gone), then `up -d --wait`. Run it twice: same result. |

Seed flags: `--password` (required), `--user` (default `admin`), `--host`, `--stream-port`, `--management-port`,
`--vhost`, `--streams a,b`, `--messages N`, `--queues N`, `--max-age 7D` (RabbitMQ notation; sets a stream
retention policy `dev-seed-retention`), `--ca` (the Taskfile passes the fixture CA). Messages are **appended on
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

Only TLS is published: stream **5551**, management API (HTTPS) **15671**, AMQP **5671**. 5672 and 15672 are not
published. The CA is `data-rabbit/src/test/resources/tls/ca.pem` (the server cert and key next to it are mounted
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
