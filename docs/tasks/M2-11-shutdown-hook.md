# Close the held connection when the process is ended without the window close button

Milestone: M2    Depends on: M2-03 (merged)    Size: S    Priority: optional, low

## Goal
Quitting with Cmd+Q, the app menu, or SIGTERM closes the held connection the same way the window close button does.

## Why
M2-03 step 5 accepted the risk (Compose `onCloseRequest` is not called for these paths). Closing is tidy for the broker log; nothing stays registered on the broker either way (read-only, unnamed connections).

## Scope
In: a JVM shutdown hook in `desktopApp` that calls `AppComposition.close()` (idempotent, never throws, must not block more than a short bound); check whether Compose Desktop's `Desktop` quit handler or `application` exit hook is the better seam on macOS and use it if it exists.
Out: any change to the holder, the gateway or the window close path.

## Acceptance
- Unit test: the hook registration function runs `close()` once and tolerates a second call (inject the registrar, do not register a real hook in tests).
- Manual note in Status: Cmd+Q on macOS and `kill` close the connection (broker shows the connection ends within the TCP close, not a timeout).

## Design notes
`desktopApp` only. No new dependency.

## UX
None.

## Open points
Whether Cmd+Q really bypasses `onCloseRequest` on the Compose version in use was reported by the test developer from reading, not from a run; verify first.

## Definition of done
Tests first and passing, no unrelated changes, README row added.
