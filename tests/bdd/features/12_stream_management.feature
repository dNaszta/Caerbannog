@M5 @streams @manage
Feature: Create and delete streams
  As an engineer
  I want to create and delete streams from the app
  So that I can set up and clean up test data without a command line

  Creating and deleting streams needs the configure permission.
  Deleting is destructive and needs the stream's name typed to confirm.

  Background:
    Given I am connected to "uat-eu"
    And the user has configure permission on "/"

  @smoke @fake-broker
  Scenario: Create a stream
    When I create a stream named "payments-stream"
    Then the broker is asked to create the stream "payments-stream"
    And "payments-stream" appears in the stream list

  @negative @fake-broker
  Scenario Outline: A stream name must be usable
    When I try to create a stream named "<name>"
    Then the create action is refused with the message "<message>"

    Examples:
      | name          | message                          |
      |               | A name is required               |
      | orders-events | A stream with this name exists   |

  @negative @fake-broker
  Scenario: The broker refuses the new stream
    Given the broker refuses to create streams with an error
    When I create a stream named "payments-stream"
    Then I see the broker's error
    And "payments-stream" is not added to the list

  @fake-broker
  Scenario: Delete needs the name typed to confirm
    Given a stream "audit-log"
    When I choose to delete the stream "audit-log"
    Then the delete is disabled until I type "audit-log"
    When I type "audit-log" and confirm
    Then the broker is asked to delete the stream "audit-log"
    And "audit-log" is no longer in the stream list

  @fake-broker
  Scenario: Typing the wrong name does not delete
    Given a stream "audit-log"
    When I choose to delete the stream "audit-log" and type "audit"
    Then the delete stays disabled

  @fake-broker
  Scenario: Cancel the delete
    Given a stream "audit-log"
    When I choose to delete the stream "audit-log" and cancel
    Then nothing is sent to the broker

  @negative @fake-broker
  Scenario Outline: Both actions are locked without configure permission
    Given the user has no configure permission on "/"
    When I open the stream list
    Then the "<action>" action is disabled
    And it explains that topology is locked because the user has no configure permission

    Examples:
      | action        |
      | Create stream |
      | Delete stream |

  @fake-broker
  Scenario: Deleting a stream that has consumers warns first
    Given a stream "orders-events" with 3 consumers
    When I choose to delete the stream "orders-events"
    Then I am warned that 3 consumers are connected

  @broker
  Scenario: Create then delete on a real broker
    Given a real broker
    When I create the stream "bdd-temp-stream" and then delete it with the name typed
    Then the stream "bdd-temp-stream" no longer exists on the broker
