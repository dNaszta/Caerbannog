# Connect hint "the broker may not use TLS"

Milestone: M2    Depends on: M2-14 (14a and 14b, the TLS switch and the Check hint)    Size: S

## Goal
When Connect with "Use TLS" on fails because the broker does not answer the TLS handshake (it is probably a plain broker), the failed state says so and names the switch: "The broker did not answer the TLS handshake. It may not use TLS; turn off 'Use TLS' in this connection's settings if that is intended." A wrong CA keeps its own reason ("The certificate was not trusted"). The hint only tells: the app never turns TLS off, never retries without TLS, and sends nothing in clear text.

## Why
Spec D9a (TLS default, per-profile switch, plain only behind that switch, no credential before the user has chosen). M2-14 ruled in P4 (`docs/tasks/M2-14-plain-connections.md`, "Step 2 architect rulings") that the Check result gets the hint but Connect does not, because Connect and Check map every `SSLException` the same way and a wrong CA and a plain broker cannot be told apart. The user's own first trial was exactly this: a local broker, TLS on, an unhelpful failure. The follow-up row "M2-14c" is in the M2-14 brief ("Follow-ups") and says: measure first, then ask the user. The user has now asked for it.

## Scope
In:
- Measure (test developer, step 2) what a Connect with TLS on actually raises in these cases, against the dev broker (`task dev:up`, `task dev:seed:plain` not needed): (1) TLS profile pointed at the plain ports 5552 and 15672; (2) TLS profile with a wrong or missing CA against the TLS ports 5551 and 15671 (the `certificate_unknown` case); (3) host name mismatch (D7: tolerated, so it must still connect); (4) a port with nothing listening; (5) a non-RabbitMQ TLS server if one is cheap to start. Record, per case, the exception class, its message and its cause chain in the Status of this brief. Use `RealBrokerPlainTest` (case 1, it already has "Connect with TLS on at the plain management port fails") and `RealBrokerCatalogTest` (case 2). Nothing below is assumed until this is written down.
- Gateway (`RabbitMqConnectionGateway.probe`): a new failure reason for "no TLS handshake answer", chosen only when the measurement shows a reliable difference from a certificate failure. Today `ManagementReply.TlsRefused` (any `SSLException`) maps to `CERTIFICATE_NOT_TRUSTED`.
- Core: a new `ConnectionFailureReason` text, built from one shared constant that the Check text (`checkResultMessage`, `TlsRefused`) also uses, so the two cannot drift.
- Scenarios in `13` (see Acceptance) and unit tests for the classification.
Out:
- Any change when TLS is off (the hint is never shown then).
- Automatic switching, a "Turn off TLS" button, or a retry without TLS. A button would be a new decision (it moves the credential path to plain); not part of this task.
- Changing the Check texts (pinned in `ConnectionCheckTextsTest`).
- Hint on the stream-only failure path: Connect probes the management API first (`whoami`); the stream `Environment` is built later and is not a Connect failure.

## Acceptance
- BDD (all in `tests/bdd/features/13_connection_lifecycle.feature`, written in this task, red until built):
  - "Connecting with TLS on to a plain broker says the broker may not use TLS" (@negative @security @fake-broker)
  - "A certificate that is not trusted does not suggest turning TLS off" (@negative @security @fake-broker)
  - "A connection with TLS off never shows the TLS hint" (@fake-broker)
  - Existing, must stay green: "A connection that cannot be used says why" (the four reasons keep their texts), "A TLS connection to a plain broker is refused with a hint" in `01` (Check).
- New steps the test developer writes (reusable): `the reason says the broker may not use TLS`, `the reason says the switch can be turned off in the connection settings`, `the reason does not mention turning TLS off`, `the reason does not mention TLS`, `the saved connection "<n>" still has TLS on`, `no credential was sent`; `the broker only offers plain connections` and `no attempt without TLS was made` already exist for `01` and are shared, not copied. The fake gateway gets a failure kind for the new reason (`FakeConnectionGateway.Failure`).
- Unit (data-rabbit): classification table from the measurement, one test per measured case. If the measurement says the cases cannot be told apart, see "Open points", do not guess.
- @broker (JUnit, not Gherkin, same reason as P9 of M2-14): extend `RealBrokerPlainTest` "Connect with TLS on at the plain management port fails" to assert the new reason, and `RealBrokerCatalogTest` wrong CA to assert it stays `CERTIFICATE_NOT_TRUSTED`.
- Rule checks (blockers if missing): TLS flag of the saved profile unchanged after the failure; no HTTP request with an Authorization header reaches a plain port; no password in the reason or in logs.

## Design notes
- Data: classify in one place (the `SSLException` catch in `ManagementHttp`), returning a typed reply, e.g. split `ManagementReply.TlsRefused` into `CertificateRejected` and `NoTlsAnswer`, instead of the gateway inspecting exceptions. The problem it removes: one catch-all that makes two different user actions (set the CA, turn TLS off) look the same. Not a boolean flag on the reply.
- Core: `ConnectionFailureReason` gets one constant (suggested name `NO_TLS_ANSWER`); the Check text and the Connect reason share the sentence. `LinkState.Failed.reason` stays plain text without secrets; the UI shows it as today, no new screen state.
- The wrong-CA text and the "certificate" naming stay for the certificate case. With TLS off no reason mentions TLS (the gateway cannot raise either TLS reason then).
- Faked in `@fake-broker` scenarios: `FakeConnectionGateway` and the fake broker "only offers plain". Real in `@broker`: dev or Testcontainers broker with plain listeners (shared fixture from M2-14, not a copy).

## UX
`docs/ux/` Connections screen: the Failed state of the profile row and panel already shows `reason`; no layout change. The longer text must wrap, not clip (check in `ConnectionsScreenUiTest`; add a test only if the text is cut off).

## Open points
- Unverified: whether the exception shape differs between "plain broker" and "wrong CA". Expected to be a guess until measured: a wrong CA likely shows `SSLHandshakeException` with a `CertPathValidatorException` or `ValidatorException` cause, a plain stream port likely shows an SSL "unrecognized message" or a closed connection, and a plain HTTP port answers HTTP to a ClientHello (JDK HTTP client maps this to its own exception). These are not facts. Meanwhile the test developer measures as described under Scope.
- If the cases are NOT reliably separable (decision D-A below): fallback is a hedged hint on the generic TLS failure, i.e. the certificate reason gets a second sentence "If this broker does not use TLS, turn off 'Use TLS' in this connection's settings." Recommended over doing nothing, because it is true in both cases and never claims a cause.
- Wording is the architect's and is fixed above; it matches the Check text.

## Decisions for the product owner
- D-A (only if measurement shows the cases cannot be told apart): (1) hedged second sentence on the certificate failure (recommended; slightly noisier for a real wrong-CA case, but accurate); (2) no hint on Connect at all, keep P4 as is (the user then sees the hint only on Test connection); (3) classify by guess (rejected: a wrong claim on a security screen). Blocked until decided: step 3 only for this case; the measured-separable path needs no decision.

## Definition of done
Tests written first and passing (new scenarios red for the right reason before the build), the measurement written into the Status, no unrelated changes, spec D9a / Connection lifecycle text mentions the Connect hint (the architect adds it at acceptance), README row updated.

## Status
Step 1 (architect, 2026-11-05): brief written and three scenarios added to `13_connection_lifecycle.feature`; no app code. Next: step 2, test developer measures the five cases against the dev broker, writes them here, then writes the steps and tests (red). The scenarios are written for the separable case; if the measurement says "not separable", the architect rewrites the scenario 2 expectation per D-A.
