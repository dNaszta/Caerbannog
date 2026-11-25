# M10-04: Match preview "can this user publish to or read from X?"

Milestone: M10    Depends on: M10-03, M10-00 (S-I)    Size: M

## Goal
On the Permissions tab the user picks a user, an action (publish, read, configure), a virtual host, a name and, for a topic exchange, a routing key, and sees whether the broker's patterns would match, which pattern decided, and that this is a prediction; and the pure matcher agrees with a real broker on a case table.

## Why
Spec "Access view (M10)", goal 2 and risk "the match preview is a prediction". The matcher must be right or absent: a false "would match" misleads.

## Scope
In:
1. **`AccessMatcher` in `core`** (pure function, no `java.*` beyond what Kotlin common offers; if Erlang's regular-expression dialect needs more than `Regex` can do, the brief stops and the architect decides, see Open points). Input: the permission rows and topic rows of a user, an `AccessQuestion(action, vhost, name, routingKey?)`. Output: `WouldMatch(kind, pattern)`, `NoMatch(kind, pattern)`, `UnreadablePattern(kind)`, `PermissionsNotReadable`. The action to permission mapping (publish to exchange needs write on the exchange; read from queue needs read; bind needs write on the queue and read on the exchange; the default exchange name; stream publish and consume) comes only from the measured table of S-I, in a data table in the code with the spike row id as comment, not from memory.
2. **The case table of S-I as a fixture** (`core-testing`, at least 30 rows) used by the unit test of the matcher and by the live gate test.
3. **UI**: the form and the verdict on the Permissions tab; the sentence "prediction, the broker decides" (wording AQ5) always next to the verdict; the source of the patterns ("read from the broker") shown; no request is sent when the user asks (the permissions are already loaded; the preview never publishes, reads or declares anything).
4. **Live test** `RealBrokerAccessMatchTest` (`broker`, in `brokerTest`): for each row of the case table, a throwaway user with the row's pattern, the matcher's answer against the broker's answer to the real operation; any disagreement fails the test and names the row.
Out: any attempt on the broker from the app (probing is a non-goal), token users (`M10-05`).

## Acceptance
- BDD (`22`, tag `@m10-04`): "Publishing to an exchange is checked against the write pattern" (@smoke), "Reading from a queue is checked against the read pattern", "A pattern is applied as RabbitMQ applies it" (outline; the rows are replaced with S-I's rows by the architect), "The default exchange is asked about by the name the broker uses for it" (`@needs-spike`), "A topic permission is checked against the routing key", "A pattern that cannot be read is reported, not guessed", "The preview sends nothing to the broker", "The preview says where the permission came from" (`@needs-decision`, AQ5), and the gate scenario "The preview agrees with a real broker for a table of cases" (`@gate-M10 @broker`).
- Unit: matcher over the whole case table, over invalid patterns, over empty vhosts and empty names.

## Design notes
`core/access` for the matcher and the case table; `ui/access` for the form. Pure functions, sealed result types, no boolean flags that switch behaviour. If a pattern can hang the matcher (catastrophic backtracking), bound the work (input length and a step or time budget with a clear result `UnreadablePattern`-like `TooComplex`).

## UX
Preview form and verdict of `Access.dc.html`; verdict wording AQ5.

## MCP
`caerbannog_check_access` in `M10-08` uses the same matcher; nothing sent beyond the permission read.

## Open points
MEASURED (M10-00 S-I, 137 rows in `data-rabbit/src/test/resources/fixtures/m10-00/s-i-pattern-rows.tsv`, columns `permission, pattern, name, operation, outcome`): the matcher must be an unanchored, case-sensitive regular-expression search (`(?i)` works); `^$` and the empty pattern allow no named object; `.*` allows all; write is `basic.publish` to an exchange, read is `basic.get` and `basic.consume` on a queue, configure is declare; `queue.bind` needs write on the queue and read on the exchange; the default exchange is checked as `amq.default` (never as the target queue); topic permissions on `amq.topic` use the same search rule on the routing key and with no topic permission nothing is limited; an MQTT topic `a/b` is checked as `a.b`; streams: write declares a publisher, read subscribes, configure creates; an invalid expression cannot be set on the broker (so `unreadable_pattern` is for the app's own limits). The 137 rows ARE the differential fixture of this brief (fast test: `AccessMatcher` agrees with every row) and of the gate `M10-09`. Still unmeasured: Erlang versus Java differences beyond `(?i)`, `^`, `$`, `.*` (character classes, lookahead, Unicode, named groups). Add rows for those first; any construct that differs is reported as "cannot be predicted". Earlier text, kept for that case: if S-I shows Erlang's dialect differs from `kotlin.text.Regex` in ways the case table cannot cover, record the differences and either narrow the preview to patterns without those constructs (others report "cannot be predicted") or stop and ask. Do not ship a preview that disagrees with the broker.

## Definition of done
Tests first and passing, the live differential test green on the pinned image, spec "Measured facts of M10-00" cites the table, README updated.
