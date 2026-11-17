# AMQP Publish dialog: bounded banners, gutter only on overflow

Milestone: M9 (follow-up)    Depends on: M9-10e (merged)    Size: S

## Goal
A long error in the AMQP `PublishTestMessageDialog` can never squeeze the fields out, and the fields keep the full width when nothing scrolls.

## Why
Findings 1 and 2 of the step 5 review of `M9-10e`. Brief item 4 of M9-10e named only the Publish MQTT dialog.

## Scope
In: the error (`publishError`) and confirmation (`publishConfirmation`) banners of `PublishDialog.kt` get a maximum height of 136 dp with their own scrollbar on the text (same pattern as `BannerBox` in `PublishMqttDialog.kt`; share the composable if it moves cleanly to `ui/components`, else copy with a reason); tag `publishErrorScrollbar` when it overflows. The 12 dp end padding of `publishDialogBody` applies only while `bodyScroll.maxValue > 0`. Optional: one shared `DISABLED_ALPHA` instead of the three copies (`StreamScreen.kt`, `AppFrame.kt`, `MigrateFromThreeScreen.kt`).
Out: new wording, any behaviour change, the Publish MQTT dialog.

## Acceptance
- UI test next to `PublishDialogLayoutUiTest`: a 2000-character error at 1000x600 keeps both buttons inside the window, `publishDialogBody` at least 120 dp high, `publishErrorScrollbar` drawn.
- UI test: in a tall window with no overflow the body fields end at the same right edge as the error banner and the footer.
- Screenshots of the AMQP dialog stay identical in a tall window.

## Design notes
`ui` only, no `core` change. Keep existing tags.

## UX
`docs/ux/Publish.dc.html`: no visible change except the bounded banner.

## MCP
None (UI-only layout fix).

## Open points
None.

## Definition of done
Tests first and red for the right reason, then green; `:ui:jvmTest` and the screenshot comparison; no unrelated changes.
