# M5-08c: desktop wiring and the Migrations screen states (umbrella)

Milestone: M5    Depends on: M5-08a, M5-08b (both merged)    Size: M, delivered in two parts    Umbrella: `M5-08-real-migration-execution`

## Goal
The desktop app applies migrations for real, with the profile's own topology control level and virtual host, the migrations folder asked from the user, and every state of the new behaviour shown clearly. Profile rename and delete keep the history in step and say so when they cannot.

## Why
`MigrationsWindowContent` in `desktopApp/.../Main.kt` (main b99c778) wires a no-op executor, in-memory repositories and a hard-coded `FULL_CONTROL`; the composition root does not pass the history repository to `ConnectionsViewModel`; `MigrationsScreen` shows none of the new states.

## Owner decisions (2026-11-10, after reviewing the mockup)
1. **Progress while applying:** option A: "Applying <version>: action k of n" with a progress bar, fed by the runner's additive `onAction` callback.
2. **Folder shown:** option A: a row under the info line ("Migration files in <path>") with "Show folder" and "Change folder".
3. **Wording:** the texts proposed by the architect stay (the failure, conflict, not-recorded, history and unavailable texts of the mockup).
4. **Split:** two parts, in this order: `M5-08c1-migrations-wiring` (wiring and view model; a real apply already works end to end with the existing banners) and `M5-08c2-migrations-screen-states` (the 11 screen states, the Connections banner). Each is a normal loop round with its own branch.
Settled earlier and unchanged: the folder is asked on first use and remembered per machine; history stays in the app's own data; failure rule A; MCP read-only (`M5-09`).

## The mockup
`docs/ux/Migrations.dc.html` (the folder row in the frame, states 1 to 11 in section `states-m5-08c`) and `docs/ux/Main.dc.html` (the last card, the history-not-updated banner). Approved by the owner 2026-11-10.

## Distribution
| Part | Brief | Scenarios (`10`) | Content |
| --- | --- | --- | --- |
| c1 | `M5-08c1-migrations-wiring.md` | `@m5-08c1`: The topology control level of the connection is honoured; A failed apply shows which action failed and why; A conflict names the resource; An apply that could not be recorded says so; A connection that cannot apply migrations says why | typed `FailureKind`, `onAction` hook, view-model state, adapters, composition root and `Main.kt`, banners composed into the existing banner strings, first-use chooser dialog, manual DMG check |
| c2 | `M5-08c2-migrations-screen-states.md` | `@m5-08c2`: Applying shows its progress; The unreadable history can be moved aside from the screen; Deleting a profile whose history cannot be updated leaves it as it is; Renaming a profile whose history cannot be updated leaves it as it is; The folder can be changed at any time; and the five `@m5-08c1` scenarios re-bound to the Compose screen without editing them | the 11 screen states with test tags, the Connections banner, UI tests |
Suite filter of `RunTopologyMigrationsAcceptanceTest` (one suite, one glue package): now `@fake-broker and not @broker and not @m5-08c1 and not @m5-08c2 and not @m8-02`; `M5-08c1` removes `and not @m5-08c1`; `M5-08c2` removes `and not @m5-08c2`. (The old tag `@m5-08c` no longer exists in the feature: the filter `not @m5-08c` must be replaced in the same commit.)

## Definition of done (umbrella)
Both parts accepted; the manual DMG check recorded (in c1); the mockup matches the screen.

## Status
- Step 1 and 1b (architect): DONE 2026-11-10. Brief against main, mockup drawn, owner reviewed and decided (above), split into `M5-08c1` and `M5-08c2` (briefs written the same day). Next: `M5-08c1` step 2.
