# M9-09b: Dead-lettering recipe follow-ups (live proof of the own-argument read, graph link code, the dashed edge)

Milestone: M9    Depends on: M9-09 (merged)    Size: S

## Goal
Three loose ends of the M9-09 review are closed: the own-argument read is proven against a real broker, the graph link code says what it does, and the dashed dead-letter edge is checked by a test that can see it or by a recorded person's look.

## Why
Review of M9-09 (docs/tasks/M9-09-dead-lettering-recipe.md, Status): the wiring reads `x-dead-letter-exchange` from `TopologyCatalog.queueDeclarations`, which the planner tests only fake; the shared graph canvas names its link function after the response stream; no test can see a dash.

## Scope
In:
- A `@broker` test (Testcontainers, `RealBrokerM9PolicyStepTest` is the model): declare a quorum queue with `x-dead-letter-exchange` as an argument and one without; `CatalogKnownTopologyReader` (or `queueDeclarations` through the real catalog) gives the text for the first and nothing for the second; read-only GETs. If a real broker returns the argument in another shape, fix the reader and say so in the spec.
- `ui/.../topology/BindingGraphCanvas.kt`: rename `responseLinkShapes` and `LinkShape` helpers so the name covers both link kinds; the line style per `PlanLinkKind` in one small function. No behaviour change.
- The dashed edge: pixel check through the existing `task test:screenshots` route if it can read pixels along the link (a dash pattern gives gaps); if not, add the person's look of M9-09 to the screenshot list in `docs/ux/` notes. Decide in the task; do not add a dependency.
Out: the two known gaps (same recipe twice leaves the second same-name policy unflagged: M9-12 and M9-13; an exchange name the broker has with another type: judged by the M9-05 check).

## Acceptance
- BDD: none new (the `@m9-09` scenario "The review graph shows the dead-letter path" stays as it is).
- The live test above, green on Docker or rootless Podman.

## Design notes
`data-rabbit` is read only here; no new read path for queue messages (none involved). Keep `RealBrokerM7GateTest` and `RealBrokerM8GateTest` untouched.

## UX
`docs/ux/Planner.dc.html`: the dashed edge of the review graph. No new state.

## MCP
None (UI-only and a test; the planner has no MCP tool).

## Open points
None for the owner.

## Definition of done
Tests written first and passing, no unrelated changes.

Status 2026-11-18: briefed (ready); parent task merged.
