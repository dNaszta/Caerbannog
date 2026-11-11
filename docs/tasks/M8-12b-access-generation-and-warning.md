# Access migration generation, the lists and the secret-file warning

Milestone: M8    Depends on: M8-12a, M8-12c (for the live proof), M8-06b (Generate); owner decisions D1, D2, D3    Size: M

## Goal
"Generate" in the Migrate from 3.x review also writes the access migration (when the export holds access data), numbered before the topology files, lists what was not migrated, writes the file owner-only and warns that it holds hashes.

## Scope
In:
1. `core`: `AccessGenerator` (pure, like `MigrationGenerator`): from the `DefinitionsDocument` and the report it builds the `MigrationFile` with `declareVhost` for every virtual host, `declareUser` for every user with a supported hash that is not `guest` (D2), `setPermissions` and `setTopicPermissions` for those users only (a permission of a user that is not declared is left out), in the order of the umbrella. Skipped users are reported with the reason: guest (D2), "set a new password on the broker" (blocker: no algorithm or an unsupported one, P12), "has no password hash" (the app never invents one). Tags and patterns verbatim. Empty when the document is a per-vhost export (the existing note stays). The generated file's `description` says "Access migrated from RabbitMQ 3.x ..." (existing description style).
2. `GenerationRequest`/`GenerationResult.Generated` gain the access file as the FIRST migration (version lower than every topology file of the same generation: the version chooser numbers the access file first) and `notMigrated: List<NotMigratedUser>` and `leftOutPermissions: List<...>` (names and reasons only, never a hash). The review stays unchanged (D1 = A: no access item is decidable; `ConversionReview` untouched).
3. Write: `FolderMigrationFileRepository.importFile` writes a file with `ownerOnly = true` when its migration holds a `declareUser` (every other file keeps `false`); the "Export the files" path (`ReportFileSaver`) also writes owner-only where the platform dialog allows, best effort. A POSIX test checks mode `600` of the saved access file and the default mode of a topology file.
4. UI (`ui/migrate3x`, existing generate result panel): the lines "Access: N virtual hosts, M users, P permissions", the "Not migrated" list (user names with the fixed reasons) and "Permissions left out", the secret-file warning in the warning tone with the text of D3, and the same warning again on "Export the files" (`migrate3x-access-warning` tag). The mockup `docs/ux/Migrate3x.dc.html` gets a section for it (architect, step 1 of this task, before step 2).
5. Report and review lines that mention a user show name, tags, algorithm and "password hash hidden" (existing report text; a test asserts no hash in the review state).
Out: per-user skip (D1 = B would be a new task), password entry, applying (the Migrations screen from `M8-12a/c`).

## Acceptance
BDD `18`, tags `@m8-12b @fake-broker`, same suite as `M8-12a`: "The access migration declares vhosts, users and permissions in order", "The review and the report show a user without the hash", "Saving a migration file that holds hashes warns the user", "Access needs the full export of the source", "Hash algorithms of the allow-list are carried over" (outline), "guest and users that need a manual password are not generated and are listed" (new), "Permissions of users that are not declared are left out and listed" (new). Unit: `AccessGeneratorTest`, version order, the lists, sentinel hash only in the file text. Compose test for the panel lines and the warning; screenshots by eye for the generate result with the warning.
Limits: `:core:jvmTest --tests '*Access*'`, `:ui:jvmTest --tests '*Access*'`, `:acceptance-tests:test --tests '*AccessMigration*'`, `:data-rabbit:test --tests '*FolderMigrationFile*'`.

## MCP
None.

## Status
- Step 1 (architect, 2026-11-11): briefed; waits for D1, D2, D3.
