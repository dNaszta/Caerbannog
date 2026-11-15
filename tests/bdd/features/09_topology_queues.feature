@topology @queues
Feature: Topology and quorum queues
  As an engineer
  I want to see the queues, exchanges and bindings of a broker, and to change them with a plan
  So that I can understand how messages are routed without opening the Management UI

  The app shows topology and statistics. It never consumes, acknowledges, deletes or purges the messages
  of a queue, because that would remove messages and change the data flow. The one way to see them (M7-08)
  is the requeue peek: every message is returned to the queue and shown as not acknowledged.

  Milestones: the scenarios tagged @M2 are the read-only topology screen (task M2-08): the queues,
  exchanges and bindings of the connection's virtual host, with statistics.

  The Topology screen stays read-only (owner decision Q15, 2026-11-15). Topology is changed with a plan in
  the Topology planner (M9, features 19 to 21), saved as a migration and applied from the Migrations screen
  (feature 10), so a migration is the one way to change a broker. Six scenarios tagged @M5 used to describe
  edit controls on this screen (declare a queue, edit a policy, add a binding, delete with a typed name,
  the locked actions, add to a migration file). They were never built and are replaced by the one @M9
  scenario near the end of the topology scenarios. Where each old idea lives now:
  declare a queue and bind: 19 "Add a queue, an exchange and a binding with the wizard";
  edit a policy: 21 "A policy is built from a form";
  locked without configure permission or at Read-only: 19 "A Read-only connection can plan, check and save,
  and is told that applying is locked" and "Without configure permission the planner says that applying is
  locked", and 10 "Apply is disabled without configure permission";
  delete: not in the first version of the planner (Q10); a delete action in a migration file needs the
  level Full control (10, "Deleting something that is already gone is a no-op");
  add a queue to a migration file from this screen: left for later as "Add to plan" (docs/spec.md,
  "Topology planner (M9)", "Left for later"), with its scenario written when that task exists.

  Background:
    Given I am connected to "uat-eu"

  @M2 @smoke @fake-broker
  Scenario: List quorum queues with statistics
    Given these quorum queues
      | name          | ready | unacked | consumers | leader        | members online |
      | orders        | 1240  | 12      | 3         | rabbit@node-1 | 3 of 3         |
      | notifications | 86    | 4       | 1         | rabbit@node-3 | 2 of 3         |
    When I open the topology screen on the "Queues" tab
    Then each queue is shown with its ready and unacked counts, consumers, leader and members online

  @M2 @fake-broker
  Scenario: A queue with a member offline is flagged
    Given the quorum queue "notifications" has 2 of its 3 members online
    When I open the topology screen on the "Queues" tab
    Then "notifications" is marked as having a member offline

  @M2 @fake-broker
  Scenario: Queue details
    Given a quorum queue "orders" bound to the exchange "events" with routing key "order.created"
    When I select the queue "orders"
    Then I see its leader, members, ready and unacked counts, consumers, policy and bindings

  @M2 @fake-broker
  Scenario: See exchanges and bindings
    Given a quorum queue "orders" bound to the exchange "events" with routing key "order.created"
    When I open the topology screen on the "Exchanges" tab
    Then I see the exchanges of the virtual host
    When I open the "Bindings" tab
    Then I see each binding with its source, destination and routing key

  @M2 @fake-broker
  Scenario: Other queue types are not the focus
    Given a quorum queue "orders" and a classic queue "legacy"
    When I open the topology screen on the "Queues" tab with the "Quorum" filter
    Then only "orders" is listed

  # Covered by TopologyDetailAndSafetyUiTest (UI test, not run as a fake-broker scenario). Since M7-08 the one way to see
  # queue messages is the requeue peek; nothing may consume, acknowledge, delete or purge them.
  @M2 @M7 @negative @safety
  Scenario: Queue messages cannot be consumed, acknowledged, deleted or purged
    When I select the queue "orders"
    Then there is no control to consume, acknowledge, delete or purge its messages
    And the only control that shows its messages is the requeue peek, labelled "Not acknowledged (requeued)"

  # Owner decision Q15 (2026-11-15): replaces the six @M5 scenarios that described edit controls on this
  # screen. Tagged @M9, not @M2 or @M7, so RunTopologyAcceptanceTest does not select it; the planner's own
  # suite binds it when the planner screen exists (task M9-03).
  @M9 @m9-03 @negative @fake-broker
  Scenario Outline: Topology is changed with a plan, not on the Topology screen
    Given the user has configure permission on "/"
    And the connection "uat-eu" has the topology control level "Full control"
    And a quorum queue "orders"
    When I select the queue "orders" on the topology screen
    Then there is no control to <change>
    And <where>

    Examples:
      | change                       | where                                                                                          |
      | declare a queue              | a queue is declared by the Queue step of a plan                                                |
      | change the policy of a queue | a policy is declared by the Policy step of a plan                                              |
      | bind an exchange to a queue  | a binding is declared by the Binding step of a plan                                            |
      | delete a queue               | no step of the wizard deletes anything and a delete action in a migration file needs the level Full control |

  @M2 @fake-broker
  Scenario: Search queues by name
    Given quorum queues "orders", "invoices" and "legacy-orders"
    When I search the queue list for "orders"
    Then "orders" and "legacy-orders" are listed

  # --- Added for task M2-08 (read-only topology screen). Decisions T1 to T5 are in docs/tasks/M2-08-topology-read-only.md. ---

  # Decision T1 (owner, 2026-11-05): the screen may show every virtual host the user can see. It starts on the connection's
  # own virtual host and shows one virtual host at a time. The stream list in 03 stays on the connection's virtual host.
  @M2 @fake-broker
  Scenario: Another vhost's queues are not listed
    Given the connection's virtual host has 3 quorum queues
    And another virtual host has 4 quorum queues
    When I open the topology screen on the "Queues" tab
    Then I see queues 1 to 3 of 3
    And no queue of the other virtual host is listed

  @M2 @fake-broker
  Scenario: The topology screen starts on the connection's virtual host
    Given the connection's virtual host is "/"
    And the user can see the virtual hosts "/" and "billing"
    When I open the topology screen on the "Queues" tab
    Then the virtual host "/" is the chosen one

  @M2 @fake-broker
  Scenario: Another virtual host can be chosen
    Given the user can see the virtual hosts "/" and "billing"
    And the virtual host "billing" has the quorum queue "invoices"
    When I open the topology screen on the "Queues" tab
    And I choose the virtual host "billing"
    Then only "invoices" is listed
    And no queue of the virtual host "/" is shown

  @M2 @fake-broker
  Scenario: Only the virtual hosts the user may see are offered
    Given the broker has the virtual hosts "/", "billing" and "secret"
    And the user can see the virtual hosts "/" and "billing"
    When I open the topology screen on the "Queues" tab
    Then I can choose between "/" and "billing" and no other

  # The management API answers 404 for a virtual host that is missing and for one the user has no permission on.
  @M2 @negative @fake-broker
  Scenario: A virtual host the user cannot see says so
    Given the virtual host "billing" is offered but the broker answers that it is not available
    When I open the topology screen on the "Queues" tab
    And I choose the virtual host "billing"
    Then I see the message "Virtual host billing is not available to this user. It may have been deleted, or the user may have no permission on it."
    And no queue is shown
    And I can still choose another virtual host

  # The permission check reads the connection's own virtual host only, so nothing is claimed about the others.
  @M2 @fake-broker
  Scenario: Another virtual host says its permissions were not checked
    Given the connection's virtual host is "/"
    And the user can see the virtual hosts "/" and "billing"
    When I open the topology screen on the "Queues" tab
    And I choose the virtual host "billing"
    Then the topology screen says permissions were checked on virtual host "/" only and this virtual host is shown read-only

  # Streams have their own screen; a stream (or a partition of a super stream) is never listed as a queue here.
  @M2 @fake-broker
  Scenario: Streams are not listed as queues
    Given a quorum queue "orders", a classic queue "legacy" and a stream "audit-log"
    When I open the topology screen on the "Queues" tab with the "Other types" filter
    Then only "legacy" is listed

  @M2 @fake-broker
  Scenario: A vhost with no queues
    Given the connection's virtual host has no queues
    When I open the topology screen on the "Queues" tab
    Then I see a message that there are no queues yet

  @M2 @fake-broker
  Scenario: Long queue lists are paged
    Given the connection's virtual host has 12 quorum queues
    When I open the topology screen on the "Queues" tab with 5 queues per page
    Then I see queues 1 to 5 of 12
    And I can move to the next page

  # Decision T2 (recommended): the default exchange and the implicit binding of every queue to it are real on the broker
  # but noise on screen; they are hidden unless asked for.
  @M2 @fake-broker
  Scenario: The default exchange and its bindings are hidden until asked for
    Given the broker has the default exchange, the exchange "events" and a queue "orders" bound to "events"
    When I open the topology screen on the "Bindings" tab
    Then the bindings view shows only the binding from "events" to "orders"
    When I choose to show the default exchange
    Then the bindings view also shows the implicit binding from the default exchange to "orders"

  @M2 @fake-broker
  Scenario: A binding to an exchange is shown as such
    Given the exchange "events" is bound to the exchange "audit" with routing key "#"
    When I open the topology screen on the "Bindings" tab
    Then the binding from "events" to "audit" says its destination is an exchange

  # --- The bindings graph (decision T2, owner 2026-11-05; task M2-08d). The table of the Bindings tab (above) is its fallback. ---

  @M2 @fake-broker
  Scenario: The bindings graph shows an exchange with its queues
    Given the exchange "events" is bound to the queues "orders" and "audit" with routing keys "order.created" and "#"
    When I open the topology screen on the "Bindings" tab and choose the graph view
    Then the graph has the nodes "events", "orders" and "audit"
    And it has an edge from "events" to "orders" labelled "order.created"
    And it has an edge from "events" to "audit" labelled "#"

  @M2 @fake-broker
  Scenario: The bindings graph never draws the default exchange unless asked
    Given the broker has the default exchange and 1000 queues, each bound to it
    When I open the topology screen on the "Bindings" tab and choose the graph view
    Then the graph has no node for the default exchange
    And no queue is drawn only because of the default exchange

  @M2 @fake-broker
  Scenario: A big neighbourhood is not drawn partly
    Given the exchange "events" is bound to 100 queues
    When I open the topology screen on the "Bindings" tab and choose the graph view
    Then no partial graph is drawn
    And the screen says there are more bindings than can be drawn and asks me to choose an exchange or narrow the filter

  @M2 @fake-broker
  Scenario: The bindings can also be read as a table
    Given the exchange "events" is bound to the queue "orders" with routing key "order.created"
    When I open the topology screen on the "Bindings" tab and choose the graph view
    And I switch to the table view
    Then I see the binding with its source, destination and routing key

  @M2 @fake-broker
  Scenario: Graph nodes can be reached by the keyboard
    Given the exchange "events" is bound to the queue "orders" with routing key "order.created"
    When I open the topology screen on the "Bindings" tab and choose the graph view
    Then every node can be focused with the keyboard
    And a focused node is described with its kind, name and number of bindings

  @M2 @fake-broker
  Scenario: A super stream exchange is marked
    Given a super stream "orders" with 3 partitions
    When I open the topology screen on the "Exchanges" tab
    Then the exchange "orders" is marked as a super stream

  # Decision D3 (refresh only while shown) applies to this screen like to the stream list.
  @M2 @fake-broker
  Scenario: The topology screen refreshes by itself only while it is shown
    Given a quorum queue "orders" with 12 ready messages
    And I have the topology screen open
    And the refresh interval is 30 seconds
    When the queue gets 20 more ready messages
    And 30 seconds pass
    Then "orders" shows 32 ready messages
    When I open the stream list
    And 30 seconds pass
    Then the topology screen does not ask the broker again

  # Decision D2 (no automatic reconnect), same rule as the stream list in 13.
  @M2 @fake-broker
  Scenario: A lost connection keeps the last queues on screen
    Given the quorum queue "orders" is listed
    When the broker stops answering
    Then "orders" stays listed and the screen is marked stale
    And the app does not reconnect by itself

  @M2 @fake-broker
  Scenario: A user without the management tag
    Given the user of "uat-eu" has no management tag
    When I open the topology screen on the "Queues" tab
    Then I see the message "This user cannot read topology. Ask for the management tag."
    And no queue list is shown

  @M2 @fake-broker
  Scenario: Queue counts are labelled as approximate
    Given a quorum queue "orders" with 1240 ready messages
    When I open the topology screen on the "Queues" tab
    Then the screen says the counts are approximate

  # The topology gateway only reads topology and statistics. Peeking at messages (M7-08) is a separate, explicit action of the
  # user through the queue inspector, which always requeues; see the @M7 scenarios at the end of this file.
  @M2 @negative @safety @fake-broker
  Scenario: Opening the topology screen never asks the topology gateway for a queue's messages
    Given a quorum queue "orders"
    When I open the topology screen on every tab and select the queue "orders"
    Then every request the app made to the broker was a read of topology or statistics
    And none of them was a request for the messages of a queue

  # Decision T4 (recommended): in M2 the screen has no action that changes topology, so none is drawn,
  # not even a disabled one. With the planner (Q15) this stays true: a locked connection is told so by
  # the planner and by the Migrations screen, never by a disabled button here.
  @M2 @fake-broker
  Scenario: The M2 topology screen offers no action that changes topology
    Given the user has configure permission on "/"
    And a quorum queue "orders"
    When I select the queue "orders"
    Then there is no control to edit, delete or bind

  # Decision T5 (recommended): the self-constraint level is a second reason for a read-only screen.
  @M2 @fake-broker
  Scenario: The topology screen says the connection is set to read-only
    Given the user has configure permission on "/"
    And the connection "uat-eu" is set to the "Read-only" topology level
    When I open the topology screen on the "Queues" tab
    Then the topology screen explains that the connection is set to read-only


  # M7-08: the requeue peek. Live scenarios are RealBrokerM7GateTest (@gate-M7); the labels are in TopologyDetailAndSafetyUiTest.
  @M7 @broker @gate-M7 @safety
  Scenario: Inspecting a queue leaves its messages in place
    Given a quorum queue "orders" with 5 messages that carry properties and headers
    When I inspect the queue "orders" three times
    Then each time I see the same 5 messages in the same order with their properties and headers
    And every inspected message is shown as "Not acknowledged (requeued)"
    And the queue still holds 5 messages
    And a consumer afterwards receives all 5 messages

  @M7 @broker @gate-M7 @safety
  Scenario: Inspecting more often than the delivery limit loses no message
    Given a quorum queue "limited" with a delivery limit of 2 and 2 messages
    When I inspect the queue "limited" 8 times
    Then no message is dropped
    And a consumer afterwards receives both messages

  @M7 @broker @gate-M7
  Scenario: Inspecting a queue that does not exist is refused
    When I inspect the queue "no-such-queue"
    Then the app says the queue was not found and shows no messages

  # The same peek against fakes of the broker: the real core view model, and a fake inspector that mirrors the contract of the real one.
  @M7 @fake-broker @safety
  Scenario: Inspect shows queue messages as not acknowledged and requeued
    Given a quorum queue "orders"
    And "orders" holds 3 messages
    When I inspect the queue "orders"
    Then I see 3 inspected messages, each marked "Not acknowledged (requeued)"
    And the screen says the messages were returned to the queue

  @M7 @fake-broker @safety
  Scenario: Every inspection is a requeue peek and leaves the queue as it was
    Given a quorum queue "orders"
    And "orders" holds 3 messages
    When I inspect the queue "orders"
    And I inspect the queue "orders"
    Then every inspector request used the requeue mode
    And the queue "orders" still holds 3 messages

  @M7 @fake-broker
  Scenario: The number of messages asked for is clamped to 1 through 50
    Given a quorum queue "orders"
    And "orders" holds 3 messages
    When I inspect the queue "orders" asking for 999 messages
    Then the broker was asked for 50 messages
    When I inspect the queue "orders" asking for 0 messages
    Then the broker was asked for 1 messages

  @M7 @fake-broker @negative
  Scenario: A user without read access to the queue is refused
    Given a quorum queue "secret"
    And the user has no read access to the queue "secret"
    When I inspect the queue "secret"
    Then the screen shows the inspection error "does not have read access"
    And I see no inspected messages

  @M7 @fake-broker @negative
  Scenario: Inspecting a queue that does not exist says so
    When I inspect the queue "ghost"
    Then the screen shows the inspection error "not found"
    And I see no inspected messages

  @M7 @fake-broker @negative @safety
  Scenario: Every request the app made was a read of topology or statistics, or a requeue peek
    Given a quorum queue "orders"
    And "orders" holds 2 messages
    When I open the topology screen on every tab and select the queue "orders"
    And I inspect the queue "orders"
    Then every request the app made to the broker was a read of topology or statistics, or a requeue peek
