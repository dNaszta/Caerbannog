# M7-09: Headers and properties inspection, copy metadata

Milestone: M7    Depends on: M7-08, M3-03    Size: S    Commit: 21193c7 (the commit message names M7-09)

## Goal
Queue messages and stream messages show their properties and headers in the detail, and "Copy metadata" puts them on the clipboard.

## Why
Spec "Queue message inspection (M7-08)" ("Rows show properties and headers; Copy metadata") and the message detail row of the spec table.

## Scope (what shipped)
In:
- `InspectedQueueMessage`: `displayExchange` ("(default exchange)"), `displayRoutingKey` ("(empty)"), `formattedDeliveryMode` ("2 (Persistent)", "1 (Transient)"), `formatMetadataForCopy()` (exchange, routing key, redelivered, payload size, remaining in queue, properties, headers).
- `PayloadFormatter.copyHeadersAndProperties(message)` for stream messages (offset, timestamp, properties, application properties).
- `RabbitMqStreamMessageReader` adds AMQP properties to the message: `userId` (when it decodes), `groupId`, `groupSequence` (when above 0), `replyToGroupId`, `creationTime`, `absoluteExpiryTime` (when above 0), and message annotations with a non-null value.
- `MessageBrowserView` "Copy metadata" button (shown only when there are properties or application properties); the queue inspector has its own.
Out: editing headers; the toolbar layout (M7-11).

## Acceptance
No test was added in this commit. Search of the test sources finds no test of `formatMetadataForCopy`, `copyHeadersAndProperties` or the new reader properties. Covered only indirectly by the M7-08 UI test (detail renders) and, for annotations, by `RealBrokerStreamExchangeAnnotationsTest` (M7-11). Mark as a test gap.

## Design notes
Plain text format, one "key: value" per line, in map order. Values are shown as strings; nested values are shown by their string form.

## UX
No file in `docs/ux/`.

## MCP
None (UI-only): the MCP browse tool already returns properties.

## Non-goals
Format choices (JSON, CSV) for the copy.

## Open points
Add the missing unit tests (formatter text, reader mapping) as a follow-up.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
