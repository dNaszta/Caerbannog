# Optional live rows for the access matcher

Milestone: M10    Depends on: M10-04 part A (accepted)    Size: S

## Goal
The known limits of `AccessMatcher` are measured on the live broker or stay recorded as limits, and `Re2jPatternEngine` never throws.

## Why
Review of M10-04 part A (architect, 2026-11-27): findings 3 to 5. A wrong "would match" is worse than none (spec "Access view (M10)").

## Scope
In: rows in `AccessMatchNewRows` run by `RealBrokerAccessMatchTest` for: a pattern of 256 and 257 characters (can the broker store it, and does `MAX_PATTERN` match); a name of 255 and 256 UTF-8 bytes; `\x{100}` and `\x{e9}` patterns; `\D` and `\s` with a non-ASCII name; the oldest pinned 4.x image the repo already uses, if one exists. If a row shows RE2/J throwing something other than `PatternSyntaxException`, catch that type in `Re2jPatternEngine.compile` and add a `PatternEngineTest` case. Any new disagreement becomes a `KnownDivergences` entry with its row.
Out: part B, any change of the check order.

## Acceptance
- BDD: none new (the differential rows are the scenarios); `RealBrokerAccessMatchTest` stays green.

## Design notes
Test code and, only if a row shows it, `Re2jPatternEngine` and `KnownDivergences`. Live rows run on throwaway containers, own users only.

## UX
None.

## MCP
None (internal; the matcher feeds `M10-08`, which has its own brief).

## Open points
Not measured so far: all of the above. Until measured they stay listed as limits in spec "Pattern engine rows (M10-04 part A)".

## Definition of done
Tests first; results in the spec and `M10-00-spike-results.md`; no unrelated change.
