# The Check says "may not use TLS" for a wrong CA

Milestone: M2    Depends on: M2-14c (merged)    Size: S

## Goal
"Test connection" with TLS on and an untrusted certificate (wrong or missing CA) says "The certificate was not trusted", as Connect does; only a broker that does not answer the handshake gets the "may not use TLS" hint. One classification, shared by Check and Connect.

## Why
Found by the M2-14c review. `StreamAndManagementReachabilityChecker` maps every `SSLException` (both legs) to `ConnectionCheckResult.TlsRefused`, whose text is now the shared `ConnectionFailureReason.NO_TLS_ANSWER`. For a wrong CA the Check therefore says "The broker did not answer the TLS handshake", which is not true, while Connect (after M2-14c) says "The certificate was not trusted". Pre-existing (accepted in M2-14 P4 as hedged: "may not use TLS"), not made worse by 14c, but the two buttons now disagree on a security screen.

## Scope
In: a typed split of `ConnectionCheckResult.TlsRefused` into certificate rejected and no TLS answer, using the same rule as `ManagementHttp.classify` (a `CertificateException` in the cause chain, depth limit) extracted to one shared function in `data-rabbit` (the stream leg `SSLSocket` and the management leg both use it); a new Check text for the certificate case that reuses `ConnectionFailureReason.CERTIFICATE_NOT_TRUSTED`; update `ConnectionCheckTextsTest`; one `01` scenario "A certificate that is not trusted does not suggest turning TLS off on the Check" (`@negative @security @fake-broker`), written first; extend the existing real-broker Check test for a wrong CA. Measure the stream-leg `SSLSocket` shapes first (wrong CA, plain port) as the 14c measurement covered the HTTP client and the stream `Environment`, not the raw `SSLSocket`.
Out: any change to Connect, to TLS defaults or to the switch; a "Turn off TLS" button.

## Acceptance
- BDD: `tests/bdd/features/01_*.feature` new scenario above (to be written first); existing "A TLS connection to a plain broker is refused with a hint" stays green.
- Rule checks: no plain fallback, no credential before the choice, TLS flag unchanged.

## Design notes
Extract the classifier out of `ManagementHttp.companion` into a small internal object (problem removed: the same rule copied in two catch blocks would drift). Faked: `FakeConnectionChecker`/fake gateway; real: dev broker TLS ports with a wrong CA.

## UX
None (text only).

## Open points
Whether the stream-leg `SSLSocket` gives the same shapes as the HTTP client (plain port: bare `SSLException`?) is not measured. Measure before building; if not separable on that leg, keep the hedged hint on that leg only and say so in the brief.

## Definition of done
Tests first and red for the right reason, passing; spec D9a text unchanged except the Check sentence; README row.
