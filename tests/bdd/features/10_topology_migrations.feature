@M5 @migrations
Feature: Topology migrations
  As an engineer
  I want to change broker topology through versioned JSON files
  So that changes are reviewable, repeatable and can be applied to several brokers

  Migrations behave like a database migration tool: files are applied in version order,
  applied versions are tracked on this machine per connection profile, and every action
  must be safe to run twice. The same JSON format is used to apply, export and import.
  The file format is a proposal and may still change.

  Background:
    Given I am connected to "uat-eu"
    And the user has configure permission on "/"

  @smoke @fake-broker
  Scenario: Apply pending migrations in version order
    Given migration files "0001", "0003" and "0002" in the migrations folder
    And none of them has been applied to "uat-eu"
    When I apply the pending migrations
    Then they are applied in the order "0001", "0002", "0003"
    And "0001", "0002" and "0003" are recorded as applied for "uat-eu" on this machine

  @fake-broker
  Scenario: Only pending migrations are applied
    Given "0001" and "0002" are already applied to "uat-eu" on this machine
    And a new migration file "0003"
    When I apply the pending migrations
    Then only "0003" is applied

  @fake-broker
  Scenario: History is kept per connection profile
    Given "0001" is applied to "uat-eu" on this machine
    When I look at the migrations of "prod-eu"
    Then "0001" is shown as pending for "prod-eu"

  @fake-broker
  Scenario: Running a migration twice changes nothing the second time
    Given the migration "0003" declares the quorum queue "orders" and a binding to the exchange "events"
    And "0003" has already been applied directly on the broker by someone else
    When I apply "0003" on this machine
    Then the broker topology is unchanged
    And "0003" is recorded as applied

  @fake-broker
  Scenario: A dry run shows what would happen
    Given a pending migration "0003" with 2 actions
    When I run a dry run of "0003"
    Then I see each action and whether it is allowed
    And nothing is sent to the broker

  @negative @fake-broker
  Scenario: Apply is disabled without configure permission
    Given the user has no configure permission on "/"
    And a pending migration "0003"
    When I open the migrations screen
    Then "Apply pending" is disabled
    And I am told that the user cannot change topology on "uat-eu"
    But I can still edit, validate and export the file

  @fake-broker
  Scenario: Export the current topology as a migration
    Given the broker has the quorum queue "orders" and the exchange "events"
    And the latest migration file is "0004"
    When I export the current topology
    Then a new migration file "0005" is created
    And it declares the queues, exchanges, bindings and policies of the broker

  @fake-broker
  Scenario: Import a migration file
    Given a valid migration file "0005.json" on disk
    When I import it
    Then "0005" appears in the migrations list as pending

  @negative @fake-broker
  Scenario Outline: Invalid migration files are rejected with a clear reason
    When I import a migration file that <problem>
    Then the import is refused
    And the message says <reason>
    And nothing is added to the migrations list

    Examples:
      | problem                              | reason                                |
      | is not valid JSON                    | the file is not valid JSON            |
      | has no version                       | the file has no version                |
      | repeats the version of another file  | that version already exists            |
      | uses an action the app does not know | which action is not supported          |
      | is nested ten thousand levels deep   | the file is nested too deep            |

  @negative @needs-decision @fake-broker
  # Open product decision: behavior when an action fails half-way through a migration.
  # Proposed: stop, report the failing action, do not record the migration as applied.
  Scenario: A failing action stops the migration
    Given a pending migration "0003" whose second action is refused by the broker
    When I apply "0003"
    Then the migration stops at the second action
    And the message names the failing action and the broker's reason
    And "0003" is not recorded as applied

  @fake-broker
  Scenario: Editing an applied migration is flagged
    Given "0002" is applied to "uat-eu" on this machine
    When the file "0002" is changed on disk
    Then the app warns that an applied migration was modified
    And it does not apply the changed file again

  @broker
  Scenario: Apply a migration to a real broker
    Given a real broker and a migration that declares the quorum queue "orders" and binds it to the exchange "events" with routing key "order.created"
    When I apply the pending migrations
    Then the broker has a quorum queue "orders" bound to "events" with routing key "order.created"

  @broker @gate-M5
  Scenario: Exported topology can be applied to an empty broker
    Given a real broker A with queues, exchanges and bindings
    And a second empty real broker B
    When I export the topology of A and apply the resulting migration to B
    Then B has the same queues, exchanges and bindings as A
