# Connection chip says "Token expired"

Milestone: M10    Depends on: M10-00 (DONE 2026-11-25, S-L measured), M10-02 (Access gateway and token-refusal state), M10-01 (mockup reviewed)    Size: M

## Goal
When the broker refuses the sign-in of a profile whose password is a token (OAuth 2, AQ4), the app bar chip and its popup row say "Token expired" instead of a generic failure, and every screen that shows the connection behaves as for a lost connection with that reason.

## Why
Owner decision on Q4, 2026-11-25: the chip itself says so. Spec "Connection lifecycle (M2 decisions D1, D2, D6, D7)" and "Access view (M10)" (token expired is a first-class state, AQ4). A token has a lifetime set by the identity provider, so the owner's users meet this often.

## Scope
In:
- A new reason in the connection state, `Failed(TokenExpired)` (or a reason value next to "Credentials were refused" and "Connection lost"), shown as "Token expired" on the chip, the chip popup row and the connection panel. The chip text is the owner's wording: "Token expired" is a PLACEHOLDER until the owner confirms the exact words; the popup explanation sentence is also a placeholder.
- Telling it from other refusals (MEASURED by M10-00, S-L, RabbitMQ 4.4.1):
  - A **live connection** whose token expires is closed by the broker with `CONNECTION_FORCED - credential expired` (reply code 320, about 10 s after the token's end in the test). That reason is certain: the chip says "Token expired". Over the management API a refresh of an expired token is a 401; the app already holds the profile's token, so a 401 on a data call of a connection that was Connected is treated the same way only when the profile holds a token and the earlier calls succeeded.
  - A **fresh connect** with an expired, badly signed, unknown-key or garbage token gets the generic 401 `{"error":"not_authorized","reason":"Not_Authorized"}` (AMQP `ACCESS_REFUSED`, stream code 8, MQTT 134): the same as a wrong password. The app CANNOT tell them apart, so the chip must not say "Token expired" there. The wording is approved by the owner (2026-11-25): "Sign-in refused" with the hint "the token may have expired or be wrong". This is also the text of the existing refusal banner of `Access.dc.html`.
  - How the app knows a profile holds a token: `whoami.is_internal_user = false` once connected; before the first successful connect it does not know, which is another reason for the generic fallback at connect.
- Behaviour while in that state: the same as Failed("Connection lost") (D2): the clients are closed, the last rows stay and are marked stale, Retry is offered; no automatic reconnect and no automatic token refresh (the app never talks to an identity provider). The stream refresh loop and the Access tabs stop refreshing (D3) and show the "token expired" refusal banner of `Access.dc.html` in place of the data. The text points at the connection settings, where the user pastes a new token as the password.
- Mockup: `docs/ux/Main.dc.html` gets the chip state and the popup row (and the connection panel line); `Access.dc.html` keeps its banner and may say the chip says the same. Mockup first, owner review, then code.
Out: refreshing or fetching a token, reading the token's expiry time (AQ3: no local decoding in v1), any identity provider call, any new control that changes the broker.

## Acceptance
- BDD, scenarios in `tests/bdd/features/13_connection_lifecycle.feature` (tags `@M10 @fake-broker @m10-10`), by name:
  - "An established connection closed because its token expired shows Token expired"
  - "A new connect with a refused token cannot be told from a refused password" (the fallback wording "Sign-in refused" with the hint "the token may have expired or be wrong" is approved by the owner, 2026-11-25; no `@needs-decision` left)
  - "A refused password does not say Token expired"
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
- DECIDED (owner, 2026-11-25, "in wording use recommended"): a fresh connect that the broker refuses with 401 shows "Sign-in refused" with the hint "the token may have expired or be wrong" on the chip, the popup and the banner (option (a)); options (b) and (c) were not taken. "Token expired" is shown only for a live connection closed with `credential expired` (code 320).
- Not measured: a stream or MQTT connection outliving its token, token refresh (`update-secret`), the clock skew the broker allows.
- The popup explanation sentences are approved as drawn in `Access.dc.html` (P12, P13). Open for the owner: whether a lost connection should also change the chip.

## Definition of done
Tests written first and passing, no unrelated changes, mockup reviewed by the owner before the UI step, spec row and scenarios updated.
