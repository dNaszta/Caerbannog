# One atomic public session state

Milestone: M2    Depends on: M2-01 (merged)    Size: S

Branch: `m2-01b-session-single-state` from `main` after M2-01 is merged.

## Goal
`ConnectionSession` exposes one immutable state (statuses plus current connection) in a single `StateFlow`, so readers never see the two parts out of step, and `ConnectionsViewModel` no longer needs a custom `StateFlow` implementation.

## Why
M2-01 review: `statuses` and `current` are two flows copied from one snapshot by a `publish()` loop and can differ briefly; `SessionMirroredState` (private `StateFlow` implementation with `@OptIn(ExperimentalForInheritanceCoroutinesApi)`, `value` read from three sources) is an unsupported contract that may break on a kotlinx.coroutines upgrade. M2-03 and M2-04 add more readers of the session, so fix it before them.

## Scope
In: public `session.state: StateFlow<SessionState>` (the existing snapshot's public part: statuses, current name or `CurrentConnection`); remove the publish loop; `statuses`/`current` removed or reduced to plain getters on `state.value`; `ConnectionsViewModel.state` built without inheriting `StateFlow` (for example the view model owns the single `MutableStateFlow<ConnectionsState>` and is told of session changes by one collector in a scope the composition root gives it, or the screen collects `combine(viewModel.state, session.state)`: senior developer chooses, says which, keeps `state.value` synchronous for existing tests). Out: any behaviour change, UI change, new dependency.
## Acceptance
All M2-01 tests and both new `02` scenarios stay green with only mechanical edits to tests that named `statuses`/`current`; a new test that a reader of `state` can never observe `current` Done while `statuses` is Checking; no `@OptIn(ExperimentalForInheritanceCoroutinesApi)` left in the repo.
## Open points
A scope owned by the view model (needs cancel on exit) versus combining in the screen; recommendation: combine in the view model with an explicit scope passed in by the composition root, closed on window close.
## Definition of done
Tests first, no unrelated changes, spec Risks waiver line removed.
## Status
- State: briefed 2026-11-04.
