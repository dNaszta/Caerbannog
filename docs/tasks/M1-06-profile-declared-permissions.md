# Declared permissions on the connection profile (model, persistence, edit panel)

Milestone: M1    Depends on: M1-03-permission-check (core `DeclaredPermissions`, session rule)    Size: S

## Goal
The user can say, per connection profile, whether their broker user has configure and write permission; the
declaration is saved with the profile, survives a restart, and a profile file written before this existed still loads.
Saving a changed declaration re-runs the permission check.

## Why
Spec "Broker-read permission" / "Declared permissions" (decision D). Only administrators can read permissions, so
without this a normal stream user is stuck in safest mode.

## Scope
In:
- `data-rabbit` `JsonFileConnectionProfileRepository`: add an optional field to the private DTO
  (`declaredPermissions` with `configure` and `write` booleans, default null). Persisted-format note: this is a schema
  addition. Because the DTO has a default of null and `Json` is not set to encode defaults, an old file loads as "no
  declaration" and a profile without declaration writes no new key, so the file stays unchanged for users who do not
  use it. No version number is needed for an additive optional field. Downgrade is not supported (`Json` does not
  ignore unknown keys; local-only app); say so in the DTO comment. Mapping both ways, as with `topologyControlLevel`.
- `core` `ConnectionsViewModel`: `setDeclaredPermissions(name, DeclaredPermissions?)` like `setTopologyControlLevel`
  (dedicated setter, not in `ConnectionProfileInput`); it saves and calls `session.reopen` when the value changed.
  Same for `setCaCertificate` (it changes the TLS input of the check): add the reopen there too.
- `ui` edit panel: two labelled switches or a three-state choice per right ("Not declared / Yes / No" is the simplest:
  not declared = null). Helper text, fixed wording: "Only used when the broker does not let this user read its own
  permissions. This is your own statement; the broker still enforces its permissions."
Out:
- Sidebar pill, banner and topology-screen wording (`M1-07`).
- Any change to how the check or the session decides (`M1-03`).

## Acceptance
- BDD (`tests/bdd/features/02_permission_check.feature`, `@fake-broker @needs-M1-06`, remove the tag when this starts):
  "Declared permissions are saved with the profile"; "A profile saved before declarations existed has none".
- Unit: repository round trip with and without a declaration; loading a JSON file literally written by M1-02 (copy a
  fixture of the old format); the file written for a profile without a declaration is byte-identical to before.
- View model: changing the declaration triggers one `reopen`; saving an identical declaration does not.

## Design notes
Persistence stays in `data-rabbit`, `core` has no serialization. Do not serialize the core type directly (existing rule
in the repository's class comment). `@fake-broker` only, no real broker.

## UX
`docs/ux/Main.dc.html`, connection edit panel (new fields are not in the mockup yet: the architect asks for a small UX
addition there, text above, before the UI part starts; build states: not declared, yes, no).

## Open points
- Three-state choice vs. two switches: decided as three-state (not declared must stay distinguishable from "no").

## Definition of done
Tests first and passing, no unrelated changes, `docs/tasks/README.md` and the feature tags updated.
