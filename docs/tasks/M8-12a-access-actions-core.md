# Access actions in the migration format: model, redaction, runner rules (core)

Milestone: M8    Depends on: M8-04, M5-08 (merged); owner decisions D1 to D5 of `M8-12-access-migration-actions.md` (D4 and D5 matter here; both decided 2026-11-11)    Size: M

## Goal
`core` can parse, write, dry-run and apply (against a fake executor) migration files with the four access actions; a hash is never visible anywhere but the file; the runner checks the administrator tag and Full control per action.

## Scope
In (module `core`, `core-testing`, plus the one permission-check change):
1. `MigrationAction.DeclareVhost`, `DeclareUser`, `SetPermissions`, `SetTopicPermissions` (names `declareVhost`, `declareUser`, `setPermissions`, `setTopicPermissions`; fields in the umbrella; `DeclareUser.passwordHash: SecretValue`). New `val isBrokerLevel: Boolean get() = false` on the interface, true for these four; `vhost` of `DeclareVhost` is its name, of `DeclareUser` the empty string (not used for broker-level actions). `description` ("Declare user svc"), `detail` ("administrator, password hash hidden": tags and the fixed text, never the value). No delete action; a parser test asserts the codec table has none.
2. Codecs in `ActionCodecs` style (one codec per action, `MigrationParser` reads and `rawJson` writes): the hash is read into a `SecretValue` and written back unchanged; an unknown `hashingAlgorithm` is refused at parse time with a fixed text (allow-list sha256, sha512, md5; no value echoed); a `declareUser` without `hashingAlgorithm` is refused. Parse and validation error strings never contain the hash (EchoableName rule extended: the hash is never echoable).
3. Raw view redaction (D4 = A): a pure function `redactedRaw(rawJson)` that replaces the value of every `passwordHash` with `hash hidden`, used by `MigrationsViewModel` wherever the raw file text is shown. The checksum stays over the real file.
4. Runner (`MigrationRunner.dryRun`, new parameter `userTags: Set<String>` after `vhost`, default `emptySet()`): per action. Read-only blocks everything as now. A topology action keeps the rules it has (configure permission, V1 vhost, destructive needs Full control). A broker-level action is allowed iff `"administrator" in userTags` and level `FULL_CONTROL`; the configure permission and the V1 vhost rule are not applied to it; its blocked reasons are "Creating virtual hosts, users and permissions needs the administrator tag" and "... needs the level Full control". `canApply` = all allowed; `blockedReason` names the first. The early returns for "no configure permission" must now block only topology actions.
5. The tags: `PermissionCheckResult.Checked` gets `val userTags: Set<String> = emptySet()` (and `DeclaredByUser` the empty set); the permission checker reads them from `GET /api/whoami` (`tags` array, 3.9+/4.x; a comma string is also accepted) in the same check, no new request kind; `MigrationsViewModel` passes `userTags` to `dryRun`.
6. Executor contract: `ActionResult` gets the existing Conflict path for the new actions (the fake returns `Conflict(reason)` where the scenario says so); `ApplyOutcome.resourceName()` gets the new actions (user name, vhost name; permission: the user name). `FakeTopologyMigrationExecutor` learns the four actions: an in-memory broker model with vhosts, users (hash kept only inside the fake for comparison), permissions, topic permissions; identical = no-op, different = Conflict (the reason names what differs: "tags", "password hash", "patterns"; never a hash), an existing vhost: for each setting the action CARRIES (`description` not empty, `tags` not empty compared as a set, `default queue type` present) a difference is `Conflict("The virtual host <name> exists with a different description")` (or tags, or default queue type; the values are not echoed); settings the action does not carry are not compared; a `declareVhost` of `/` is `NoOp` without any comparison (D5 = B, `/` exempt; the runner also never reads it).
Out: the generation (`M8-12b`), the real executor (`M8-12c`), any UI beyond the existing Migrations screen rows and the disabled reasons.

## Acceptance
- BDD `18`, tags `@m8-12a @fake-broker`, suite `RunAccessMigrationAcceptanceTest` (steps `AccessMigrationSteps`; the Background's definitions file and `new-eu` held connection via the existing fakes): "A dry run never shows a hash", "No hash appears in any log, report text, error string or MCP output" (log capture with a sentinel hash over parse, dry run, apply with a failing action, history, the MCP dry-run tool output), "Access actions are disabled without the administrator tag", "... below Full control", "The access migration applies from an administrator profile of any virtual host", "Applying the access migration twice changes nothing the second time", "A user that exists with a different password hash is a conflict", "Permissions that exist with other patterns are a conflict", "A virtual host that exists with another description or tags is a conflict" (new), "Settings the export does not carry are not compared" (new), "The default virtual host is never created or compared" (new), "The migration never deletes a virtual host, user or permission", "No MCP tool applies a migration ...". `10` and every existing migration test stay unedited and green.
- Unit: codecs round trip with a sentinel hash (file keeps it, `toString`, dry-run text and `detail` do not), old files parse as before, the algorithm allow-list, `redactedRaw`, `dryRun` table (administrator x level x vhost x configure for a mixed file).

## Design notes
Strategy per action kind in the codec table and the fake; sealed results (existing `ActionResult`). Hash comparison stays inside the executor (never in `core`). No hash through `SessionState` or `SecretStore`. The hash `SecretValue.expose()` has two callers only: the codec writer and (M8-12c) the executor request builder; a source-scan guard test asserts it.

## UX
`docs/ux/Migrations.dc.html` rows: "Declare user svc (administrator), password hash hidden"; the two disabled reasons under "Apply pending". No new screen.

## MCP
None new (see umbrella).

## Definition of done
Tests first and passing, no hash outside the file, spec updated.

## Status
- Step 1 (architect, 2026-11-11): briefed. Owner decisions FINAL (2026-11-11): D4 A (raw view hides hashes), D5 B (an existing vhost with a differing carried setting is a Conflict; `/` exempt, to be confirmed). Ready for step 2. 13 `@m8-12a` scenarios (3 new for D5).
