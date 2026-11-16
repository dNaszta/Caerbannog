# Make the M9-00b spike tests assert what the results file states

Milestone: M9    Depends on: M9-00b (step 3 review, 2026-11-16)    Size: S

## Goal
The four claims of the results file that the live tests only LOG (or check without a control) are asserted, so a broker that behaves differently fails the test instead of passing silently. No product code.

## Why
`docs/tasks/M9-00b-spike-results.md`, "Review of step 3": the planner rules of `docs/spec.md` "Measured facts of M9-00b" (the at-least-once tick must set both keys; a held dead letter; an argument beats a policy) rest on these tests, and the first one is the surprise the recipe is built around.

## Scope
In (test code only, in `RealBrokerM9SpikeDeadLetterTest` and `RealBrokerM9SpikeStreamDeadLetterTest`; the results file may be corrected to the new facts if a run differs):
- **R1 (major).** `S-C4 at-least-once without reject-publish ...`: `assertFalse` for `outP` (policy, strategy only), `outD` (policy, `drop-head`) and `outA` (argument without `x-overflow`; fail with the status if the declare was refused instead). Today only `outB` is asserted.
- **R2 (major).** `S-C2 an argument alone dead-letters, and an argument beats a policy ...`: a positive control that the policy's exchange B works (publish straight into `otherDlx`, see it arrive in `otherDlq`, drain it), then a settle window (`assertStaysEmpty`, 6 s) on `otherDlq` after the dead letter arrived in A. The comment in the test says "positive control" and none exists.
- **R3 (major).** `S-C3 a target without a binding, ...`: after `al.bind()` and the control dead letter, assert that `api.peek(al.dlq)` holds exactly the control (`poke`) and not `held`; the line `assertStaysEmpty(api, al.src, ...)` is trivially true for a held message (a held message is never ready) and proves nothing: replace it or add the real check. (The target must still not receive `held` before the slow retry; the retry test already covers the later arrival.)
- **R4 (minor).** `S-D2`: run the `all` policy on its own stream (a second target) so it is independent of the `streams` policy, or say in the results that it is not (already worded so).
- **R5 (minor).** `S-C5`: add a kept test for the same quorum numbers with the limit set by POLICY (`max-length` 2, `overflow: reject-publish`, `apply-to: quorum_queues`): ack, ack, ack, nack, nack, nack; holds three. It was seen once in an unkept probe and the recipe sets it by policy.
- **R6 (idea, only if `brokerTest` gets within 30 minutes of its 40-minute budget).** Move `S-C3 a held dead letter is delivered by a slow retry ...` (204 s) into its own class with its own container and tag it so a default run can skip it. Today: stays (decision in the results file).
- Print the retry's `seenAt` in a stable `M9SPIKE` line and record the poll step in the results, so the single observation is at least stated with its resolution.

Out: any product code; measuring more keys against arguments (`dead-letter-routing-key`); operator policies; 3.x.

## Acceptance
- BDD: none (spike).
- `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM9SpikeDeadLetterTest*'` and `'*RealBrokerM9SpikeStreamDeadLetterTest*'` pass on RabbitMQ 4.x; each new assertion was seen failing once by temporarily flipping its expectation (note the flip in the Status; do not commit it).
- If a run contradicts the results file or the spec, the test developer reports it to the architect; the architect corrects `docs/spec.md`.

## Design notes
Helpers stay private to the two classes (`M9SpikeDl`). Positive control before every "nothing arrived". No bare sleep.

## UX
None.

## MCP
None (spike).

## Open points
None.

## Definition of done
Tests pass on a machine with Docker or rootless Podman; no `src/main` change; results file consistent with the asserted facts.
