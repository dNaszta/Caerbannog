@M5 @publish
Feature: Publish a test message
  As an engineer
  I want to send a single test message to any stream, exchange or queue
  So that I can try out consumers and routing without writing code

  Publishing needs the write permission. It is allowed on every connection the user has write access to,
  but the dialog always says which broker the message goes to.

  Background:
    Given I am connected to "prod-eu"
    And the user has write permission on "/"

  @smoke @fake-broker
  Scenario: Publish to a stream
    Given a stream "orders-events"
    When I open the publish dialog and choose the target kind "Stream"
    And I pick "orders-events" and enter the body {"orderId":"TEST-1"}
    And I publish
    Then the broker receives one message on "orders-events" with that body
    And I see a confirmation that the message was published

  @fake-broker
  Scenario: Publish to an exchange with a routing key
    Given an exchange "orders.events"
    When I open the publish dialog and choose the target kind "Exchange"
    And I pick "orders.events", set the routing key "order.created" and enter the body {"orderId":"TEST-2"}
    And I publish
    Then the broker receives one message for exchange "orders.events" with routing key "order.created"

  @fake-broker
  Scenario: Publish to a queue
    Given a quorum queue "orders"
    When I open the publish dialog and choose the target kind "Queue"
    And I pick "orders" and enter the body "hello"
    And I publish
    Then the broker receives one message for the queue "orders"

  @fake-broker
  Scenario: Set properties and application properties
    When I open the publish dialog
    And I set message-id "test-0001" and content-type "application/json"
    And I add the application property "tenant" with value "eu"
    And I publish to the stream "orders-events"
    Then the published message carries those properties and application properties

  @fake-broker
  Scenario: The dialog says which broker receives the message
    When I open the publish dialog
    Then I see that the message goes to the real broker on "prod-eu"

  @negative @fake-broker
  Scenario: Publishing is disabled without write permission
    Given the user has no write permission on "/"
    When I open the stream "orders-events"
    Then the "Publish test message" action is disabled
    And it explains that the user has no write permission

  @negative @fake-broker
  Scenario: The broker refuses the message
    Given the broker refuses publishes to "orders-events" with an access error
    When I publish a test message to "orders-events"
    Then I see the broker's refusal
    And I am not told that the message was published

  @negative @fake-broker
  Scenario: Cancel leaves nothing behind
    When I open the publish dialog and fill in a message
    And I cancel
    Then nothing is sent to the broker

  @broker
  Scenario: A published message appears at the next offset
    Given a real broker with a stream "orders-events" whose last offset is 99
    When I publish a test message to "orders-events"
    And I browse "orders-events" starting from "Last page"
    Then the last message shown is at offset 100
    And its body is the one I published
