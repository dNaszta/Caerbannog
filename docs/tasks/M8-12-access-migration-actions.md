# Access migration: vhosts, users, permissions in the migration format

Milestone: M8    Depends on: M8-00 (S-M, S-A, S-B), M8-04    Size: L

## Goal
The migration format and runner can declare virtual hosts, users (with an exported password hash and tags), permissions and topic permissions, safely: hashes are secret pass-through values, applying needs an administrator and Full control, conflicts never overwrite.

## Why
Spec "Users, virtual hosts and permissions in the migration", owner decision P2 (2026-11-09).

## Scope
In: actions `declareVhost`, `declareUser`, `setPermissions`, `setTopicPermissions` in `core` and the parser (no delete actions); `SecretValue` for `passwordHash` (redacting `toString`, redacted in the dry run, the migrations screen raw view, errors, history and logs; JSON on disk keeps the real value); executor in `data-rabbit` (management `PUT /api/vhosts`, `/api/users`, `/api/permissions`, `/api/topic-permissions`, bodies per S-M); idempotency (identical = no-op; different hash, tags or permission patterns = `Conflict` result that stops the apply and names what differs, never the hash; existing hash read with an administrator call into memory only); guardrail rule in the effective permission: administrator tag (from `GET /api/whoami`) plus Full control, checked per action; V1 exemption for these four actions (P10, decided) and a file-level scope so they travel in their own access migration; owner-only file write (`0600` where POSIX, best effort elsewhere, P13) and the secret-file warning on save and export; fakes.
Out: generating the files (`M8-05`), UI of the review (`M8-06`), any password entry or generation.

## Acceptance
- BDD: `18_migrate_access_from_3x.feature` (all scenarios); `10` unedited and green.
- Unit: old migration files unchanged; hash never in any `toString`, dry run text, error string or captured log (a log-capture test); `Conflict` on a different hash; file mode test on POSIX.
- Live (`@broker-3x`, needs S-M): a hash exported from 3.13 is accepted by 4.x and the old password logs in; a second apply is a no-op; a different hash is a conflict.

## Design notes
Strategy per action kind, sealed results (`Applied`, `NoOp`, `Conflict`, `Refused`). Hash comparison stays inside the executor. Do not route hashes through `SessionState` or `SecretStore`.

## UX
Dry run rows: "Declare user svc (administrator), password hash hidden". Warning dialog on saving or exporting a file with hashes.

## MCP
No tool returns a hash; an apply tool (if one exists) refuses migrations with these actions (spec MCP table).

## Open points
P10 (V1 exemption and separate file), P11 (`guest`), P12 (unsupported algorithm), P13 (owner-only file) are decided (2026-11-09); S-M answers the algorithm, `guest`, read-back and body shapes.

## Spike findings (M8-00, 2026-11-09)
Measured (S-M, spike results): allow-list of hashing algorithms is `rabbit_password_hashing_sha256`, `sha512`, `md5`; bcrypt or any other name is a blocker finding (3.13 accepted any name) with the manual step; the executor ALWAYS sends `hashing_algorithm` with `password_hash` (without it the login fails on both versions); 4.x silently overwrites a different hash, so compare before write: `GET /api/users/{name}` hides the hash on 4.4, so the comparison reads the target's `GET /api/definitions` through the redacting parser (it also contains Shovel source passwords in clear, which the parser drops) and keeps the hash in memory only; an imported hash keeps the old password working (live test: login on 4.x with the old password); the per-vhost export has no users, so the generator needs the full export; `guest` loopback behaviour outside the container image is not measured (S-P). `@needs-spike` removed from the scenarios of this brief that S-M answered.

## Definition of done
Tests first and passing, no hash anywhere outside the file and the executor request body, spec updated with the measured facts.
