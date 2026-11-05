# IPv6 literal hosts on the management path

Milestone: M2    Depends on: none    Size: S

## Goal
A connection profile whose host is an IPv6 literal (for example `::1`) connects on the management API, TLS on or off, as it already does on the stream path.

## Why
Found by the M2-14c measurement: `ManagementRequests` builds `https://::1:15671/api/whoami` without brackets and the URL parser throws `URLParserException`. Spec D9a allows any host.

## Scope
In: bracket an IPv6 literal when the management URL is built (both schemes); unit test of the URL builder (`::1`, a full address, a host name, an IPv4 address, an already bracketed input); one @broker check if the dev broker listens on `::1` cheaply, else skip and say so.
Out: how the host is validated or stored in the profile form (raise it as a question if the form rejects `::1`); zone ids (`fe80::1%eth0`), not supported, say so in the KDoc.

## Acceptance
- Unit: the URL builder table above. No Gherkin scenario needed (no user-visible rule change); add one only if the form needs a change.

## Design notes
`data-rabbit`, `ManagementRequests` only. One function that formats the authority; no duplicated string building.

## UX
None.

## Open points
Whether the profile form accepts an IPv6 literal today (not checked).

## Definition of done
Tests first and passing, no unrelated changes, README row updated.
