# Compatibility analyzer (pure core rules, severity, report)

Milestone: M8    Depends on: M8-00, M8-01    Size: L

## Goal
`CompatibilityAnalyzer.analyze(DefinitionsDocument): CompatibilityReport` lists every incompatibility with severity, reason and a proposed 4.x equivalent, counts the compatible items, and exports as JSON.

## Why
Spec "Compatibility report". The owner's biggest need: know what in the 3.x topology does not work on 4.x.

## Scope
In: `core` rule engine (a rule is a small class or table row: id, applies-to, check, severity, why text, proposal builder, `evidence` fixture name, `verified`), the report model and JSON export (`caerbannog.compat-report/1`), plain-text rendering, rules for what is verified and what `M8-00` measured: mirroring policy keys (`ha-*`), mirrored or plain classic queues (propose quorum), the queue argument matrix S-C, policy key matrix S-E, vhosts, users and permissions are findings with proposals that become access actions (`M8-12`): `guest` (warning, default skip, P11), `administrator` tags (warning), hash algorithm not accepted by 4.x (blocker, proposal manual, P12), users without a hash (info); the report carries user name, tags, algorithm and `hasPasswordHash` only, never the hash; not-covered parameters (federation, shovels, other plugins), 4.x import rejects from S-B, client-visible changes from S-I as global findings. A rule without spike evidence is emitted as `verified = false` and shows "unverified".
Out: reviewing, migration output, UI.

## Acceptance
- BDD: `16` / "Every finding says what, how serious, why and what to do on 4.x", "Mirroring settings of a policy are reported as having no effect", "A mirrored classic queue is proposed as a quorum queue", "Queue settings that a quorum queue does not accept are reported" (@needs-spike), "Switching a classic queue to quorum is reported when behaviour changes" (@needs-spike), "Items that need no change are not listed as problems", "Users, virtual hosts and permissions become proposals for the access migration", `18` / "The report shows users without their hashes", "guest is proposed for skipping", "A user with an unsupported hash algorithm is a manual step", "Parameters the app does not migrate are listed as not covered", "Export the report", "Secrets ... report half".
- Unit tests: one per rule with its fixture; a test that fails when a rule has no evidence and is not flagged unverified; the report is deterministic (stable order: vhost, kind, name, rule).

## Design notes
Pure functions, no I/O, `core` only. Findings are immutable data with sealed `Proposal` (DropKey, ConvertQueueType, SetArgument, RemoveArgument, Manual, None) so `M8-05` can turn them into actions without parsing text. Do not word anything as fact that `M8-00` did not measure, especially the Tanzu start-up claim.

## UX
None here.

## MCP
Consumed by `M8-10`.

## Open points
Severity of each matrix row is fixed from the spike results; the `16` outline's severities are proposals.

## Spike findings (M8-00, 2026-11-09)
Rules established by the spike (full table in the spec "Facts" and `M8-00-spike-results.md` S-B to S-E, S-G, S-H, S-I): every `ha-*` policy key and `queue-master-locator` is a blocker (REJECTED on 4.x, not "no effect"; correct any text that says otherwise); transient or non-durable queues and `x-queue-master-locator` queues are blockers because a 4.x import skips them silently with HTTP 204; for a quorum target `x-max-priority`, `x-queue-mode`, `x-queue-version`, auto-delete are blockers of that conversion and the rest convert as is; `x-max-in-memory-*` warning; EVERY classic to quorum conversion gets the ordering warning (a 4.x quorum queue always orders by message priority, messages without a priority sort ahead of priority 3, a classic queue without `x-max-priority` is FIFO; stronger text for `x-max-priority` queues; the review needs an explicit tick, P15); keeping a classic queue is valid on 4.4 except transient and master-locator; operator policies from `parameters`; users: algorithm outside sha256, sha512, md5 is a blocker (`M8-12`); global "check your clients" info items from the broker's deprecation list; a global warning that an in-place upgrade can lose classic queue messages (S-N, undiagnosed) recommending the blue-green path; the live signal `deprecated-features/used`. `@needs-spike` removed from the scenarios of this brief; unverified items (exclusive queues, redelivery, delivery limit, global QoS, `x-max-in-memory-*` effect) are emitted as `verified = false`.

## Definition of done
Tests first and passing, every shipped rule traced to a fixture, spec "UNVERIFIED" list shrunk accordingly.
