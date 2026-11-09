# Fixtures of M8-01 (definitions export from RabbitMQ 3.x)

- `definitions-3.13-full.json` and `definitions-3.13-vhost-v1.json`: copies of the M8-00 spike recordings
  (RabbitMQ 3.13.7, `GET /api/definitions`, 2026-11-09, S-A) in `data-rabbit/src/test/resources/fixtures/m8-00/`.
  The only change: every `password_hash` is replaced by a sentinel of the same length,
  `SENTINEL-HASH-<user>-XXXX...`, so tests can assert that no hash appears anywhere.
- `definitions-3.13-shovel-parameter.json`: HAND-BUILT (not recorded). The shape of a real export that holds a
  Shovel is not measured; the value shape follows spec decision 3. Its source URI carries `SENTINEL-PASSWORD`.

Do not paste real definitions here: they hold password hashes and may hold clear passwords in parameter values.
Read them with `DefinitionsFixtures.read(name)`.
