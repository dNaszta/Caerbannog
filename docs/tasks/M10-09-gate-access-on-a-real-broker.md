# M10-09: Gate: the Access view on a real broker (`RealBrokerM10GateTest`)

Milestone: M10    Depends on: M10-02 to M10-08 (the gate lands last; its fixture can start after the spike)    Size: M

## Goal
`RealBrokerM10GateTest` (`@gate-M10`, tag `broker`, in `brokerTest`) proves on a real RabbitMQ 4.x broker that the product gateway reads who am I, sign-in method and clients correctly, that no value read contains a token or a password, that the match preview agrees with the broker, and that no queue message was read.

## Why
Every milestone from M2 has a live gate (spec "Milestones"). The risks of M10 (a leaked secret, a wrong preview) are the kind only a real broker answers.

## Scope
In: one container class built as in the spike (OAuth 2 plugin, static key, internal and token users, `BrokerImages` pin); the product gateway (not the spike's raw calls) reads: whoami and vhosts for an administrator, a `monitoring` and a `management` user, token and internal; the sign-in detection; clients (an AMQP consumer and publisher, a stream publisher and consumer, an MQTT client, of different users, one with a JWT-shaped and one with a credential-URI `connection_name`); every string the gateway returned is searched for the token, the password and the URI secrets; the case table of `M10-04` is run once more; queue depth and a second reader prove no message was consumed or acknowledged by the app (publish N, read the lists, queue still has N).
Out: UI, MCP (covered by their own tests), clusters, 3.x.

## Acceptance
- BDD: `23` "A real broker with the OAuth 2 backend is recognised and no token leaks", `24` "A real broker's clients are listed by user and nothing leaks", `22` "The preview agrees with a real broker for a table of cases" (all `@gate-M10 @broker`).
- `./gradlew :data-rabbit:brokerTest --tests '*RealBrokerM10Gate*'` green on the pinned image; deterministic (polling on values, bounded, no sleeps; statistics lag respected); throwaway users, queues and connections cleaned up; time added to the `brokerTest` budget stated in the report.

## Design notes
`data-rabbit` test code; reuse `RabbitContainers` and the token minting helper of the spike (move it to a shared test helper in its own commit). The case table is the fixture from `M10-04`. If the gate is slow, keep one container per class.

## UX
None.

## MCP
None here; `M10-08` tests carry the MCP side with fakes.

## Open points
S-K (Keycloak) is not part of the gate.

## Definition of done
Gate green, timeout check against the `brokerTest` limit in the root `build.gradle.kts` and the CI step (`docs/tasks/M2-18`), milestone status updated by the architect, README rows closed.
