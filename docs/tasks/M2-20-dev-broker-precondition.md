# Name the hidden dev-broker precondition of two live classes

Milestone: M2 (test infrastructure)    Depends on: M2-18 (merged)    Size: S

## Goal
Running `brokerTest` (or `task test:broker`) without the local dev broker fails `RealBrokerM5GateTest` and `RealBrokerMqttIntegrationTest` at once with one sentence that says what to start (`task dev:up`), instead of eight unrelated connection errors; with the broker up nothing changes.

## Why
Found in M2-18 step 4 (2026-11-20): the first contended run had 11 red tests, 8 of them these two classes, green with the dev broker up. Both talk to `localhost` (MQTT 1883, management 15672, stream ports) and CI starts the broker in `ci.yml` ("Start the dev broker"), so only a local run is surprised. A skip would hide a gate, so the answer is a clear failure, never `Assumptions`.

## Scope
In: (1) a small test helper `DevBrokerPrecondition.require()` in data-rabbit tests (one TCP connect to the management port, short bounded timeout) called from `@BeforeAll` of the two classes; the failure message: `The local dev broker is not running. Start it with: task dev:up (see dev/README.md).` (2) `Taskfile.yml`: `test:broker` and its podman and docker variants do not start it (a test run must not change the machine silently); the `desc` of `test:broker` names the precondition. (3) `CLAUDE.md` Commands line for `brokerTest`: one clause "two classes need `task dev:up`". (4) A fast unit test of the helper against a closed local port (red message check) and an open `ServerSocket` (passes).
Out: moving the two classes to Testcontainers (a possible later task, not now); a skip; any change to what the classes assert.

## Acceptance
- BDD: none (test infrastructure).
- With the dev broker stopped, `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM5GateTest*' --tests '*RealBrokerMqttIntegrationTest*'` fails with exactly the message above, once per class; with it up, both classes are green as before.
- Helper unit test green without a container.

## Design notes
Test code only. No sleeps: one connect with a 2 s timeout is the bound. Ports come from the same constants the classes already use; do not hardcode a third copy.

## UX
None.

## MCP
None (internal test infrastructure).

## Open points
None.

## Definition of done
Tests first (helper test red), both classes show the message with the broker down, `CLAUDE.md` and README updated, no unrelated changes.
