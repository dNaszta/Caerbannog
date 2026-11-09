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

## Open points
P5 decided (default `queue-length`, `never` only with a warning); P4 and P8 (AMQP host and port asked in the dialog) proposed, awaiting the owner. The confirmation must state that the broker stores the credentials until the Shovel is removed (P1).

## Definition of done
Tests first and passing, screenshots looked at, spec screens table updated.
