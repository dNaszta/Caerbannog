@M2 @M5 @topology @queues
Feature: Topology and quorum queues
  As an engineer
  I want to see and, where allowed, change the queues, exchanges and bindings of a broker
  So that I can understand how messages are routed without opening the Management UI

  The app shows topology and statistics only. It never reads, gets or purges the messages
  of a queue, because reading would remove messages and change the data flow.

  Background:
    Given I am connected to "uat-eu"

  @smoke @fake-broker
  Scenario: List quorum queues with statistics
    Given these quorum queues
      | name          | ready | unacked | consumers | leader        | members online |
      | orders        | 1240  | 12      | 3         | rabbit@node-1 | 3 of 3         |
      | notifications | 86    | 4       | 1         | rabbit@node-3 | 2 of 3         |
    When I open the topology screen on the "Queues" tab
    Then each queue is shown with its ready and unacked counts, consumers, leader and members online

  @fake-broker
  Scenario: A queue with a member offline is flagged
    Given the quorum queue "notifications" has 2 of its 3 members online
    When I open the topology screen on the "Queues" tab
    Then "notifications" is marked as having a member offline

  @fake-broker
  Scenario: Queue details
    Given a quorum queue "orders" bound to the exchange "events" with routing key "order.created"
    When I select the queue "orders"
    Then I see its leader, members, ready and unacked counts, consumers, policy and bindings

  @fake-broker
  Scenario: See exchanges and bindings
    When I open the topology screen on the "Exchanges" tab
    Then I see the exchanges of the virtual host
    When I open the "Bindings" tab
    Then I see each binding with its source, destination and routing key

  @fake-broker
  Scenario: Other queue types are not the focus
    Given a quorum queue "orders" and a classic queue "legacy"
    When I open the topology screen on the "Queues" tab with the "Quorum" filter
    Then only "orders" is listed

  @negative @safety
  Scenario: Queue messages cannot be read
    When I select the queue "orders"
    Then there is no control to browse, get, consume or purge its messages

  @fake-broker
  Scenario: Declare a quorum queue when allowed
    Given the user has configure permission on "/"
    When I declare a quorum queue "invoices"
    Then the broker is asked to create a quorum queue "invoices"
    And "invoices" appears in the list

  @fake-broker
  Scenario: Edit a queue's policy when allowed
    Given the user has configure permission on "/"
    And a quorum queue "orders" with the policy "quorum-default"
    When I change the policy of "orders" to "quorum-strict"
    Then the broker applies "quorum-strict" to "orders"

  @fake-broker
  Scenario: Add a binding when allowed
    Given the user has configure permission on "/"
    When I bind the exchange "events" to the queue "orders" with routing key "order.created"
    Then the binding appears in the details of "orders"

  @fake-broker
  Scenario: Deleting a queue needs the name typed to confirm
    Given the user has configure permission on "/"
    When I choose to delete the queue "legacy-orders"
    Then I must type "legacy-orders" before the delete is enabled
    When I type "legacy-orders" and confirm
    Then the broker is asked to delete the queue "legacy-orders"

  @negative @fake-broker
  Scenario Outline: Everything that changes topology is locked without configure permission
    Given the user has no configure permission on "/"
    When I select the queue "orders"
    Then the "<action>" action is disabled
    And it explains that topology is locked because the user has no configure permission

    Examples:
      | action      |
      | Edit policy |
      | Delete queue |
      | Add binding |

  @fake-broker
  Scenario: A locked queue can still be added to a migration file
    Given the user has no configure permission on "/"
    When I choose "Add to migration" for the queue "orders"
    Then a migration file with the queue's declaration is prepared
    And I am told it can be applied on a connection that allows it

  @fake-broker
  Scenario: Search queues by name
    Given quorum queues "orders", "invoices" and "legacy-orders"
    When I search the queue list for "orders"
    Then "orders" and "legacy-orders" are listed
