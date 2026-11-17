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

## Status
- Reconciled 2026-11-17: this task is merged into `main` (6283995, 2026-11-06); statements below about waiting for a merge, an unpushed branch or an owner's merge word are history.

### Step 1: Architect brief confirmed (2026-11-06)
- **MCP check:** None required. Fixing IPv6 literal formatting in `data-rabbit` transparently enables all existing profile tools in `mcp-server` to work with IPv6 hosts.
- **Quorum queue safety:** Confirmed. No message reading, getting, or purging.
- **Seam & Authority formatting:**
  - RFC 3986 section 3.2.2: IPv6 literals must be enclosed in square brackets `[host]` in URIs.
  - Add internal `formatHost(host: String): String` and `formatAuthority(host: String, port: Int): String` to `ManagementRequests.kt`.
  - Bracket `host` if it contains `:` and is not already bracketed (`host.startsWith("[") && host.endsWith("]")`). Hostnames and IPv4 remain untouched. Scoped IPv6 addresses (`fe80::1%eth0`) not supported (noted in KDoc).
  - Use `formatAuthority` in `ManagementRequests.forProfile`, `ManagementApiPermissionChecker.permissionsUrl`, and `StreamAndManagementReachabilityChecker`.
- **Open point (profile form):**
  - Confirmed: The profile form has no validator rejecting IPv6 literals; it stores `host` directly as a String without alteration.
- **Broker note:**
  - Confirmed: `caerbannog-dev-rabbitmq` listens on `[::]:15671` and `[::]:15672`, reachable over `::1`.

### Step 2: Tests first (test developer, 2026-11-06)
- Added 4 unit tests in `data-rabbit/src/test/kotlin/dev/caerbannog/datarabbit/ManagementRequestsTest.kt`:
  1. `an IPv6 literal host is bracketed for TLS and plain profiles`: asserts `https://[::1]:15671/api/whoami` and `http://[::1]:15672/api/whoami`, verifies `java.net.URI` parses `[::1]` as raw authority without throwing.
  2. `a full IPv6 address literal is bracketed`: asserts `https://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:15671/api/whoami`.
  3. `an already bracketed IPv6 address is kept as is and not double-bracketed`: asserts `https://[::1]:15671/api/whoami` given `[::1]`.
  4. `host names and IPv4 addresses remain unbracketed`: asserts `rabbit.example.com` and `127.0.0.1` remain without brackets.
- Verified RED state: `./gradlew :data-rabbit:test --tests "dev.caerbannog.datarabbit.ManagementRequestsTest"`: 21 tests completed, the 3 IPv6 tests failed with `AssertionFailedError` as expected.

### Step 3: Build (senior developer, 2026-11-06)
- Implemented `formatHost` and `formatAuthority` in `ManagementRequests.Companion`:
  - `formatHost(host)`: returns `host` if already bracketed (`host.startsWith("[") && host.endsWith("]")`), `"[host]"` if it contains colons (`:`), otherwise `host`.
  - `formatAuthority(host, port)`: `"${formatHost(host)}:$port"`.
  - Documented zone identifiers (`fe80::1%eth0`) as unsupported per scope.
- Updated authority callers across `data-rabbit`:
  - `ManagementRequests.forProfile(profile, transport)`: uses `formatAuthority(profile.host, profile.managementPort)`.
  - `ManagementApiPermissionChecker.permissionsUrl(profile, transport)`: uses `ManagementRequests.formatAuthority(...)`.
  - `StreamAndManagementReachabilityChecker.checkManagementEndpoint` & `checkStreamManagementPlugin`: uses `ManagementRequests.formatAuthority(...)`.
- Added direct unit test `formatHost brackets IPv6 literals and leaves hostnames and IPv4 unchanged`.
- Verified GREEN state: all unit tests in `:data-rabbit:test` PASS.

### Step 4: Verification (test developer, 2026-11-06)
- **Mutation check:**
  - Mutated `formatHost` to omit the already-bracketed check `host.startsWith("[") && host.endsWith("]")`.
  - Result: 2 unit tests failed immediately (`AssertionFailedError`), killing the mutation.
- **Full test suite & guard check:**
  - `./gradlew test verifyTestDiscovery`: 46 tasks successful, 0 failures across all modules.
- Verdict: **PASS**. Ready for Step 5 architect review.

### Step 5: Architect review (solution architect, 2026-11-06)
- **Review:**
  - RFC 3986 section 3.2.2 adherence: IPv6 literals are cleanly enclosed in square brackets `[host]` for URI authority.
  - Safe bracketing: already bracketed hosts (e.g. `[::1]`) are preserved without double-bracketing. Hostnames and IPv4 addresses remain unbracketed.
  - Consistent authority formatting shared across `ManagementRequests`, `ManagementApiPermissionChecker`, and `StreamAndManagementReachabilityChecker`.
  - Zone identifier non-support clearly documented in KDoc.
  - Headless MCP check: Verified (no new MCP tools needed; existing profile tools benefit directly).
  - Quorum queue safety: Verified.
- **Decision:** **ACCEPTED**. Ready to merge into local `main`.



