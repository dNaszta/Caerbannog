# The forms refuse the names the broker refuses (S-H)

Milestone: M9    Depends on: M9-01 (done), M9-00c (done)    Size: S

Status: step 3 done (build, 2026-11-17): `BrokerNameRules`, `Utf8Length`, two `PlannerTexts` constants, `FormChecks.nameProblem` takes `isQueue`; all `@m9-01b` runs and unit tests green, `RealBrokerM9BuiltInExchangeDeclareTest` 4 of 4 green. Next: test verification and review. Earlier: step 2 done (tests first, 2026-11-17). Red for the right reason: 6 of the 11 `@m9-01b` runs fail (the 5 refused rows and the response stream: `form problems expected [message] but was []`), the 5 accepted rows pass already (regression guards); the unit tests do not compile until `BrokerNameRules`, `Utf8Length` and the two `PlannerTexts` constants exist. Live row measured: `amq.topic` and `amq.direct` redeclared with their real type answer **204** (see `M9-00c-spike-results.md`, "M9-01b"). Next: step 3, the senior developer. Policy `amq.x` unit test waits for `M9-08` (no policy form yet).

NEW 2026-11-16, written at step 3 of `M9-00c`. Starts after `M9-02` or in parallel with it; it touches only `core.planner` name rules and their texts.

## Goal
The queue, exchange and stream forms refuse a name that a real RabbitMQ 4.x measurably refuses, with a fixed plain message, so the user hears it in the wizard instead of at apply time. Names the broker accepts are not blocked.

## Why
Spec "Naming" (broker name rules) and "Measured facts of M9-00c", S-H. Today the forms block only an empty name and `.`/`..` ("Nothing about the broker's name rules is assumed", `M9-01`); the check reads an `amq.` queue as absent ("Will be created") and the apply then fails with 401, and a queue of 256 bytes fails with 400.

## Scope
In (`core.planner`, `commonMain`, no `java.*`; UTF-8 byte length is counted with a pure function, not `String.toByteArray` from the JVM: write it over code points, 1 to 4 bytes):
- Block, with a fixed `PlannerTexts` message each: a queue, exchange or stream name that starts with `amq.` (case: only the lower-case prefix was measured; do not block `AMQ.`, `Amq.`); a queue name longer than 255 bytes (UTF-8 bytes, not characters).
- The stream form's RESPONSE name (field `responseName`, followed or typed) is a stream name: the `amq.` rule applies to it too, with the same message on field `responseName`. Why: the dot style derives `amq.dln` from the stream `amq`, which the broker refuses (S-H, stream manager). No length rule for it (streams have none). The derived name is never repaired silently.
- Order inside one field, one message only (the scenarios assert exactly one): empty, `.`/`..`, `amq.` prefix, queue byte length, then the parser's reason (`FormChecks.nameProblem`). Put the rule in `FormChecks.nameProblem` (new parameter or a `BrokerNameRules.problem(kind, name)` call before the parser round trip); the stream form's existing `queueConflict` stays after it. Check that the parser round trip does not already refuse a 256-letter name with another text (the scenarios expect the new text).
- Not blocked, with scenarios: `amq` without a dot; 255 bytes; an exchange, a policy, a stream of 256 bytes; a space or a slash in a name.
- `PlanValidation`: no new rule. A name from an opened file passes through (A2); the check cannot predict these refusals; the apply stops with the broker's answer.
- A policy named `amq.x` is accepted by the broker (201): never block it (the policy form is `M9-08`; add a unit test there).
- One small live row, a new `@broker` test in the style of `RealBrokerM9SpikeNamesTest` (the spike classes stay as they are): does the broker answer a repeated declare of an EXISTING built-in exchange (`amq.topic`, `amq.direct`) with 204 or with 401? It is unmeasured. Whatever it answers, the exchange form blocks the prefix (the planner never declares a built-in exchange; bindings use them as sources); the answer is written into `M9-00c-spike-results.md` so the message can say the right thing.
Out: hints for names (advice stays O11); the 255-byte rule for streams, exchanges, policies (accepted by the broker); names with `.`/`..` (already refused); case variants of `amq.` (unmeasured).

## Acceptance
- BDD: `19_topology_planner.feature` / "A name that RabbitMQ would refuse is refused by the form" (outline, 5 rows; @m9-01b @negative @fake-broker; the two messages DECIDED by the owner on 2026-11-16, Owner review list item 1 of `M9-00c`; `@needs-decision` removed)
- BDD: `19_topology_planner.feature` / "A response stream name that RabbitMQ would refuse is refused by the form" (1 scenario; @m9-01b @negative @fake-broker; added by the architect at this step: the derived `amq.dln`)
- BDD: `19_topology_planner.feature` / "A name that RabbitMQ accepts is not refused by the form" (outline, 5 rows; @m9-01b @fake-broker)
- Runner: `RunTopologyPlannerAcceptanceTest` filter does NOT list `@m9-01b` yet (it lists `@m9-01` and the others only): add `@m9-01b` when the steps exist; the scenarios carry no `@needs-*` tag, so the existing `not @needs-decision and not @needs-spike` stays. 11 runs in all: 5 refused rows, 1 response stream, 5 accepted rows.
- Steps (test developer, `PlannerSteps.fillForm`, today a closed `when` over five inputs): add `the name "<x>"` for any kind in general (not one row per name), `a name of <n> letters` (ASCII `a` repeated), `a name of <n> letters "<c>"`, the kind "Stream" (a `streamForm` in `PlannerDriver`), and `the name "<x>" and its response stream`. "Next" enabled/disabled steps exist.
- Unit: `QueueFormTest`, `ExchangeFormTest`, `StreamFormTest` rows per name (prefix, 255 and 256 ASCII bytes, 127 and 128 two-byte characters, a four-byte character at the border); `Utf8LengthTest` (1 to 4 byte code points, well-formed text only).

## Test API fixed at step 2
`internal object Utf8Length { fun of(text: String): Int }`, `object BrokerNameRules { fun reservedPrefix(name): Boolean; fun queueNameTooLong(name): Boolean }` (public or internal, same package `core.planner`), `PlannerTexts.NAME_RESERVED`, `PlannerTexts.QUEUE_NAME_TOO_LONG`. The forms are tested through their public `problems(ctx)`/`actions(ctx)`; the parser round trip has no length rule (checked: a 256-letter name only gets the new text). Steps: `PlannerSteps.fillForm` handles `the name "<x>"` (with ` and its response stream`), `a name of <n> letters`, `a name of <n> letters "<c>"` for Queue, Exchange and Stream.

## Design notes
One pure `BrokerNameRules` object in `core.planner`, used by the three forms; messages in `PlannerTexts` (`NAME_RESERVED` = `Names that start with "amq." are reserved`, `QUEUE_NAME_TOO_LONG` = `A queue name can be at most 255 bytes long`). Suggested API for the unit tests: `BrokerNameRules.reservedPrefix(name): Boolean`, `BrokerNameRules.queueNameTooLong(name): Boolean`, `Utf8Length.of(text): Int` (internal, code points: below 0x80 one byte, 0x800 two, 0x10000 three, else four; a lone surrogate is out of scope: counted as 3 and not tested). No broker is touched. The rules mirror `DeclareEquivalenceTable`'s neighbour data: the S-H table of the results file is the evidence; cite it in a comment, not the numbers twice.

## Live test
Only the built-in exchange row (Scope) is unmeasured; all other claims are in the S-H table (measured on 4.4). The new class (suggested `RealBrokerM9BuiltInExchangeDeclareTest`, tag `broker`) declares `amq.topic` and `amq.direct` again with their real type and answers 204 or 401, plus a positive control (an ordinary exchange redeclared: 204). It needs no BDD scenario; the rule is the same either way. `RealBrokerM9SpikeNamesTest` has no `amq.direct`/`amq.topic` row today (checked).

## UX
`docs/ux/Planner.dc.html` "4." forms: the existing field-error style, no new element.

## MCP
None (owner decision 2026-11-15, no MCP for the planner).

## Open points
- Wording (Owner review list item 1, DECIDED 2026-11-16): the messages are `Names that start with "amq." are reserved` and `A queue name can be at most 255 bytes long`.
- Whether a file's `amq.` step should also show a note in the plan: DECIDED (owner, 2026-11-16, item 10 of `M9-00c`): no note; the check says "Will be created" and the apply says the broker's refusal.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
