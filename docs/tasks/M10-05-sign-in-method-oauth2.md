# M10-05: Sign-in method tab (OAuth 2 and Keycloak awareness)

Milestone: M10    Depends on: M10-02, M10-00 (S-B, S-C, S-D, S-J, S-L), owner answers AQ3 (no) and AQ4 (decided)    Size: M

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
6. **Not built (AQ3 answered no, 2026-11-25; revisit after S-B):** local reading of the `scope` claim of the user's own token in memory, shown labelled "from my token, not confirmed by the broker", never the token or other claims, never stored. The scenario "Scopes in the user's own token are not read or shown" asserts the negative.
Out: Keycloak calls, token refresh, any change.

## Acceptance
- BDD (`23`, tag `@m10-05`, rewritten after the spike): "A token user is told that the connection signs in with a token" (@smoke), "The view says what it can and cannot show", "An internal user on a broker with the OAuth 2 plugin loaded is told what the view cannot tell", "An internal user on a broker without the OAuth 2 plugin does not claim the opposite", "The plugin list cannot be read for this user", "For a token user, what RabbitMQ gives is shown and labelled", "The view says that the permissions of a token user cannot be listed", "The token never appears", "The app makes no request to the identity provider", "A token the broker refuses is reported without echoing it", "A live connection closed because its token expired says so", "Scopes in the user's own token are not read or shown" (AQ3).
- Unit: detection mapping on the fixtures of S-C, token-refusal mapping on S-L, a property test that the held JWT never appears in any state's text.

## Design notes
`core/access`, `data-rabbit`, `ui/access`. The held secret reaches the guard only through the connection holder (no new place keeps it). Reuse `SecretGuard` from `M10-02`.

## UX
Sign-in tab of `Access.dc.html`: detected, not detected, could not be told, token user B and A, refused token.

## MCP
The sign-in method is part of `caerbannog_get_access_summary` (`M10-08`): `{method, note}` with the same honesty note. No scopes in MCP in any case.

## Open points
AQ3 and AQ4 are decided. Changed by M10-00 (2026-11-25): outcome B is confirmed (no resolved permissions for token users). The OAuth 2 backend itself CANNOT be detected from the API: `/api/auth` is 404, `/api/nodes` (administrator and monitoring only) shows the plugin loaded (`applications`, `enabled_plugins`) but not `auth_backends`. So item "detect the backend" becomes: (1) a token user is recognised by `whoami.is_internal_user = false` and told "this connection signs in with a token"; (2) for an administrator or monitoring user the Sign-in tab may add "the OAuth 2 plugin is loaded" with the sentence that the view cannot tell whether it is used; (3) never "this broker uses tokens" or "only its own users". Scenarios in `23` are rewritten accordingly. Expiry: see `M10-10`. The mockup's Sign-in states (OAuth 2 detected, not detected, could not be told) must be redrawn to these three cases (token user, internal user with plugin loaded or not loaded, not readable); that is a mockup follow-up. The wording is the owner's. Facts: `docs/tasks/M10-00-spike-results.md`, spec "Measured facts of M10-00".

## Definition of done
Tests first and passing; a live check of detection and "no token in any value read" in the gate (`M10-09`); README, scenarios and spec updated to the outcome.
