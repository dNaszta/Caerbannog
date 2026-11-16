@M9 @planner @check
Feature: Check a plan against the broker
  As an engineer
  I want to see what each step of my plan would meet on the broker before I apply it
  So that I am not surprised by something that already exists or by a step that is not allowed

  The check reads the broker and changes nothing. Per step it says what the broker has (will be created,
  already there, exists with different settings, needs something that is missing, could not be checked;
  for a delete step of an opened file: will be deleted, already gone)
  and, beside it, whether the existing dry run blocks the step (the level, the configure permission,
  another virtual host). The check is advice: Apply stays the authority, with its own read-back.

  PLANNED 2026-11-15, not built. The exact rule for "the same settings" of a queue and an exchange waits
  for the spike M9-00c (S-G); the scenarios below use differences that today's comparison already tells
  apart (type, flags, a declared argument with another value). Sub-tags: @m9-05 the check in core and
  data-rabbit, @m9-05b the argument rule measured by S-G (one live scenario), @m9-06 the check on the screen. The wording of the results is decided (Q14, 2026-11-15).

  Background:
    Given I am connected to "uat-eu" with the virtual host "/"
    And the user has configure permission on "/"
    And the connection "uat-eu" has the topology control level "Declare and edit"

  @m9-05 @smoke @fake-broker
  Scenario: The check says what each step would meet on the broker
    Given the broker has the direct exchange "events" and the quorum queue "orders"
    And a plan with the steps
      | Declare direct exchange events                 |
      | Declare quorum queue orders                    |
      | Declare quorum queue invoices                  |
      | Bind events to invoices with invoice.created   |
    When I check the plan against "uat-eu"
    Then the steps show these results
      | Declare direct exchange events               | Already there   |
      | Declare quorum queue orders                  | Already there   |
      | Declare quorum queue invoices                | Will be created |
      | Bind events to invoices with invoice.created | Will be created |
    And only read requests were sent to the broker

  @m9-05 @fake-broker
  Scenario: Something that exists with other settings is found before applying
    Given the broker has the classic queue "orders"
    And a plan that declares the quorum queue "orders"
    When I check the plan against "uat-eu"
    Then the step "Declare quorum queue orders" shows "Exists with different settings"
    And it says applying would stop there and nothing would be overwritten
    And "orders" on the broker is unchanged

  @m9-05 @fake-broker
  Scenario Outline: Each kind of step is compared with what the broker has
    Given the broker has <existing>
    And a plan with the step "<step>"
    When I check the plan against "uat-eu"
    Then the step "<step>" shows "<result>"

    Examples:
      | existing                                                              | step                                     | result                          |
      | the fanout exchange "events"                                          | Declare direct exchange events           | Exists with different settings  |
      | the direct exchange "events"                                          | Declare direct exchange events           | Already there                   |
      | the stream "telemetry"                                                | Declare stream telemetry                 | Already there                   |
      | the quorum queue "telemetry"                                          | Declare stream telemetry                 | Exists with different settings  |
      | the stream "orders"                                                   | Declare quorum queue orders              | Exists with different settings  |
      | the binding from "events" to "orders" with routing key "order.created" | Bind events to orders with order.created | Already there                   |
      | the binding from "events" to "orders" with routing key "order.paid"    | Bind events to orders with order.created | Will be created                 |
      | the policy "limits" on "^orders" with "max-length" 500                 | Declare policy limits                    | Exists with different settings  |

  @m9-05 @fake-broker
  Scenario: A policy that exists with the same settings is already there
    Given the broker has the policy "limits" on "^orders" with "max-length" 1000
    And a plan that declares the policy "limits" on "^orders" with "max-length" 1000
    When I check the plan against "uat-eu"
    Then the step "Declare policy limits" shows "Already there"

  @m9-05 @fake-broker
  Scenario: A binding to something the plan declares earlier is fine
    Given the broker has neither the exchange "events" nor the queue "orders"
    And a plan with the steps
      | Declare direct exchange events           |
      | Declare quorum queue orders              |
      | Bind events to orders with order.created |
    When I check the plan against "uat-eu"
    Then every step shows "Will be created"

  @m9-05 @negative @fake-broker
  Scenario: A binding to something that is neither on the broker nor in the plan
    Given the broker has the quorum queue "invoices" and no exchange "evnts"
    And a plan with the step "Bind evnts to invoices with invoice.created"
    When I check the plan against "uat-eu"
    Then the step shows "Needs something that is missing"
    And it says "The exchange evnts is not on the broker and is not declared earlier in this plan."

  @m9-05 @negative @fake-broker
  Scenario: A step blocked by the level is marked blocked and still compared
    Given the connection "uat-eu" has the topology control level "Read-only"
    And the broker has the quorum queue "orders"
    And a plan with the steps
      | Declare quorum queue orders   |
      | Declare quorum queue invoices |
    When I check the plan against "uat-eu"
    Then the steps show these results
      | Declare quorum queue orders   | Already there   |
      | Declare quorum queue invoices | Will be created |
    And both steps show "Blocked" with the reason "Topology control level is Read-only"

  @m9-05 @negative @fake-broker
  Scenario: Without configure permission every step is blocked and still compared
    Given the user has no configure permission on "/"
    And a plan that declares the quorum queue "invoices"
    When I check the plan against "uat-eu"
    Then the step shows "Will be created" and "Blocked" with the reason "User has no configure permission on this virtual host"

  @m9-05 @negative @fake-broker
  Scenario: A plan for another virtual host is blocked and nothing is read from that virtual host
    Given a plan for the virtual host "orders" that declares the quorum queue "invoices"
    When I check the plan against "uat-eu"
    Then the step shows "Blocked" with the reason "The action belongs to virtual host orders; this connection is for /"
    And no request for the virtual host "orders" was sent

  @m9-05 @negative @fake-broker
  Scenario: A delete in an opened file needs Full control
    Given the pending migration "0006" that deletes the queue "legacy-orders" is open in the planner
    And the broker has the quorum queue "legacy-orders"
    When I check the plan against "uat-eu"
    Then the step "Delete queue legacy-orders" shows "Will be deleted" and "Blocked" with the reason "Destructive actions require Full control level"

  @m9-05 @fake-broker
  Scenario: A delete of something that is already gone says so
    Given the pending migration "0006" that deletes the queue "legacy-orders" is open in the planner
    And the broker has no queue "legacy-orders"
    When I check the plan against "uat-eu"
    Then the step "Delete queue legacy-orders" shows "Already gone"

  @m9-05 @negative @fake-broker
  Scenario: A step that cannot be read is never shown as "Will be created"
    Given the broker does not answer when the queue "invoices" is read
    And a plan that declares the quorum queue "invoices"
    When I check the plan against "uat-eu"
    Then the step shows "Could not be checked" with the reason "the broker did not answer"

  @m9-05 @negative @fake-broker
  Scenario: A user who may not read an item gets "Could not be checked"
    Given the broker refuses the read of the policy "limits" for this user
    And a plan that declares the policy "limits" on "^orders" with "max-length" 1000
    When I check the plan against "uat-eu"
    Then the step shows "Could not be checked" with the reason "the user is not allowed to do this"

  @m9-05 @negative @safety @fake-broker
  Scenario: The check sends reads of the executor's allow-list and nothing else
    Given a plan with a queue named "get", an exchange, a binding, a stream and a policy
    When I check the plan against "uat-eu"
    Then every request was a GET for a queue, an exchange, a binding list or a policy by its name
    And no request path ended in "/get", "/contents" or "/purge" after a queue name
    And no request created, changed or deleted anything

  @m9-05 @negative @safety @fake-broker
  Scenario: A RabbitMQ 3.x connection is never checked
    Given I connect to "legacy-eu", a broker whose overview reports RabbitMQ 3.13.7
    And a plan that declares the quorum queue "orders"
    When I ask for a check
    Then the check is refused with "This broker runs RabbitMQ 3.x: it is a migration source only"
    And the check sent no request to "legacy-eu"

  @m9-05 @fake-broker
  Scenario: After applying the saved plan, the check finds everything already there
    Given the broker has neither the exchange "events" nor the queue "orders"
    And a plan that declares the direct exchange "events", the quorum queue "orders" and binds them with routing key "order.created"
    And the plan was saved as "0005" and "0005" was applied on the Migrations screen
    When I copy "0005" into a new plan and check it against "uat-eu"
    Then every step shows "Already there"

  @m9-05 @fake-broker
  Scenario: The check does not decide whether a plan can be saved
    Given the broker has the classic queue "orders"
    And a plan that declares the quorum queue "orders"
    When I check the plan against "uat-eu"
    Then "Save as migration" is still enabled

  # ---- M9-06: the check on the screen ----

  @m9-06 @fake-broker
  Scenario: The summary counts the results
    Given a plan whose check found 5 steps that will be created, 1 that is already there and 1 that exists with different settings
    Then the planner says "Checked against uat-eu" with the time and "5 will be created, 1 already there, 1 exists with different settings"

  @m9-06 @fake-broker
  Scenario: A check result goes stale when the plan changes
    Given a checked plan with 3 steps
    When I add a quorum queue "invoices" with the wizard
    Then the planner says "The plan changed since the check. Check again."
    And the results of the first 3 steps are shown as stale
    And the new step shows no result

  @m9-06 @fake-broker
  Scenario: A running check can be seen and does not block the plan
    Given a plan with 10 steps
    When the check is at step 4
    Then the planner says "Checking step 4 of 10 against uat-eu"
    And I can still read the steps

  @m9-06 @fake-broker
  Scenario: Switching the connection drops the check result
    Given a plan checked against "uat-eu"
    When I connect to "prod-eu"
    Then the steps show no result
    And the check button names "prod-eu"

  @m9-06 @fake-broker
  Scenario: A late check result of an old connection is dropped
    Given a check of the plan against "uat-eu" is running
    When I connect to "prod-eu" before it answers
    Then the answer for "uat-eu" is not shown

  # ---- real broker ----

  @m9-05 @broker
  Scenario: The check agrees with a real broker before and after applying
    Given a real RabbitMQ 4 broker
    And a plan that declares a direct exchange, a quorum queue with "x-delivery-limit" 5, a binding between them, a stream and a policy
    When the plan is checked
    Then every step shows "Will be created"
    When the plan is saved and applied by the migration runner and checked again
    Then every step shows "Already there"
    And the check sent GET requests only

  # Measured by M9-00c (S-G, docs/spec.md "Check against the broker"): the check predicts the broker's answer to a
  # repeated declare AND the read-back that follows it. The broker refuses (400) a difference in an argument its queue
  # type honours; it answers 204 and stores nothing for any other argument, and the read-back then stops the apply.
  # Row 4 and 5 use an argument the quorum type does not honour. "x-foo" is a made-up one: a real 4.4.0 broker refuses the
  # FIRST declare of "x-max-priority" (and "x-queue-mode", "x-queue-version") on a quorum queue with 400, so it cannot be set
  # up as "on the broker"; only a repeated declare of it is 204 (row 5, where the read-back then stops the apply).
  @m9-05b @broker
  Scenario Outline: What the check calls different is what a real broker refuses
    Given a real RabbitMQ 4 broker with a quorum queue "orders" declared with <on the broker>
    And a plan that declares the quorum queue "orders" with <in the plan>
    When the plan is checked
    Then the step shows "<shown>"
    When the plan is saved and applied by the migration runner
    Then <apply>
    And the queue "orders" on the broker is unchanged

    Examples:
      | on the broker        | in the plan          | shown                          | apply                                                              |
      | "x-delivery-limit" 3 | "x-delivery-limit" 5 | Exists with different settings | the apply stops at that step with "exists with different settings" |
      | "x-delivery-limit" 3 | no arguments         | Exists with different settings | the apply stops at that step with "exists with different settings" |
      | "x-delivery-limit" 3 | "x-delivery-limit" 3 | Already there                  | the apply succeeds and changes nothing                             |
      | "x-foo" 3            | no arguments         | Already there                  | the apply succeeds and changes nothing                             |
      | no arguments         | "x-max-priority" 3   | Exists with different settings | the apply stops at that step                                       |

  @m9-05b @broker
  Scenario: A name that the management API answers with a list is not read as a queue
    Given a real RabbitMQ 4 broker
    When the check reads the queue and the exchange named "." and ".." through the management API
    Then every step shows "Could not be checked"
    And the check sent GET requests only

  # M9-14c (owner decision O16, 2026-11-17): what "Already there" compares for a stream. Only what the step declares counts, as for a queue:
  # a step with no settings is the same whatever the broker holds (a stream the product made carries x-queue-leader-locator balanced);
  # every declared setting must be on the broker with the same value. Carries @needs-M9-14c and no @fake-broker until step 2 (the planner
  # runner selects m9-01 to m9-07 with @fake-broker, so nothing selects these before then).
  @m9-14c @needs-M9-14c
  Scenario Outline: A stream is compared on the settings its step declares
    Given the broker has the stream "audit" with "x-max-age" as the text "1D" and "x-queue-leader-locator" as the text "balanced"
    And a plan with the step <step>
    When I check the plan against "uat-eu"
    Then the step <step> shows "<result>"

    Examples:
      | step                                                       | result                         |
      | Declare stream audit                                       | Already there                  |
      | Declare stream audit with x-max-age 1D                     | Already there                  |
      | Declare stream audit with x-max-age 7D                     | Exists with different settings |
      | Declare stream audit with x-max-length-bytes 1000000000    | Exists with different settings |
      | Declare stream archive with x-max-age 7D                   | Will be created                |
