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

## Status
- Step 2 (test developer, tests first): DONE 2026-11-10, round 0. Added to `ItemDecisionTest`: `..` queue refused as `ReplaceInvalid` with nothing stored and `pending` unchanged; a refused Replace keeps an earlier decision; an action for another vhost refused (single and mixed with a valid one); a valid Replace is still `Decided` and generates. `ConversionExploratoryTest` `..` case tightened to assert the refusal. Compile stub `DecisionRefusal.ReplaceInvalid(reason)` marked `M8-05b STUB` in `ConversionReview.kt` (no logic; `decide` unchanged). Red: `:core:jvmTest --tests '*migrate3x*'` 458 run, 4 failed by assertion (the valid-Replace test is green, as it guards existing behaviour). No BDD scenario added: no user-visible behaviour before M8-06. Acceptance module not touched or run.
