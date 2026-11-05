# M2-07 follow-ups: Type column width, row identity, file size

Milestone: M2 (item 1) and M3 (items 2 and 3)    Depends on: M2-07 (review done)    Size: S

## Goal
1. (Before merging M2-07, same branch.) The Type cell of a super stream reads in full ("Super stream · 3 partitions") at 1280 dp, as in `docs/ux/Streams.dc.html`.
2. (M3, with the Browse button or any row selection.) Rows have an identity that carries the kind, so a plain stream and a super stream with the same name cannot collide.
3. (M3, F-H split.) `StreamsTable.kt` (389 lines) and `StreamsScreen.kt` (293 lines) are split before more is added.

## Why
Item 1: the test developer's step 4 finding (minor): "Super s..." is cut; the text is the point of the column. Item 2: architect ruling 3 of M2-07 asked for kind-aware row ids; the built code has no id field (selection does not exist in M2, the expanded set is keyed by the super stream name, which is unambiguous because only a super stream expands), and the UI tags `streamRow:<name>` and `cell:<name>:Name` repeat for a same-name pair. Compose does not use them as keys, so nothing breaks at run time; only UI tests and any future selection would meet the clash. Item 3: planned in M2-05.

## Scope
In:
1. `StreamsTable.kt` `Column.Type` width from 74 dp to about 190 dp (measure: the longest text "Super stream · 100 partitions" must fit at the table's own scale at 1280 dp); keep the sideways scroll for narrow windows. Re-run `StreamsScreenshotTest` `streams-06` and `streams-07` and compare. Same commit: `StreamRow.typeText` uses `StreamListTexts.PARTITION` instead of the two literals "partition" and "partitions" (add a plural constant or build both from one place).
2. Add `StreamRow.id` (vhost, kind, name; partition rows: the stream id) and tag rows `streamRow:<kind>:<name>` only when the first task that needs selection starts; until then keep the name tags (the tag contract is in the KDoc of `StreamsSuperStreamUiTest`).
3. Split by concern (header and sort, data row and name cell, footer and chips), no behaviour change, tests stay green.
Out: any new behaviour.

## Acceptance
- Item 1: a ui test that the Type cell of `orders` (super stream, 3 partitions) is not ellipsized at 1280 dp (text layout `hasVisualOverflow` false, or the node width at least the text width) and the screenshot matches the mockup; red first.
- Items 2 and 3: existing scenarios stay green; item 2 adds a ui test with two rows of the same name and different kinds and unique tags.

## Design notes
`ui` only for item 1 and 3; `core` plus `ui` for item 2. No broker code touched.

## UX
`docs/ux/Streams.dc.html`, expanded and collapsed states.

## Open points
None. Item 1 is a decision taken here: fix now, small, not a reason to hold the branch longer than one commit.

## Definition of done
Test first and red, then green; affected suites run, full suite once; no unrelated changes.
