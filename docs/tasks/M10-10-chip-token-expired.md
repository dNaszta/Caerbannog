# Connection chip says "Token expired"

Milestone: M10    Depends on: M10-00 (S-D, S-J: how a refused or expired token looks), M10-02 (Access gateway and token-refusal state), M10-01 (mockup reviewed)    Size: M

## Goal
When the broker refuses the sign-in of a profile whose password is a token (OAuth 2, AQ4), the app bar chip and its popup row say "Token expired" instead of a generic failure, and every screen that shows the connection behaves as for a lost connection with that reason.

## Why
Owner decision on Q4, 2026-11-25: the chip itself says so. Spec "Connection lifecycle (M2 decisions D1, D2, D6, D7)" and "Access view (M10)" (token expired is a first-class state, AQ4). A token has a lifetime set by the identity provider, so the owner's users meet this often.

## Scope
In:
- A new reason in the connection state, `Failed(TokenExpired)` (or a reason value next to "Credentials were refused" and "Connection lost"), shown as "Token expired" on the chip, the chip popup row and the connection panel. The chip text is the owner's wording: "Token expired" is a PLACEHOLDER until the owner confirms the exact words; the popup explanation sentence is also a placeholder.
- Telling it from other refusals. The broker only answers 401 with a body. What distinguishes an expired token from a wrong password or a token that was never valid is unknown. It is measured by the spike M10-00 (S-D, S-J). Until then the app treats a 401 on a profile that is known to hold a token as "Token expired" only if the spike shows a reliable signal; if the broker cannot tell, the fallback wording is the owner's (options: "Token refused or expired" on the chip, or the plain "Credentials were refused" with the token hint only in the popup). How the app knows a profile holds a token (the OAuth 2 detection of `M10-05`, or a per-profile marker) is part of the same decision.
- Behaviour while in that state: the same as Failed("Connection lost") (D2): the clients are closed, the last rows stay and are marked stale, Retry is offered; no automatic reconnect and no automatic token refresh (the app never talks to an identity provider). The stream refresh loop and the Access tabs stop refreshing (D3) and show the "token expired" refusal banner of `Access.dc.html` in place of the data. The text points at the connection settings, where the user pastes a new token as the password.
- Mockup: `docs/ux/Main.dc.html` gets the chip state and the popup row (and the connection panel line); `Access.dc.html` keeps its banner and may say the chip says the same. Mockup first, owner review, then code.
Out: refreshing or fetching a token, reading the token's expiry time (AQ3: no local decoding in v1), any identity provider call, any new control that changes the broker.

## Acceptance
- BDD, new scenarios appended to `tests/bdd/features/13_connection_lifecycle.feature` (tags `@M10 @fake-broker @m10-10`), by name:
  - "A refused token shows Token expired on the chip and the popup" (needs the spike signal; `@needs-spike`)
  - "A refused token is told from a refused password" (`@needs-spike`; if S-D/S-J show no signal, replaced by "When the broker cannot tell, the fallback wording is shown", `@needs-decision`)
  - "A connection with Token expired keeps the last data as stale and offers Retry"
  - "The stream refresh and the Access tabs stop refreshing while the token is expired"
  - "The token and the password never appear in the chip, the popup or the message" (`@security`)
  - "Retry after a new password is saved connects again"
- A Compose UI test of the chip and popup row for this state, with `testTag`s kept stable.
- No change for profiles that are not token profiles: a refused password still reads "Credentials were refused".

## Design notes
`core`: a typed reason in the connection state (sealed result, no string matching in the UI); the decision "is this refusal a token expiry" is a pure function over what the gateway reports, unit-tested with fakes. `data-rabbit`: the mapping from the broker's 401 (matched by mapped reason, never by passing the body through, SecretGuard rules apply). `ui`: chip, popup row, panel line. The composition root wires nothing new.

## UX
`docs/ux/Main.dc.html`: chip state "Token expired" (with the Not encrypted marker still shown on a plain profile), popup row, panel line. States: connected, token expired, token expired and not encrypted.

## MCP
None for tools. The Access summary tool already reports the connection state; it may carry the same reason in its existing status field (`tokenExpired`), with no new tool and no token text. Decide in `M10-08` after this brief's wording is settled.

## Open points
- WAITS FOR THE SPIKE (M10-00 S-D, S-J): the signal that tells an expired token from another refusal. If none exists, the fallback wording is the owner's.
- The exact chip and popup words (placeholder "Token expired").
- How the app knows that a profile holds a token.

## Definition of done
Tests written first and passing, no unrelated changes, mockup reviewed by the owner before the UI step, spec row and scenarios updated.
