# M10-01: Access view mockup

Milestone: M10    Depends on: owner answers AQ1, AQ2, AQ5 (may be asked with the first draft)    Size: S

## Goal
`docs/ux/Access.dc.html` exists in the Material 3 style of the other mockups, shows the four tabs with every state, and the owner has reviewed it.

## Why
Screens come from `docs/ux/`. No UI step of M10 starts without a reviewed mockup (as for M8 and M9). Spec "Access view (M10)", "Screen".

## Scope
In: the mockup for Who am I, Permissions (table, topic permissions, match preview form and verdict), Sign-in (OAuth 2 detected, not detected, could not be told, token user with and without resolved permissions), Clients (users with counts, a queue or stream with consumers and publishers, filter, notice for a `management` user, "settling" row). States per tab: loading, empty, error, locked with the reason, stale after a lost connection, "Not encrypted" banner. The honesty sentence for part 3 ("what RabbitMQ resolved, not the identity provider's setup") placed where it cannot be missed. The redaction state ("hidden" with a count). No control that changes anything (no edit, delete, close connection, no queue message action). Narrow window and font scale 1.5 states (the layout promises of the spec, M2-21).
Out: any code; wording the owner has not given (use clearly marked placeholder text and list the placeholders).

## Acceptance
- The file opens like the other `.dc.html` mockups; every state above is reachable.
- A list of placeholders and of questions the mockup raised is at the top of the file or in the brief's Status.
- Owner review recorded in the Status line of this brief and in `docs/tasks/README.md`.

## Design notes
Reuse the components of `Topology.dc.html` (virtual host switch, read-only banner), `Clients.dc.html` (publishers and consumers tables, settling) and `Main.dc.html` (drawer, connection chip). The drawer gets an item only if AQ1 says so.

## UX
It is the UX task.

## MCP
None (design).

## Open points
AQ1, AQ2, AQ5, AQ8 (what client properties may be shown).

## Definition of done
Mockup committed, reviewed by the owner, spec "Screens and UX" row for Access updated if the review changes it.
