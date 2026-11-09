# Widen the migration action model for converted 3.x topology

Milestone: M8    Depends on: M8-00    Size: M

## Goal
The M5-04 migration format can express what a converted topology needs (typed queue arguments, exchange flags and arguments, policy keys with typed values, `apply-to` and priority, exchange-to-exchange bindings), and every existing migration file still loads, checksums and applies as before.

## Why
`core/.../migrations/MigrationModels.kt` today has `DeclareQueue.arguments: Map<String,String>`, `DeclareExchange` with only name, type, vhost and durable, `DeclarePolicy.definition: Map<String,String>` with no `apply-to` or priority, and `DeclareBinding` with queue only. A faithful conversion cannot be written in that format (a numeric `x-delivery-limit` or `x-max-length` would become a string). Spec "Reuse of the migrations feature".

## Scope
In: additive model fields (typed value type for arguments and policy definitions; `autoDelete`, `internal`, `arguments` on exchanges; `applyTo`, `priority` on policies; `destinationType` on bindings), JSON in and out, executor support in `data-rabbit` (management PUT bodies), `FakeTopologyMigrationExecutor`, export of current topology keeps working, the migrations screen shows the new details.
Out: Shovel (not a migration action, spec); vhost, user and permission actions (`M8-12`, which builds on this model).

## Acceptance
- BDD: `10_topology_migrations.feature` all green unedited; `16` / "A migration file cannot contain a shovel action", "The generated migration is an ordinary migration file" (with `M8-05`).
- Unit: old files (fixtures from M5-04 tests) parse to the same actions and the same checksum; new fields round trip; a numeric argument stays a number on the wire (live `@broker` test against 4.x).

## Design notes
Backward compatibility is the whole risk: checksum is over raw JSON so old applied files are untouched. Prefer a sealed `ArgumentValue` over `Any`. If this needs `core` serialization changes, keep `rawJson` as the source of the checksum.

## UX
`docs/ux/Migrations.dc.html`: action rows show arguments; no new screen.

## MCP
None (internal model; existing migration tools, if built, benefit automatically).

## Open points
Exact set of fields is confirmed by `M8-00` S-C and S-E (what a quorum queue and a 4.x policy accept).

## Definition of done
Tests first and passing, old-file regression suite green, no unrelated changes, spec format note updated.
