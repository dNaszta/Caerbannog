# Permission state in the UI (sidebar pill, edit panel banner, topology text)

Milestone: M1    Depends on: M1-03-permission-check, M1-05-wire-desktopapp-connections    Size: M

## Goal
The user can always see what the permission check found for each connection: broker-read, declared by the user, or
could not be read (safest mode), and what that locks.

## Why
Spec "Broker-read permission" and its UX rule: a declared result must never look like a verified one.

## Scope
In:
- Sidebar pill next to the connection: "Topology locked", "Topology unlocked", "Topology unlocked (declared)".
- Edit panel "Permission check" banner (`docs/ux/Main.dc.html`): verified style only for `Checked`; a neutral/warning
  style for `DeclaredByUser` with the fixed text "You declared that <user> can ...; the broker could not confirm it and
  still enforces its own permissions."; `Unreadable.userMessage` for safest mode.
- Topology screen locked explanation (`docs/ux/Topology.dc.html`) when no configure permission on the vhost.
- The Connections screen calls `saveProfileChanges` (from `M1-03`) instead of `updateConnection`, and the "Check
  connection and permissions" button runs the session check.
Out: any declare/delete screen (M5), stream list (M2).

## Acceptance
- BDD: `02_permission_check.feature` "A locked connection says so in the sidebar and on topology screens"
  (`@needs-M1-07`), "Declared permissions are shown as the user's own claim" (`@needs-M1-07`); remove the tags when
  steps exist. The topology-screen part needs a topology screen: if M2 has not delivered it, assert the pill and banner
  only and keep the topology text scenario split into a `@needs-M2` scenario (architect to adjust at start).
- UI state tests per state: checking, Checked, DeclaredByUser, Unreadable (each cause message), no declaration.

## Design notes
UI holds no broker code; it reads `session.permissions(name)` through the view model state. State in, events out.

## UX
`docs/ux/Main.dc.html`, `docs/ux/Topology.dc.html`.

## Open points
- Exact pill colors for the declared state: to be added to the mockup before building.

## Definition of done
Tests first and passing, no unrelated changes, README updated.
