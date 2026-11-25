# M10-07: Clients tab: stream publishers and consumers and MQTT clients by user

Milestone: M10    Depends on: M10-06    Size: S

## Goal
On the Clients tab a chosen stream shows its publishers and consumers with connection and user (from the existing endpoints), and MQTT clients appear under their users, all through the same guard and the same grouping as the queue lists.

## Why
Spec "Access view (M10)", goal 4; "Management API facts" (publisher and consumer rows, `connection_details.user`, settling). No new endpoint: reuse `M2-06` and `M6` data.

## Scope
In: map the existing stream publisher and consumer rows and the MQTT client list (`caerbannog_list_mqtt_clients` source, M6) into the Clients tab model; the settling row (empty `connection_details` is shown as settling, not as an error); the missing-plugin state (404 on the top-level path is the signal, per "Management API facts"; queue lists still shown); super stream partitions each as their own stream (one partition at a time, no merge); the same redaction, the same visibility notice.
Out: a new data call if the existing ones suffice (a token user's `connection_details.user` is checked in S-F), history, closing connections.

## Acceptance
- BDD (`24`, tag `@m10-07`): "Stream publishers and consumers show the user" (@smoke), "A publisher whose connection is not yet known is shown as settling", "MQTT clients are listed by user", "The stream plugin is missing".
- Unit: mapping tests on the M2-02 fixtures and the S-F fixtures; guard applied to the stream rows (a hostile `connection_details.name`).

## Design notes
Reuse `StreamClientsViewModel` data where it exists; do not poll twice (one refresh driver per screen). No second copy of the settling rule: call the existing function.

## UX
Clients tab, stream and MQTT rows of `Access.dc.html`.

## MCP
Covered by `caerbannog_list_clients` (`M10-08`, `stream` input).

## Open points
Whether the token user shows as the claim-derived name in `connection_details.user` (S-F).

## Definition of done
Tests first and passing; README updated.
