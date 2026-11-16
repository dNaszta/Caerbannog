# The forms refuse the names the broker refuses (S-H)

Milestone: M9    Depends on: M9-01 (done), M9-00c (done)    Size: S

NEW 2026-11-16, written at step 3 of `M9-00c`. Starts after `M9-02` or in parallel with it; it touches only `core.planner` name rules and their texts.

## Goal
The queue, exchange and stream forms refuse a name that a real RabbitMQ 4.x measurably refuses, with a fixed plain message, so the user hears it in the wizard instead of at apply time. Names the broker accepts are not blocked.

## Why
Spec "Naming" (broker name rules) and "Measured facts of M9-00c", S-H. Today the forms block only an empty name and `.`/`..` ("Nothing about the broker's name rules is assumed", `M9-01`); the check reads an `amq.` queue as absent ("Will be created") and the apply then fails with 401, and a queue of 256 bytes fails with 400.

## Scope
In (`core.planner`, `commonMain`, no `java.*`; UTF-8 byte length is counted with a pure function, not `String.toByteArray` from the JVM: write it over code points, 1 to 4 bytes):
- Block, with a fixed `PlannerTexts` message each: a queue, exchange or stream name that starts with `amq.` (case: only the lower-case prefix was measured; do not block `AMQ.`, `Amq.`); a queue name longer than 255 bytes (UTF-8 bytes, not characters).
- Not blocked, with scenarios: `amq` without a dot; 255 bytes; an exchange, a policy, a stream of 256 bytes; a space or a slash in a name.
- `PlanValidation`: no new rule. A name from an opened file passes through (A2); the check cannot predict these refusals; the apply stops with the broker's answer.
- A policy named `amq.x` is accepted by the broker (201): never block it (the policy form is `M9-08`; add a unit test there).
- One small live row, a new `@broker` test in the style of `RealBrokerM9SpikeNamesTest` (the spike classes stay as they are): does the broker answer a repeated declare of an EXISTING built-in exchange (`amq.topic`, `amq.direct`) with 204 or with 401? It is unmeasured. Whatever it answers, the exchange form blocks the prefix (the planner never declares a built-in exchange; bindings use them as sources); the answer is written into `M9-00c-spike-results.md` so the message can say the right thing.
Out: hints for names (advice stays O11); the 255-byte rule for streams, exchanges, policies (accepted by the broker); names with `.`/`..` (already refused); case variants of `amq.` (unmeasured).

## Acceptance
- BDD: `19_topology_planner.feature` / "A name that RabbitMQ would refuse is refused by the form" (outline, 5 rows; @m9-01b @negative @fake-broker; the two messages DECIDED by the owner on 2026-11-16, Owner review list item 1 of `M9-00c`; `@needs-decision` removed)
- BDD: `19_topology_planner.feature` / "A name that RabbitMQ accepts is not refused by the form" (outline, 5 rows; @m9-01b @fake-broker)
- Unit: `QueueFormTest`, `ExchangeFormTest`, `StreamFormTest` rows per name (prefix, 255 and 256 ASCII bytes, 127 and 128 two-byte characters, a four-byte character at the border); `Utf8LengthTest` (1 to 4 byte code points, well-formed text only).

## Design notes
One pure `BrokerNameRules` object, used by the three forms; messages in `PlannerTexts`. No broker is touched. The rules mirror `DeclareEquivalenceTable`'s neighbour data: the S-H table of the results file is the evidence; cite it in a comment, not the numbers twice.

## UX
`docs/ux/Planner.dc.html` "4." forms: the existing field-error style, no new element.

## MCP
None (owner decision 2026-11-15, no MCP for the planner).

## Open points
- Wording (Owner review list item 1, DECIDED 2026-11-16): the messages are `Names that start with "amq." are reserved` and `A queue name can be at most 255 bytes long`.
- Whether a file's `amq.` step should also show a note in the plan: DECIDED (owner, 2026-11-16, item 10 of `M9-00c`): no note; the check says "Will be created" and the apply says the broker's refusal.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
