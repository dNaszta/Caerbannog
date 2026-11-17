# Write-permission gate on the older MQTT entry points, and the fold check of the AMQP Publish dialog

Milestone: M9 (follow-up)    Depends on: M9-10b    Size: S

Proposed 2026-11-17 by the architect at step 5 of `M9-10b`. Not started, not scheduled. The owner may want it before a release (recommendation: yes, small).

## Goal
Every place that opens the Publish MQTT dialog respects the profile's declared write permission the way "Send a test message" already does, and the AMQP Publish dialog is checked for the same "actions below the fold" defect that `M9-10b` fixed in the Publish MQTT dialog.

## Why
CLAUDE.md safety rule: topology changes and publishing respect the profile's declared permissions. `M9-10b` step 1 found that the older MQTT entry points (stream screen, stream pipeline graph, topology screen) are gated only by the MQTT port (`offersMqttPublishing` in `StreamWiring.kt`), not by the write permission; the text exists in `core.publish.noWritePermission(vhost)` since `M9-10b`. The AMQP `PublishDialog` was not looked at when the MQTT dialog got a pinned footer (senior developer note, `M9-10b` step 3 round 2).

## Scope
In:
- The three MQTT entry points show their "Publish MQTT message" control disabled with `noWritePermission(vhost)` as the reason when the finished permission check says publishing is not enabled; an unfinished check counts as no permission (same expression as `canWrite` in `Main.kt` and `MigrationsInputs.publishingEnabled`). One pure rule in `core` shared with `MqttTestMessage.offer` (the MQTT-port, then permission order), so the surfaces cannot disagree.
- Check the AMQP `PublishDialog` at 1280x800 and 1000x600 with the largest realistic content (headers, banner). If Publish or Cancel is outside the window, give it the same fix as `PublishMqttDialog` (pinned footer, visible scrollbar on overflow). If not, record "checked, fine" in the Status and add a layout test that pins it.
Out: any change of wording (the write text is reused); changing the topology control level logic; any new request kind.

## Acceptance
- BDD (to write by the architect or test developer first, tag `@m9-10e`, `@fake-broker`): "Publishing to MQTT is disabled without write permission on the stream screen", the same for the topology screen and the pipeline graph; "The reason names the virtual host"; "An unfinished permission check disables it". File: the feature that holds the existing MQTT publish scenarios (find it with `grep -rl "Publish MQTT" tests/bdd/features`), style as there.
- Unit test of the shared rule; the layout test for the AMQP dialog (bounds of Publish and Cancel inside the window at both sizes).

## Design notes
`core` gets the rule; `desktopApp` wiring (`StreamWiring.kt`, `Main.kt`) passes the permission; `ui` draws the disabled state with a `testTag` per entry point. No new dependency. Existing tags stay.

## UX
No new screen. Disabled control plus the reason text in the style of the AMQP publish button. AMQP dialog: only if the check fails.

## MCP
None (UI-only): the MCP server has no tool that publishes; this task adds none.

## Open points
- Whether the old entry points hide or disable the control without permission: recommendation, disabled with the reason (as the AMQP dialog and the test message do); wording is already decided, so no owner question unless the owner prefers hiding.

## Definition of done
Tests written first and passing, no unrelated changes, spec and scenarios updated if behaviour changed.
