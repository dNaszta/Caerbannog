@M2 @clients @needs-spike
Feature: Publishers and consumers of a stream
  As an engineer
  I want to see who publishes to and consumes from a stream
  So that I can tell whether a consumer is behind and which service is responsible

  Data comes from the management API (rabbitmq_stream_management).
  Fields confirmed on a real RabbitMQ 4.4.0 broker (M2-02 spike, docs/spec.md "Management API facts"):
  publisher reference, published, confirmed, errored (shown as "errors"), connection; consumer offset,
  offset_lag, active, connection. A named consumer's lag can be 1 higher than the number of user messages
  behind, because its offset tracking entry occupies an offset, so "Caught up" needs M2-06 to decide how to
  read offset_lag. Users with only the management tag see only their own connections' rows, and users with
  no management tag see nothing (product decision S3 pending). The @needs-spike tag stays until M2-06 reviews this file.

  Background:
    Given I am connected to "uat-eu"
    And a stream "orders-events" with first offset 0 and last offset 48299

  @fake-broker
  Scenario: Publishers are listed with their connection and counters
    Given these publishers on "orders-events"
      | reference    | connection                         | published | confirmed | errors |
      | checkout-1   | 10.0.4.21:51234 - checkout-service | 48100     | 48100     | 0      |
      | backfill-job | 10.0.7.8:40022 - backfill          | 200       | 200       | 0      |
    When I open the "Publishers and consumers" tab of "orders-events"
    Then both publishers are listed with their connection, published, confirmed and error counts

  @fake-broker
  Scenario Outline: A consumer's lag is the distance to the last offset
    Given a consumer "<consumer>" on "orders-events" at offset <offset>
    When I open the "Publishers and consumers" tab of "orders-events"
    Then "<consumer>" shows <behind> messages behind
    And its status is "<status>"

    Examples:
      | consumer  | offset | behind | status    |
      | billing   | 48299  | 0      | Caught up |
      | analytics | 47950  | 349    | Behind    |
      | audit     | 48100  | 199    | Behind    |

  @fake-broker
  Scenario: Jump from a consumer to its offset in the browser
    Given a consumer "analytics" on "orders-events" at offset 47950
    When I open the "Publishers and consumers" tab of "orders-events"
    And I choose to browse from the offset of "analytics"
    Then the message browser opens on "orders-events" starting at offset 47950

  @fake-broker
  Scenario: A stream nobody uses
    Given "orders-events" has no publishers and no consumers
    When I open the "Publishers and consumers" tab of "orders-events"
    Then I see that there are no publishers and no consumers

  @negative @fake-broker
  Scenario: The stream management plugin is missing
    Given the broker does not have the "rabbitmq_stream_management" plugin enabled
    When I open the "Publishers and consumers" tab of "orders-events"
    Then I am told to enable "rabbitmq_stream_management"
    And the rest of the stream screen keeps working

  @broker
  Scenario: Counts on the tab match counts in the stream list
    Given a real broker where "orders-events" has 2 publishers and 3 consumers connected
    When I open the stream list
    And I open the "Publishers and consumers" tab of "orders-events"
    Then the tab lists 2 publishers and 3 consumers
