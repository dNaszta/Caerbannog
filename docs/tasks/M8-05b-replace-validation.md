# Validate a Replace decision when it is made

Milestone: M8    Depends on: M8-05    Size: S

## Goal
`ConversionReview.decide(item, Replace(actions))` refuses actions that the migration parser would refuse, so `generate` never fails on user input.

## Why
M8-05 review, finding F1. Today a `Replace` holding a queue named `..` is `Decided`, then `generate` throws `IllegalStateException("a generated migration does not parse")`, a message meant for generator bugs. The user's text must be refused where it is entered.

## Scope
In: new `DecisionRefusal.ReplaceInvalid(reason: String)`; `decide` round-trips the actions through `MigrationParser.exportToJson` and `parse` (a throw-away version and description) and refuses with the parser's reason (no secret can be in it: typed topology only). Optional, same task: refuse an action whose `vhost` differs from the item's vhost as `ReplaceInvalid` (F3; a file for another vhost is not what the item asked for).
Out: UI (`M8-06`), any change to Accept rules.

## Acceptance
- Unit tests (`ItemDecisionTest`): `Replace` with `..` name refused with `ReplaceInvalid` and nothing stored; valid replace still `Decided`; the exploratory test in `ConversionExploratoryTest` is tightened to assert the refusal.
- Existing `@m8-05` scenarios and `verifyTestDiscovery` stay green.

## MCP
None (internal to the review core).

## Definition of done
Tests first and passing, no unrelated change.
