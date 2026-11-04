# Connections screen layout pass (grid, app bar, drawer, icons)

Milestone: M1    Depends on: M1-07    Size: M

## Goal
The Connections screen matches `docs/ux/Main.dc.html` in structure: two-column field grid with the ports as fields, app
bar with the "New connection" button, the drawer, and icons (lock, verified_user, visibility toggle on the password).

## Why
Split from `M1-07` (2026-11-04): the small fixes (card, spacing, dim hints, scrollbar, clickable profile card, masked
password) are done there; these are larger and need a dependency decision.

## Scope
In: field grid (`FlowRow` or two-column rows), ports as read-only fields, app bar, drawer with the connection card,
icon set, password reveal toggle. Out: any behaviour change.

## Acceptance
- BDD: none (no behaviour). UI tests keep passing; add tests for the password reveal toggle and for the drawer text.
- Manual: visual match with the mockup, by the user.

## Design notes
Decide first: `compose.materialIconsExtended` (large) versus a few vector assets. Recommend a few vector assets (lock,
verified_user, visibility, visibility_off, wifi_tethering, delete, add): no new dependency.

## UX
`docs/ux/Main.dc.html`.

## Open points
Dependency choice above (product/architect decision before starting). Not started.

## Definition of done
Tests passing, no behaviour change, README row added, manual check recorded.
