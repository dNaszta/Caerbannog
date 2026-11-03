@M2 @streams
Feature: Stream list
  As an engineer
  I want to see all streams on a connection with their statistics
  So that I can find the stream I care about and judge its health at a glance

  Message counts are approximate: committed offset minus first offset, plus one.
  RabbitMQ's own Management UI can show a slightly different count, because offset tracking
  data is stored in the stream.

  Background:
    Given I am connected to "uat-eu"

  @smoke @fake-broker
  Scenario: The list shows the statistics of every stream
    Given these streams exist
      | name            | first offset | last offset | size   | publishers | consumers |
      | orders-events   | 0            | 48299       | 212 MB | 2          | 3         |
      | audit-log       | 880000       | 2083999     | 3.4 GB | 1          | 1         |
      | payments-stream | 0            | 7849        | 31 MB  | 1          | 0         |
    When I open the stream list
    Then each stream is shown with its type, approximate messages, first offset, last offset, size, publishers and consumers

  @fake-broker
  Scenario Outline: The message count is derived from the offsets
    Given a stream "s" with first offset <first> and last offset <last>
    When I open the stream list
    Then the stream "s" shows about <count> messages

    Examples:
      | first  | last    | count     |
      | 0      | 48299   | 48,300    |
      | 880000 | 2083999 | 1,204,000 |
      | 0      | 0       | 1         |

  @fake-broker
  Scenario: Counts are labelled as approximate
    Given a stream "orders-events" exists
    When I open the stream list
    Then the list says that message counts are approximate

  @fake-broker
  Scenario: Filter streams by name
    Given streams "orders-events", "audit-log" and "payments-stream" exist
    When I search the list for "orders"
    Then only "orders-events" is listed

  @fake-broker
  Scenario: Filter by kind
    Given a stream "audit-log" and a super stream "orders" with 3 partitions exist
    When I choose the "Super streams" filter
    Then only the super stream "orders" is listed

  @fake-broker
  Scenario: Sort the list
    Given streams "b-stream", "a-stream" and "c-stream" exist
    When I sort the list by name
    Then the streams are listed as "a-stream", "b-stream", "c-stream"

  @fake-broker
  Scenario: Refresh updates the statistics
    Given a stream "orders-events" with last offset 100
    And I have the stream list open
    When messages are published so the last offset becomes 150
    And I refresh the list
    Then "orders-events" shows the new last offset 150
    And the list says it was updated a moment ago

  @fake-broker
  Scenario: A broker with no streams
    Given the broker has no streams
    When I open the stream list
    Then I see a message that there are no streams yet
    And I am offered the "Create stream" action

  @fake-broker
  Scenario: Long lists are paged
    Given the broker has 12 streams
    When I open the stream list with 5 streams per page
    Then I see streams 1 to 5 of 12
    And I can move to the next page

  @broker @gate-M2
  Scenario: Statistics agree with the Management UI
    Given a real broker with a stream "orders-events" containing 1000 messages
    When I open the stream list
    Then the size, publisher count and consumer count of "orders-events" equal those in the RabbitMQ Management UI
    And the message count differs from the Management UI by no more than the offset tracking entries
