# M7-11: Review follow-ups (BodyPreview, clipboard helper, details sheet, copy toolbar, routes scrollbar)

Milestone: M7    Depends on: M7-01 to M7-10    Size: S    Commits: 9134e60, dc5f82b (no task id in either commit; the number is assigned by the product owner)

## Goal
Findings from a review of the message browser are fixed, and the details sheet and the Inbound routes bar get small ergonomic fixes.

## Why
Remove duplicated copy code and an inconsistent text-or-binary decision between table cell and tooltip; confirm on a live broker where exchange and routing key come from.

## Scope (what shipped)
In:
- `core/browser/BodyPreview`: `cell(payload)` (text up to 120 chars, newlines flattened, or the first 32 bytes as hex) and `tooltip(payload)` (text up to 1000 bytes or hex up to 256 bytes, with a "(truncated, full length: N bytes)" note, no cut inside a multi-byte character). One rule decides binary: control characters other than tab, CR, LF, or the replacement character.
- `ui/components/Clipboard.kt` `rememberCopyToClipboard()`: the one copy path (desktop `StringSelection`); the dialog and screen duplicates are removed.
- Details sheet: horizontal scroll removed (vertical only); copy actions are a compact icon toolbar (body, offset, metadata) whose label is the tooltip and accessibility text.
- Stream screen Inbound routes bar: a horizontal scrollbar appears when the routes overflow (`inboundRoutesRow`, `inboundRoutesScrollbar`).
- `RealBrokerStreamExchangeAnnotationsTest` (`@Tag("broker")`): on RabbitMQ 4 a message routed into a stream through an exchange has the `x-exchange` and `x-routing-key` annotations, which the reader shows as exchange and routing key; a direct stream publish has neither. Recorded in `docs/spec.md`.
Out: new features.

## Acceptance
- `core/.../browser/BodyPreviewTest` (flattening, control bytes, invalid UTF-8 agreement, short and long text, split multi-byte character, large binary).
- `data-rabbit/.../RealBrokerStreamExchangeAnnotationsTest`: "a message routed through an exchange reports that exchange and routing key, a direct stream publish reports none".
- `ui/.../publish/PublishMqttDialogUiTest`: "StreamScreen inbound routes row scrolls and shows a scrollbar when several long routes overflow".
- `ui/.../streams/MessageBrowserUiTest` (existing) still finds `copyBodyButton` and `copyOffsetButton` by tag after the toolbar change.
Not covered by a test: what the copy actions put on the clipboard, the details-sheet scroll fix, `rememberCopyToClipboard`.

## Design notes
`ui` has a single JVM target, so `StringSelection` stays in `Clipboard.kt`; move behind expect/actual if a second target is added.

## UX
Details sheet per the browser design in `docs/ux/`; toolbar is a deviation (icons only).

## MCP
None: UI and formatting only.

## Non-goals
Changing what is copied.

## Open points
None.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
