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

## Status
- Step 2 (test developer, 2026-11-17, round 0, branch `m9-00b-b-spike-test-hardening`): R1 (`S-C4`: `outP`, `outD`, `outA` asserted `false`, `outD` and the argument declare must answer 201), R2 (`S-C2`: argument beats policy, B empty for a 6 s settle window, then a control into B's exchange arrives in B's queue, A holds exactly one), R3 (`S-C3` unbound: the target holds exactly `[poke]`, the source still shows the held signature), R4 (`S-D2`: one target stream per policy plus the control), R5 (`S-C5` policy variant kept as `S-C5 the same quorum limit set by POLICY ...`; the default-overflow row tightened to exactly `m3, m4`, 4 of 4 runs), the retry's `seenAt` printed and its observations recorded in a comment (not asserted), R6 (retry test move) not done: `brokerTest` was not measured near 30 minutes. Each new assertion was seen failing once by a temporary flip (not committed): outP, outD, outA (S-C4, one run each), `inPolicy` expected 1 (S-C2), `[held, poke]` (S-C3 unbound), the policy confirms list (S-C5), `none` instead of `all` (S-D2). Not flipped alone: the control-into-B assertion of S-C2 (the run stopped at the earlier flipped assertion), the "held signature still there" assertion of S-C3, and the `201` status checks. Runs on RabbitMQ 4.4.0 (Erlang 29.1.2): `RealBrokerM9SpikeDeadLetterTest` 14 of 14 in 533.5 s (retry test 204 s, 175 s seen), `RealBrokerM9SpikeStreamDeadLetterTest` 7 of 7 in 60.2 s. No claim was disproved. No `src/main` change.
- Step 5 (architect, 2026-11-17, round 1, branch `m9-00b-b-spike-test-hardening`): verdict ACCEPTED (no blocker, no major; two ideas). R1 to R6 of the M9-00b review closed (R6 retry-test move deliberately not done: `brokerTest` not near 30 minutes; R7 stays an idea). Reviewed: the whole test diff (13f0042) against the code. The three assertions not flipped alone are ACCEPTED without another flip: the S-C2 control into B is an exact list after a positive `routed:true` publish (a broken route times out in `awaitPeek`); the S-C3 held-signature check reuses `heldSignature`, already proven both ways by S-C4 (reject-publish variant true, three variants false); the 201 checks fail with the whole reply. Ideas (no action): the S-C5 default-overflow row is asserted twice but without a settle, fine for one outcome in 4 of 4 runs; the retry test has a 400 s bound against 175 to 204 s seen, so a slower broker fails loudly, not silently. Spec line about the three dropped outcomes and the results wording corrected to "asserted by test X". Not run by the architect: any broker test. `main` b2a5692 merged in, fast suite run once.
