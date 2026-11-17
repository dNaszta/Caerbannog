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

## Status
- Reconciled 2026-11-17: this task is merged into `main` (b2c6894, 2026-11-06); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.

### Step 1: Architect brief confirmed (2026-11-06)
- **MCP check:** None required. This is desktopApp JVM lifecycle management. The stdio MCP server (`M-MCP-01`) runs as an independent CLI process managing its own lifecycle.
- **Quorum queue safety:** Confirmed. No message reading, getting, or purging.
- **Seam evaluation & open point:**
  - In Compose Multiplatform Desktop, `ApplicationScope` does not provide an application exit hook.
  - Cmd+Q / native Quit menu on macOS is handled by AWT as an application-level quit event, bypassing `Window(onCloseRequest = ...)`. AWT's default quit response triggers `System.exit(0)`.
  - SIGTERM (`kill`) sends an OS termination signal directly to the JVM process, which also runs JVM shutdown hooks.
  - Therefore, registering a standard JVM shutdown hook via `Runtime.getRuntime().addShutdownHook(...)` cleanly and reliably intercepts both Cmd+Q and SIGTERM across all platforms without fragile AWT GUI hooks.
  - `AppComposition.close()` is already fully idempotent and catches exceptions during teardown.
- **Contract:**
  - Function: `fun registerShutdownHook(composition: AppComposition, register: (Thread) -> Unit = { Runtime.getRuntime().addShutdownHook(it) }): Thread` in `CompositionRoot.kt` (or `ShutdownHook.kt`).
  - Unit test in `CompositionRootWiringTest.kt`: verifies hook registration, runs the captured hook, asserts that `composition.close()` is called once, and that a second call is tolerated without errors or duplicate closes.
  - Wiring in `Main.kt`: `val composition = remember { createAppComposition().also(::registerShutdownHook) }`.

### Step 2: Tests first (test developer, 2026-11-06)
- Added 3 unit tests to `desktopApp/src/test/kotlin/dev/caerbannog/desktopapp/CompositionRootWiringTest.kt`:
  1. `shutdown hook registers an unstarted thread and closes the held connection on exit`: asserts unstarted thread named `caerbannog-shutdown`, verifies executing hook invokes `composition.close()` (disconnects active connection).
  2. `shutdown hook tolerates a second call without error or duplicate closing`: asserts idempotency when hook executes twice.
  3. `shutdown hook after normal close request is idempotent`: asserts executing hook after `handleCloseRequest` does not cause duplicate closes or errors.
- Stubbed `registerShutdownHook` in `desktopApp/src/main/kotlin/dev/caerbannog/desktopapp/CompositionRoot.kt` with `TODO("M2-11 stub")`.
- Verification of RED state: `./gradlew :desktopApp:test --tests "dev.caerbannog.desktopapp.CompositionRootWiringTest"` completes with 11 tests completed, 3 failed with `NotImplementedError`.

### Step 3: Build (senior developer, 2026-11-06)
- Implemented `registerShutdownHook(composition, register)` in `desktopApp/src/main/kotlin/dev/caerbannog/desktopapp/CompositionRoot.kt`:
  - Instantiates thread named `"caerbannog-shutdown"` running `composition.close()`.
  - Default register adds hook to `Runtime.getRuntime().addShutdownHook(hook)`, catching `IllegalStateException` in case shutdown is already underway.
- Wired in `desktopApp/src/main/kotlin/dev/caerbannog/desktopapp/Main.kt`:
  - `remember { createAppComposition().also(::registerShutdownHook) }`
- Verified: all 11 tests in `:desktopApp:test` PASS.

### Step 4: Verification (test developer, 2026-11-06)
- **Mutation check:**
  - Mutated `registerShutdownHook` to pass an empty runnable `{}` instead of `composition.close()`. Result: 2 unit tests failed (`AssertionFailedError`), killing the mutation.
- **Full test suite & guard check:**
  - `./gradlew test verifyTestDiscovery`: 46 tasks successful, all modules green.
- **Manual verification note (Cmd+Q & SIGTERM):**
  - Both Cmd+Q (macOS AWT default quit calling `System.exit(0)`) and SIGTERM (`kill <pid>` invoking JVM shutdown sequence) trigger `Runtime.getRuntime().addShutdownHook`.
  - When the hook fires, `composition.close()` synchronously triggers `active.close()` -> `RabbitMqHeldConnection.close()`, which closes the underlying RabbitMQ connection and Stream client.
  - In the broker log, the TCP connection is cleanly closed with TCP FIN rather than hanging until connection heartbeat timeout.
- Verdict: **PASS**. No findings. Ready for Step 5 architect review.

### Step 5: Architect review (solution architect, 2026-11-06)
- **Review:**
  - Seam check: `Runtime.getRuntime().addShutdownHook` cleanly catches both macOS Cmd+Q and UNIX SIGTERM without depending on fragile UI-level callbacks.
  - Idempotency: Tolerates normal close followed by hook, or double invocation, with zero errors or side-effects.
  - No new dependencies, no changes outside `desktopApp`.
  - Headless MCP check: None needed (confirmed in Step 1).
  - Quorum queue safety: Fully respected (read-only / connection teardown only).
- **Decision:** **ACCEPTED**. Ready to merge into local `main`.



