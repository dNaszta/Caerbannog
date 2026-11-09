# M7-03: P1 layout and ergonomics (Topology, message browser, Connections)

Milestone: M7    Depends on: M7-02    Size: S    Commit: 544025a (the number is assigned in commit order by the product owner; the commit itself has no task id)

## Goal
Less wasted space and faster keyboard use: the selected queue's detail stays visible in the Topology Queues tab, the message browser is more compact and steps rows with the arrow keys, rows show a badge for application properties, and the Connections edit panel gets a tidier header and action bar.

## Why
Findings from hands-on use of the M7 screens; no spec section changed.

## Scope (what shipped)
In:
- `TopologyTabsContent`: the table scrolls in its own box; `QueueDetail` is docked below it, so selecting a row in a long list shows the detail without scrolling.
- `MessageBrowserView`: tighter paddings, page-size selector moved to the right; table handles Down, Up and Escape (select next or previous row, clear selection); the properties cell reads `contentType` as well as `content-type` and shows a "+N" badge when the message has application properties.
- `ConnectionsScreen`: smaller vertical gaps, header row, `editPanelActionBar` test tag on the action bar.
Out: any behaviour change of connections or paging.

## Acceptance
- `ui/.../topology/TopologyDetailAndSafetyUiTest`: "when the queue list has many rows the selected queue detail stays docked in the viewport without scrolling".
- `ui/.../streams/MessageBrowserUiTest`: "table renders camelCase contentType and application properties count badge and steps rows with arrow keys" (covers Down only; the test stops at one key press).
Not covered by a test: Up and Escape, the Connections changes.

## Design notes
Compose-only change, no core or broker code.

## UX
Follows `docs/ux/` styling; no screen file was changed.

## MCP
None (UI-only).

## Non-goals
Redesign of any screen.

## Open points
None.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
