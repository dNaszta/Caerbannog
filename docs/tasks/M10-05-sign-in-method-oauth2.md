# M10-05: Sign-in method tab (OAuth 2 and Keycloak awareness)

Milestone: M10    Depends on: M10-02, M10-00 (S-B, S-C, S-D, S-J, S-L), owner answers AQ3, AQ4    Size: M

## Goal
The Sign-in tab says whether the broker uses the OAuth 2 backend (yes, no, could not be told), says plainly that the view shows what RabbitMQ resolved and not the identity provider's setup, shows what RabbitMQ resolved for a token user where the management API exposes it, and where it does not says that the permissions come from the token and cannot be listed here.

## Why
Spec "Access view (M10)", goal 3 and the honesty rule; the owner's Keycloak users. The management API does not show the Keycloak client configuration; the view must not claim it does.

## Scope
In:
1. **Detection** through the call S-C found, with its tag requirement; three results: `InUse`, `NotDetected`, `CannotTell(reason)`. `NotDetected` never turns into the claim "internal users only" (the management UI's OAuth setting and the broker's backend are different things).
2. **Outcome A or B** (decided by S-B and S-J, recorded in the results file at the top). B (baseline, likely): the tab says tokens are in use, permissions come from the token and are not stored on the broker, this view cannot list them; the Permissions tab shows no pattern table for a token user. A (extension, only if the spike shows an endpoint): the resolved tags and permissions are shown labelled "resolved by RabbitMQ from the token", through the same row model as `M10-03`.
3. **Token states**: a refused token (expired, bad signature, missing audience) maps to a reason from S-L and S-M and never echoes the body or the token; "the token may have expired" is the first-class hint.
4. The fixed honesty text, always shown when `InUse`.
5. Security: the token (the profile's password) and any part of it appear nowhere (screen, copy, log, error); the app makes no request to the issuer (a test counts the hosts requested).
6. **Only if AQ3 is yes:** local reading of the `scope` claim of the user's own token in memory, shown labelled "from my token, not confirmed by the broker", never the token or other claims, never stored. Without a yes this item is not built and the `@needs-decision` scenario stays unrun.
Out: Keycloak calls, token refresh, any change.

## Acceptance
- BDD (`23`, tag `@m10-05`): "A broker with the OAuth 2 backend says so" (@smoke), "The view says what it can and cannot show", "A broker without the OAuth 2 backend does not claim the opposite", "The sign-in method cannot be read for this user", "The token never appears", "The app makes no request to the identity provider", and, after the spike, "For a token user, what RabbitMQ resolved is shown and labelled" or "When RabbitMQ does not expose the resolved permissions, the view says so" (one of the two stays, the other is deleted by the architect), "A token the broker refuses is reported without echoing it" (`@needs-spike`), "Scopes read locally from the user's own token are labelled as not confirmed" (`@needs-decision`, AQ3).
- Unit: detection mapping on the fixtures of S-C, token-refusal mapping on S-L, a property test that the held JWT never appears in any state's text.

## Design notes
`core/access`, `data-rabbit`, `ui/access`. The held secret reaches the guard only through the connection holder (no new place keeps it). Reuse `SecretGuard` from `M10-02`.

## UX
Sign-in tab of `Access.dc.html`: detected, not detected, could not be told, token user B and A, refused token.

## MCP
The sign-in method is part of `caerbannog_get_access_summary` (`M10-08`): `{method, note}` with the same honesty note. No scopes in MCP in any case.

## Open points
AQ3, AQ4; the outcome of S-B. The wording is the owner's.

## Definition of done
Tests first and passing; a live check of detection and "no token in any value read" in the gate (`M10-09`); README, scenarios and spec updated to the outcome.
