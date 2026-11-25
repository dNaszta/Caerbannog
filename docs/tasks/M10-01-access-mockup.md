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

## Status
Mockup drafted 2026-11-25 on branch `M10-01-access-mockup`; awaiting owner review (NOT reviewed). Owner decisions applied: AQ1, AQ4, AQ7, AQ9, AQ10. Working assumptions, not decisions: AQ2, AQ3, AQ5 (wording), AQ6, AQ8.

Placeholders (full list at the top of `docs/ux/Access.dc.html`): P1 preview verdict wording, label and prediction note (AQ5); P2 the honesty sentence; P3 the other Sign-in texts; P4 refused / token-expired text; P5 redaction line and "hidden"; P6 indirect publisher wording and "publishers could not be told"; P7 drawer icon and generic empty, error, locked and loading texts; P8 3.x text, locked reasons, declaration label.

Questions raised: Q1 Sign-in line on Who am I (needs OAuth detection on the first tab); Q2 user chooser list only, no free typing; Q3 keep "Configure" in the preview; Q4 should the connection chip also say "token expired"; Q5 a "Stop reading" button for paged connections; Q6 where the screen goes when the connection becomes 3.x; Q7 reuse of the settling wording; Q8 the "token" pill on token users; Q9 font scale 1.5 is only approximated in the mockup.

Not checked: opened only in headless Chrome (top of the page seen as a screenshot, the rest not looked at by eye); not opened in the design canvas (`support.js` is not in the repo); `canvas.json` not changed.
