# Block forwarding when RabbitMQ 4.4.1 would refuse the direct destination

Milestone: M8 (follow-up)    Depends on: none (M8-07, M8-08, M8-09 are merged); runs in parallel with `M2-18`, before `M2-19`    Size: S to M

## Goal
On a destination broker of RabbitMQ 4.4.1 or later, a user who is not an administrator is stopped at the dry run with a clear message instead of a bare "The broker did not accept the shovel definition"; the Shovel keeps the credential-free destination `dest-uri: "amqp://"`, and an administrator still forwards.

## Why
Owner decision 2026-11-19 (A), option 3: keep the direct destination, block when the broker would refuse it. Found by the live suite: since 4.4.1 `PUT /api/parameters/shovel/%2F/<name>` with `dest-uri: "amqp://"` answers 400 for a non-administrator, so `RealBrokerM8ShovelTest` (3 tests) and `RealBrokerM8SpikeShovelTest` S-F are red on `rabbitmq:4-management`. `docs/spec.md` "Forwarding messages with a dynamic Shovel" item 6 and the Shovel row of the measured facts say "policymaker is enough", which is no longer true on 4.4.1. Safeguard 7 (no credentials in a URI the app does not need to store) is why the app does not put the user's own password into the destination to get around the refusal.

## Measured facts (architect, 2026-11-19, throwaway brokers, same PUT body, `ack-mode: on-confirm`)
| Broker | user tags | `dest-uri` | Answer |
| --- | --- | --- | --- |
| 4.4.1 | administrator | `amqp://` | 201 |
| 4.4.1 | administrator + policymaker | `amqp://` | 201 |
| 4.4.1 | policymaker (with or without management, monitoring) | `amqp://` | 400 `Validation failed: user "pm" may not use a URI without a hostname or a username` |
| 4.4.1 | policymaker | `amqp:///%2F` | 400, same text |
| 4.4.1 | policymaker | `amqp://pm@` | 400 `may not use the username "pm" without a password` |
| 4.4.1 | policymaker | `amqp://pm:pw@localhost` | 201 (a URI with user, password and host is accepted: this is what the app must NOT do) |
| 4.4.1 | monitoring only | any | 401 `not_authorised` (unchanged: creating needs policymaker or administrator) |
| 4.4.0 | policymaker | `amqp://` | 201 (the rule is new in 4.4.1) |
| 4.3.7 | policymaker, administrator | `amqp://` | 201 |
| 3.13.7 | policymaker, administrator | `amqp://` | 201 (3.x is never a destination: the app never writes to a 3.x broker; listed for completeness) |
Not measured: 4.4.2 and later, 4.5 (the rule is assumed to stay; the start-time mapping below covers a surprise). A monitoring-only user is already blocked by `NoPolicymakerTag`.

## The rule
Block with the new `ForwardingBlock.DirectDestinationNeedsAdministrator` exactly when ALL hold: the destination broker version is known and is at least 4.4.1 (compare the three numbers; a pre-release suffix such as `-rc.1` is ignored after the numbers), and `administrator` is not in the user's tags (`GET /api/whoami`). Anything else is not blocked by this rule: an unknown or unparsable version never blocks in advance (fail open here is correct: the broker is the authority, and the second line of defence below catches it).
Second line of defence, in `ManagementShovelGateway.start`: a 400 whose body contains the broker's words `without a hostname or a username` maps to a new `StartOutcome.DirectDestinationRefused`; `ForwardingService` turns it into the same message as the block and sends nothing more (no retry, no other URI). The broker text is matched, never shown, never logged (safeguard 7).

## Scope
In:
1. `core`: `BrokerVersion` gets a pure `atLeast(major, minor, patch)` (the file has the major-only parser today, `core/src/commonMain/.../connections/BrokerVersion.kt`, with its test `BrokerMajorVersionTest`); `DryRunResult` gets `brokerVersion: String?`; `ForwardingBlock.DirectDestinationNeedsAdministrator`; the rule in `ForwardingPlanner.plan`; the text in `ForwardingTexts.block`; `StartOutcome.DirectDestinationRefused` and its handling in `ForwardingService.start`; the fake `FakeShovelGateway` can be given a version (default 4.3.7) and can be told to refuse the direct destination at the start.
2. `data-rabbit`: `ManagementShovelGateway.dryRun` reads the version with one GET `/api/overview` (`rabbitmq_version`; 401 or any failure gives null) and passes it in the result; `ShovelPathAllowList` allows exactly `GET /api/overview` (test updated: it is a GET, no query, and PUT, DELETE and other `/api/*` shapes stay refused); the 400 mapping above.
3. Live tests: `RealBrokerM8ShovelTest` and `RealBrokerM8SpikeShovelTest` S-F follow the measured table (see Design notes); the new `@broker` scenario.
4. Docs: `docs/spec.md` (see "Spec changes" below), `tests/bdd` (done in this brief's commit).
Out: any change to `dest-uri` (stays the constant `amqp://`), any URI with credentials for the destination, an option "use my password for the destination", changing the Shovel name, `ack-mode` or `src-delete-after` handling, a Shovel as a migration action, an MCP tool that creates or removes a Shovel, support for a 3.x destination.

## Acceptance
- BDD: `tests/bdd/features/17_forward_messages_with_shovel.feature`, tags `@m8-15`:
  - `A policymaker who is not an administrator is blocked on RabbitMQ 4.4.1 and later` (outline: 4.4.1, 4.4.2, 4.5.0, 5.0.0) (`@fake-broker`)
  - `A broker before 4.4.1 does not block a policymaker for this reason` (outline: 4.4.0, 4.3.7, 4.0.2) (`@fake-broker`)
  - `An administrator is not blocked on RabbitMQ 4.4.1` (`@fake-broker`)
  - `A broker that hides its version is not blocked in advance and the refusal at the start is told the same way` (`@fake-broker`)
  - `The refusal never makes the app put credentials into the destination` (`@fake-broker`)
  - `On a real RabbitMQ 4.4.1 the policymaker is blocked and the administrator forwards` (`@broker`, bound by `RealBrokerM8ShovelTest`)
  - The existing `@m8-07`, `@m8-08`, `@m8-09` scenarios of the feature stay green unchanged (the fake broker is 4.3.7 by default).
- Unit tests: `BrokerVersion.atLeast` table (4.4.0 no, 4.4.1 yes, 4.4.10 yes, 4.10.0 yes, 5.0.0 yes, 4.3.99 no, `4.4.1-rc.1` yes, "", "4.4", "x" null or no); `ForwardingPlannerTest` (block present or absent per version and tag; unknown version absent; order of blocks unchanged, the new one last); `ManagementShovelGatewayTest` over a fake HTTP engine (the overview GET is made once, only on the dry run; the 400 text maps to the outcome; a 400 of another text still gives "did not accept"; the PUT body still has `"dest-uri":"amqp://"` and the source password only inside `src-uri`); `ShovelPathAllowListTest`; `ShovelGatewayShapeTest` and the no-message-read guard still green.
- Live (`broker`): the shovel test classes name `rabbitmq:4.4.1-management` literally (they must test the broker that has the rule; `M2-19` later moves the literal into a shared constant): `RealBrokerM8ShovelTest` and `RealBrokerM8SpikeShovelTest` green; `RealBrokerM8GateTest` and `RealBrokerM7GateTest` green.

## Design notes
- Message (architect proposal, owner said "do arch suggest" for wording; plain, no jargon beyond the tag name):
  - Block text (`ForwardingTexts.block`): `RabbitMQ 4.4.1 and later lets only an administrator create a shovel that sends to its own broker. The user of {destination} is not an administrator. Use an administrator user for this connection, or ask the administrator of {destination} to create the shovel.`
  - Start-time text (same words, same block text reused): the screen shows it in the same place as the other blocks; no HTTP status, no broker text.
  - The block is shown together with the other blocks (a user with neither tag sees both).
- Why the app does not work around it: the only accepted destination URI for a policymaker carries user, password and host. That would store a second copy of the user's password on the broker and break safeguard 7's principle. Option 3 was chosen by the owner for this reason.
- The administrator tag is read from `whoami` as today (`DryRunResult.userTags`); no new permission call. The version comes from `GET /api/overview`, a read that the shovel allow-list gains deliberately. A user without the management tag cannot read it: then version null, no block in advance, the second line of defence applies.
- Tests that change (data-rabbit, test code only): the user `svc` of `RealBrokerM8ShovelTest` (tags `management,policymaker,monitoring` today) is used for the happy paths: make it `administrator` there, keep `pm` (management, policymaker) as the user who is blocked on 4.4.1, and assert `Blocked` with the new block plus, with the gateway called directly, `DirectDestinationRefused`. In `RealBrokerM8SpikeShovelTest` S-F, split the "management+policymaker may create" line: it holds on 4.3.x and 4.4.0, not on 4.4.1; with the image `rabbitmq:4.4.1-management` assert 400 with the words of the table and that an administrator gets 201; the spike class records the fact, it does not need a version switch. Check which user `RealBrokerM8GateTest` uses for the shovel; if it is not an administrator it goes red on 4.4.1 too: make it an administrator there (the gate proves the flow, not the tag).
- Layering: no Compose change beyond the existing block list rendering; `core` stays free of `java.*`.
- Safeguards that must stay intact (checked by the existing tests, do not weaken): typed confirmation naming source and destination; dry run first and repeated at the start; `ack-mode` only `on-confirm`; source queue never deleted; no write to a 3.x broker; the URI never logged, shown or returned (the new outcome carries no text from the broker); no read path for queue messages; Remove shovel and cleanup rules unchanged; TLS: the source URI scheme still from the profile with no fallback.

## UX
`docs/ux/` forwarding dialog (the blocked state of M8-09, `Blocked` state in `ForwardingScreenState`): one more block line in the existing list; no new layout. Use the existing `testTag` pattern of the block lines (look at the tags of `NoPolicymakerTag`) and give the new one a stable tag, for example `forwarding.block.directDestination`.

## MCP
None. CLAUDE.md forbids an MCP tool that creates or removes a Shovel. `caerbannog_list_shovels` (read-only, M8-10) needs no change: it never creates anything. The version read is internal to the dry run and no tool exposes it.

## Open points
- Product wording: proposed above, not blocked on the owner (he asked the architect to suggest); he may change the sentence in review. Recommendation: keep it.
- Behaviour on 4.4.2 and later and 4.5 is unmeasured (the live tests cover 4.4.1 only). The non-blocking CI job on the floating `4-management` tag (`M2-19`) will show a change.
- Unverified whether a vhost-limited administrator also passes; the rule only tests the tag.

## Spec changes (made with this brief, by the architect)
`docs/spec.md`: the Shovel bullet in "Milestones" item 3, safeguard 6 and the Shovel measured-facts row now say that the policymaker tag is enough only before 4.4.1, and that from 4.4.1 the broker refuses the direct destination `amqp://` from anyone who is not an administrator, with the block and its message; a new Risks line records the owner decision (A).

## Status
- Step 2 (tests first) written 2026-11-20 on branch `M8-15-shovel-direct-uri-on-4-4`, RED, nothing built. Names the tests expect from production: `brokerVersionAtLeast(text: String?, major, minor, patch): Boolean` (top-level in `BrokerVersion.kt`, false for null or unparsable), `DryRunResult.brokerVersion: String?` (last parameter), `ForwardingBlock.DirectDestinationNeedsAdministrator`, `StartOutcome.DirectDestinationRefused` (an object), and in `ForwardingService.start` that outcome becomes `StartResult.Blocked(ForwardingPlan(listOf(DirectDestinationNeedsAdministrator), emptyList()))` (same place and text as the block; no new result type).
- Fake: `FakeShovelGateway.brokerVersion(text)` (default 4.3.7) and `refuseDirectDestination()`. Steps in `ForwardingCoreSteps`; suite filter widened to `(@m8-07 or @m8-15) and @fake-broker`.
- Live: `RealBrokerM8ShovelTest` (administrator `svc`, `pmm` policymaker+monitoring+management, image `rabbitmq:4.4.1-management`, new M8-15 test) and `RealBrokerM8SpikeShovelTest` S-F split. `RealBrokerM8GateTest` uses `guest` (administrator): unchanged. `RealBrokerM8SpikeAmqpsShovelTest` still on `4-management` with a policymaker `svc`: see the report.
- Step 3 (senior developer, 2026-11-20) built, ready for test. `brokerVersionAtLeast` (core), `DryRunResult.brokerVersion` (default null), `ForwardingBlock.DirectDestinationNeedsAdministrator` (last), the rule in `ForwardingPlanner.plan`, `StartOutcome.DirectDestinationRefused` mapped in `ForwardingService.start` to `StartResult.Blocked`, the approved wording in `ForwardingTexts`, `ManagementShovelGateway` (one GET `/api/overview` on the dry run only; 400 with "without a hostname or a username" maps to the outcome, the text is matched and never carried), `ShovelPathAllowList` allows `GET /api/overview`. `dest-uri` stays `amqp://`. No UI change: the block line reuses `forward-block-<n>`. Live: `RealBrokerM8GateTest`, `RealBrokerM7GateTest`, `RealBrokerM8SpikeShovelTest` green; `RealBrokerM8ShovelTest` 5 of 6 green, the happy path fails at its own line 117 (`assertTrue("policymaker" in ready.facts.userTags)`): `svc` is an administrator now, so the tag is absent. Test-side fix needed (test developer): assert `"administrator"`.
- Step 4 (test developer, 2026-11-20) verified. Test-side fix 65d46ac8: the happy path asserts `administrator` (svc is administrator-only). Live on `rabbitmq:4.4.1-management`: `RealBrokerM7GateTest` 4/4, `RealBrokerM8GateTest` 1/1, `RealBrokerM8SpikeShovelTest` 6/6 green; `RealBrokerM8ShovelTest` 6/6 green on two reruns after the fix (slow: Docker shared with the M2-18 run). Fast suite (core, data-rabbit, acceptance-tests, ui, mcp-server, desktopApp) BUILD SUCCESSFUL; the `@m8-15` `@fake-broker` scenarios pass in `RunForwardingCoreAcceptanceTest` (0 failed); the `@broker` one is bound by `RealBrokerM8ShovelTest`. Mutation (rule from 4.4.0 instead of 4.4.1) turns `ForwardingPlannerTest` red; reverted, tree clean. Diff skim: allow-list gains exactly `GET /api/overview`, no new write path, no MCP change, the new outcome carries no broker text. Not done: a UI test of the block line (no change in the UI, it reuses `forward-block-<n>`), the second mutation (400 mapping).

## Definition of done
Tests written first and red for the right reason (the planner test fails because the block does not exist; the live tests fail with the 400), then green; the three red live classes green on 4.4.1; gates green; no unrelated change; `THIRD_PARTY_NOTICES.md` untouched (no dependency).
