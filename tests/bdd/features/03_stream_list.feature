@M2 @streams
Feature: Stream list
  As an engineer
  I want to see all streams on a connection with their statistics
  So that I can find the stream I care about and judge its health at a glance

  Message counts are approximate: the count is the broker's own message count for the stream,
  and the last offset is the first offset plus that count minus one. (Do not use committed
  offset minus first offset plus one: the committed offset is where the last chunk starts, so it
  undercounts messages published in batches; M2-02 spike.) The count can be slightly higher than
  the number of user messages, because offset tracking data is stored in the stream; RabbitMQ's
  own Management UI shows the same number.
  The Size column is shown but left blank (a dash) until M3: the broker reports no size in bytes
  for streams (product decision N1, option c). First and last offset come from the stream protocol,
  one statistics call per visible stream. When that call fails or is denied, the row still shows its
  counts from the management API and the offset cells read "unavailable".
  A user without the management tag gets a message instead of the list; a user with only the
  "monitoring" tag or more gets the full list (product decision S3a).

  Background:
    Given I am connected to "uat-eu"

  @smoke @fake-broker
  Scenario: The list shows the statistics of every stream
    Given these streams exist
      | name            | first offset | last offset | publishers | consumers |
      | orders-events   | 0            | 48299       | 2          | 3         |
      | audit-log       | 880000       | 2083999     | 1          | 1         |
      | payments-stream | 0            | 7849        | 1          | 0         |
    When I open the stream list
    Then each stream is shown with its type, approximate messages, first offset, last offset, publishers and consumers
    And the Size cell of each stream is blank

  @fake-broker
  Scenario Outline: The message count and the offsets agree
    Given a stream "s" with first offset <first> and last offset <last>
    When I open the stream list
    Then the stream "s" shows about <count> messages

    Examples:
      | first  | last    | count     |
      | 0      | 48299   | 48,300    |
      | 880000 | 2083999 | 1,204,000 |
      | 0      | 0       | 1         |

  @fake-broker
  Scenario: The first offset cannot be read for one stream
    Given a stream "orders-events" whose statistics call is denied
    And a stream "audit-log" with first offset 0 and last offset 99
    When I open the stream list
    Then "orders-events" is listed with its message count, publishers and consumers
    And the first offset and last offset cells of "orders-events" read "unavailable"
    And "audit-log" shows first offset 0 and last offset 99

  @fake-broker
  Scenario: A user without the management tag
    Given the user of "uat-eu" has no management tag
    When I open the stream list
    Then I see the message "This user cannot read stream statistics. Ask for the monitoring tag."
    And no stream list is shown

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
    Then the publisher count and consumer count of "orders-events" equal those in the RabbitMQ Management UI
    And the message count differs from the Management UI by no more than the offset tracking entries
