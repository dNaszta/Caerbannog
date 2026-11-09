# Definitions model and file import (3.x export)

Milestone: M8    Depends on: M8-00    Size: M

## Goal
A `definitions.json` exported from RabbitMQ 3.x can be read into a typed `core` model in which secrets exist only as redacting pass-through values, with clear errors for files that are not definitions.

## Why
Spec "Migrating from RabbitMQ 3.x (M8)", decision 1 (file source) and "3.x as a read-only source" (no secrets in the model). The parser is shared with the live source (`M8-02`).

## Scope
In: `core` `DefinitionsDocument` (vhosts, queues, exchanges, bindings, policies, operator policies, parameters by component, users (name, tags, hashing algorithm, `passwordHash` as a pass-through `SecretValue`, limits), permissions, topic permissions, source version if present), `DefinitionsParser.parse(text): ParseResult` (sealed: Parsed, Rejected(reason)), tolerance for unknown keys, typed argument values (number, string, boolean, list), dropping of shovel and federation parameter URIs at parse time; `SecretValue` for the user hash (redacting `toString`, no serialization to any report, never logged; P2 decided 2026-11-09), a file chooser action on the new Migrate-from-3.x entry point that only loads text (UI wiring in `M8-06`). Fixtures from `M8-00` S-A drive the tests.
Out: analysis, live fetch, UI screen.

## Acceptance
- BDD: `16_migrate_from_3x.feature` / "Analyse a definitions file exported from RabbitMQ 3.x" (@smoke @fake-broker), "A file that is not a definitions export is rejected" (outline), "Secrets in a 3.x export never reach the report" (@security @fake-broker; the model half here, the report half in `M8-03`).
- Unit tests: the real 3.13 fixture round trip; unknown keys ignored; missing optional sections; a 20 MB file parses without holding it twice; the model's `toString`, string templates and a data-class dump of a document with a user contain no hash and no URI.

## Design notes
`core` only, no JSON-library types leaking if avoidable (kotlinx-serialization is already used in `core` for migrations: reuse its conventions). Typed values matter because the current `DeclareQueue.arguments` is `Map<String,String>` (see `M8-04`). Errors as a sealed result, never an exception from the parser.

## UX
None here.

## MCP
None here (the report tool is `M8-10`).

## Open points
Shape of the real export is UNVERIFIED until `M8-00` S-A; the tests use its fixture, not a hand-written one.

## Definition of done
Tests first and passing, no unrelated changes, `16` steps written, spec updated if the export shape differs from this brief.
