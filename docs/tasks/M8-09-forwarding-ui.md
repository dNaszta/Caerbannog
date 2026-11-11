# Forwarding screen: dry run, typed confirmation, progress, remove

Milestone: M8    Depends on: M8-07, M8-08, M8-06 (navigation)    Size: M

## Goal
From a queue of the 3.x source the user runs the dry run, confirms by typing the source queue name, watches the broker move the messages, and can remove the shovel.

## Why
Spec safeguards 1 to 3, 9, 10 and the UX rule for destructive actions.

## Scope
In: `ForwardingViewModel` in `core`, Compose dialog and progress view in `ui`: dry-run result table, blocked and warning states with reasons, confirmation text per spec (source and destination named, "messages are moved", "source queue is not deleted", "shovel keeps credentials on <dest> until removed", "Not encrypted" for plain), typed source queue name, `src-delete-after` choice with the `never` warning, progress on the refresh tick, Remove shovel with the Full control rule, no "forward all".
Out: starting from MCP.

## Acceptance
- BDD: all `17` scenarios that read "I choose to forward", "confirmation", "progress", "remove", "One queue at a time", "A plain profile says it is not encrypted".
- Compose UI tests with testTags; the confirm button stays disabled until the name matches exactly.

## Design notes
Unidirectional data flow. Address display uses `ShovelAddress` (no password). Dialog texts are constants in `core`.

## UX
Mockup `docs/ux/Migrate3x.dc.html` (forwarding section, from `M8-06`). Blocked until the owner reviews it.

## MCP
None (UI); see `M8-10` for what is exposed.

## Follow-up carried from M8-07 and M8-08 (architect, 2026-11-11)
- Move the fixed failure texts of `ForwardingService` ("The broker could not be reached", "The broker did not allow creating the shovel", "... removing the shovel") from the service into `ForwardingTexts` when this screen shows them (M8-07 review finding 1). Pin the exact confirmation strings here when the owner sees the dialog (M8-07 ruling 4). The screen texts for the blocks (plugins, locked, not encrypted) are the new `@m8-09` scenario.
- No name length rule exists (M8-08 step 5): do not add one in the dialog.

## Open points
P5 decided (default `queue-length`, `never` only with a warning); P4 (Full control to remove) and P8 (AMQP host and port asked in the dialog) decided 2026-11-09. The confirmation must state that the broker stores the credentials until the Shovel is removed (P1).

## Spike findings (M8-00, 2026-11-09)
Spike consequences: the confirmation shows the strengthened disclosure (source password stored in clear on the 4.x broker, readable by any administrator of it until the Shovel finishes or is removed); progress polls the Shovel status and shows `terminated` with its reason as a failure with a Remove shovel action; after completion the screen says how many messages stayed on the source because they were published meanwhile ("about N", an exact cut-over needs the publishers stopped); a missing destination queue is shown as blocked, never as a start. P14 is decided (2026-11-09): the dialog offers "Use another user for this Shovel" (name and password typed for this run, memory only, never saved, the password field is masked and cleared when the dialog closes); the default is the profile user.

## Definition of done
Tests first and passing, screenshots looked at, spec screens table updated.
