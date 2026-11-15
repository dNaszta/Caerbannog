# M9-14: The topology export keeps arguments and policies

Milestone: M9 (recommended follow-up; not part of the planner's first version)    Depends on: none of the planner tasks; best after M9-00c    Size: M

PLANNED 2026-11-15. Not started. Found while planning M9 (spec "Topology planner (M9)", "Disagreements found while planning", item 2). The owner decides its place in the order.

## Goal
"Export current topology" writes a migration that declares what the virtual host really has: queues with their durable flag and arguments, exchanges and bindings with their typed arguments, and the policies. Applying the exported file to an empty broker gives the same topology.

## Why
`tests/bdd/features/10_topology_migrations.feature`, "Export the current topology as a migration", says the file "declares the queues, exchanges, bindings and policies of the broker", and the `@gate-M5` scenario says an exported topology can be applied to an empty broker. The code (`MigrationsViewModel.exportCurrentTopology`) writes queues by name and type only, exchanges without arguments, bindings without arguments and no policy; `MigrationsViewModelExportTest` pins "exported queues have no arguments"; the step definition of the scenario does not look at policies. So an export of a topology with dead-letter arguments, a headers binding, a super stream exchange (`x-super-stream`) or a policy is silently incomplete, and the gate passes only because its topology has none of these. With the planner such topologies become common.

## Scope
In:
- A short spike first (live, `rabbitmq:4-management`): which fields `GET /api/queues/{vhost}` gives with `columns=...,arguments,durable,auto_delete` and their JSON types; `GET /api/policies/{vhost}`; which arguments the broker adds on its own and must not be exported (`x-queue-type` is the `type` key).
- `core`: `TopologyCatalog` gains what the export needs behind the interface (typed arguments and flags of queues, typed arguments of exchanges and bindings, the policies of a virtual host), as new read methods or fields, all GET; the display rows of the Topology screen are unchanged.
- `MigrationsViewModel.exportCurrentTopology` writes them; a file without any of them stays byte for byte what it is today (the M8-04 rule).
- `data-rabbit`: the reads, through the catalog's existing guard (every request a GET of an allowed shape, never a path under a queue's name).
- The scenario's step checks policies and arguments; the gate topology gets a queue with an argument, a headers binding and a policy.

Out: users, virtual hosts and permissions (never exported; the access migration of M8 is the only place for them); operator policies unless the spike shows they are needed for equality; streams' retention arguments while `declareStream` has none (reported in the file's description as left out, or solved with the later `declareStream` growth).

## Acceptance
- BDD: `10_topology_migrations.feature` / "Export the current topology as a migration" (@fake-broker), with a step that checks what the sentence says; "Exporting the topology keeps working and keeps exchange flags" (@m8-04 @fake-broker) unchanged; "Exported topology can be applied to an empty broker" (@broker @m5-08 @m5-08a @gate-M5) with the richer topology.
- New scenario, written with the task: "An export says what it leaves out" (a stream with retention: the migration's description or the screen names it).
- Unit tests first: `MigrationsViewModelExportTest` (the pinned "no arguments" test is replaced by typed-arguments tests; the byte-for-byte test stays), parser tests for the new reads, the catalog guard test extended.

## Design notes
Arguments are `ArgumentValue` end to end (numbers stay numbers); nothing is guessed from display text (`ExchangeRow.arguments` and `BindingRow.arguments` are display text today and stay so). Until this task is built, the Migrations screen should say what an export leaves out; that one sentence can be added with `M9-03` if the owner wants it sooner.

## UX
`docs/ux/Migrations.dc.html`: no new control; one line under the export result when something was left out.

## MCP
None new. The existing read-only tools list and dry-run an exported file as before.

## Open points
- Whether an exported policy keeps its priority and name exactly (yes, proposed) and whether policies the app's user cannot read make the export fail loudly (yes, proposed: the existing rule "an export that cannot read the broker writes no file").
- Order: the owner's call. Recommended right after the planner's first slice, because a check or a plan made from an incomplete export misleads.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
