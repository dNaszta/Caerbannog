# M10-01: Access view mockup

Milestone: M10    Depends on: owner answers AQ1, AQ2, AQ6, AQ8 (all decided 2026-11-25); AQ5 wording stays placeholder    Size: S

## Goal
`docs/ux/Access.dc.html` exists in the Material 3 style of the other mockups, shows the four tabs with every state, and the owner has reviewed it.

## Why
Screens come from `docs/ux/`. No UI step of M10 starts without a reviewed mockup (as for M8 and M9). Spec "Access view (M10)", "Screen".

## Scope
In: the mockup for Who am I, Permissions (table, topic permissions, match preview form and verdict), Sign-in (OAuth 2 detected, not detected, could not be told, token user with and without resolved permissions), Clients (users with counts, a queue or stream with consumers and publishers, filter, notice for a `management` user, "settling" row). States per tab: loading, empty, error, locked with the reason, stale after a lost connection, "Not encrypted" banner. The honesty sentence for part 3 ("what RabbitMQ resolved, not the identity provider's setup") placed where it cannot be missed. The redaction state ("hidden" with a count). No control that changes anything (no edit, delete, close connection, no queue message action). Narrow window and font scale 1.5 states (the layout promises of the spec, M2-21).
Out: any code; wording the owner has not given (use clearly marked placeholder text and list the placeholders).

## Acceptance
- The file opens like the other `.dc.html` mockups; every state above is reachable.
- A list of placeholders and of questions the mockup raised is at the top of the file or in the brief's Status.
- Owner review recorded in the Status line of this brief and in `docs/tasks/README.md`.

## Design notes
Reuse the components of `Topology.dc.html` (virtual host switch, read-only banner), `Clients.dc.html` (publishers and consumers tables, settling) and `Main.dc.html` (drawer, connection chip). The drawer gets an item only if AQ1 says so.

## UX
It is the UX task.

## MCP
None (design).

## Open points
AQ5 (verdict wording) only; AQ1, AQ2, AQ3, AQ6, AQ8 are decided (spec "Access view (M10)"). The mockup must no longer label them as assumptions after the review fixes.

## Definition of done
Mockup committed, reviewed by the owner, spec "Screens and UX" row for Access updated if the review changes it.


## Status
Mockup drafted 2026-11-25 on branch `M10-01-access-mockup`, round 2 and round 3 done (round 3 pending the architect's check); awaiting owner review (NOT reviewed).

Decided (owner): AQ1, AQ2, AQ3, AQ4, AQ6, AQ7, AQ8, AQ9, AQ10 (AQ2, AQ3, AQ6, AQ8 recorded in the spec by commit d2df4d2e). Still a placeholder: AQ5, the owner's words for the preview verdicts and the prediction note.

Placeholders (full list at the top of `docs/ux/Access.dc.html`): P1 preview verdicts, label, Check button and prediction note (AQ5); P2 the honesty sentence; P3 the other Sign-in texts; P4 refused / token-expired text; P5 redaction line and "hidden"; P6 indirect publisher wording and "publishers could not be told"; P7 drawer icon and generic empty, error, locked and loading texts; P8 the 3.x notice ("needs a 4.x broker"), locked reasons, declaration label.

Round 2 changes: Active column on the queue consumers table; 3.x drawn as item absent plus the return to Connections with a one-line notice when the connection becomes 3.x (scenario 22 is aligned, branch M10-spec-aq-alignment); Would not match and topic verdict frames now agree with their form fields; the stale Permissions frame draws the preview disabled; expanded connection details row (product, version, platform, connection_name only); narrow 800 dp frames for Who am I and Permissions; token-expired frame titled as replacing the data on all four tabs; user chooser frame with filter box; Sign-in line removed from Who am I; "token" pill removed.

Architect decisions applied: Q1 Who am I is whoami plus virtual hosts only; Q2 user chooser with a filter box, no paging; Q3 Configure stays in the preview; Q5 no "Stop reading"; Q7 M2-06 settling wording reused; Q8 no token pill (S-D unmeasured); Q9 font scale 1.5 is approximated in the mockup, a known limit.

Q4 decided (owner, 2026-11-25): the connection chip says "Token expired" when the token is refused. Drawn in `Access.dc.html` (red chip and popup row, marked "decided (owner)") in the refused frames on Who am I and Sign-in and in a new "stale after a refusal" frame. `Main.dc.html` is not edited here; the chip and popup are drawn only as a preview of the Main change, which the architect briefs separately as M10-10. Open for M10-10 (owner, architect): should a lost connection also change the chip? Spec D2 says Failed "Connection lost"; the ordinary connection-lost frames keep the Connected chip, as Topology and Streams draw it today.

Not checked: opened only in headless Chrome, the top of the page seen as a screenshot and the later frames (including the new ones) not looked at by eye; not opened in the design canvas (`support.js` is not in the repo); `canvas.json` has no Access board and none was added.

Round 3 changes: narrow Permissions frame no longer stretches (stacked fields do not flex), with a "table scrolls sideways" hint and visible scrollbar styling; the expired popup no longer covers the banner; the 3.x frames show the 3.x profile (legacy-3x) in the chip and the notice is one line ("The Access view needs a 4.x broker."); the Q4 heading is retitled; chevrons in the by-user table are centred with the name. Looked at in headless Chrome after the change: narrow Permissions, token expired (chip and popup), 3.x switched, Clients by user. Not looked at: the other frames, the font scale 1.5 frame, the new scroll bar on a real (non-overlay) scrollbar system.

Round 4 (M10-01b, 2026-11-25, branch `M10-01b-access-signin-redraw`): the Sign-in tab is redrawn after the spike M10-00 (the OAuth 2 backend cannot be detected from the management API; see `M10-05`, `M10-10` and feature 23). The frames "OAuth 2, no resolved permissions", "OAuth 2, resolved permissions", "not detected" and "could not be told" (outcome A and B) are removed. New frames, each with the honesty sentence first: token user (whoami `is_internal_user` false, user, tag and virtual hosts labelled "Resolved by RabbitMQ from the token", the notice that permissions come from the token and cannot be listed, plugin line); token user who may not read the node list; internal user with the plugin loaded ("this view cannot tell whether the broker uses it"); internal user without the plugin (no claim about "only its own users"); internal user who may not read the node list; a live connection closed with "credential expired" (code 320) with chip and popup row "Token expired" and the last data dimmed; a fresh connect refused with 401, "Sign-in refused" with the hint "the token may have expired or be wrong"; loading; error; stale; Not encrypted. The titles and captions of the Who am I token frame and the Permissions token frame no longer say "OAuth 2" or "outcome B". No token, scope or secret is drawn.

New placeholders (every text on the new frames carries a "placeholder Pn" pill; list at the top of `Access.dc.html`): P9 token-user texts and the label "Resolved by RabbitMQ from the token"; P10 the OAuth 2 plugin lines (loaded, cannot tell whether used, not in the plugin list, could not be told); P11 the internal-user statement; P12 the banner and popup sentence for the live "credential expired" closure; P13 "Sign-in refused" with its hint. P3 and P4 are replaced by P9 to P13. The generic loading and error texts on the Sign-in frames stay under P7, the honesty sentence under P2.

P13 is the architect's recommendation, not an owner decision (marked "recommendation, owner to decide" on the frame). Alternatives for the owner: (b) "Token expired" whenever the profile holds a token (overclaims: a wrong or unsigned token reads the same); (c) a per-profile switch "this password is a token", so "Token expired" shows only where the user said so. "Token expired" for the certain case (live connection, code 320) stays the owner's decided chip wording (Q4).

Owner review still pending: NOT reviewed.

Round 4 checked: each of the 11 new Sign-in frames was rendered in headless Chrome and looked at as a screenshot. Not checked: the design canvas (`support.js` not in the repo; frames rendered with that script removed), the narrow and font scale 1.5 variants of the new Sign-in states (not drawn), the other tabs' frames (only the two titles and one caption changed, not re-rendered by eye).

Round 4, architect review fixes (same branch, M10-01b): a user who may not read the node list shows the tag `management` on both Sign-in frames (a user without any tag gets 401 on whoami in S-A, so it is never a token user; the missing-tag case belongs to the locked states of the other tabs); the Permissions token-user frame now says the broker has no patterns for a token user (the preview sentence matches the banner, texts under P9); the honesty sentence is first on the token-expired and Sign-in refused frames; the internal-user line is only "This connection signs in as a user stored on the broker." (P11; "whoami" is left in captions); loading text "Reading the user and the plugin list" (P7); caption on the refused frame that a password profile shows only "Sign-in refused" without the token hint (scenario 13); the round 2 note on Q8 no longer says S-D is unmeasured. Correction of my first round 4 note: the placeholder list P9 to P13 was NOT in the top card of `Access.dc.html` in the first commit (the insertion had silently failed); it is there now.

Looked at after the fixes (headless Chrome): the top of the page (status, index, placeholder cards, round 2 card), the Who am I token frame, the Permissions token frame, and the Sign-in frames token-nonodes, internal-nonodes, expired, refused. Not re-looked: the other Sign-in frames (internal-loaded, loading, as only a caption or the loading words changed) and the design canvas.

Final follow-ups (M10-01b): the P13 note says "round 4"; the Permissions token-user texts carry "placeholder P9"; the Prediction line is dropped from that frame (no patterns above it). The honesty sentence was already directly under the tabs, before the refusal banner, on the token-expired and Sign-in refused frames (checked in the file order and in the render; the earlier report was right). The Sign-in redraw is awaiting the owner: NOT reviewed. Looked at: the Permissions token frame after the change.
