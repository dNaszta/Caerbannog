@M2 @clients
Feature: Publishers and consumers of a stream
  As an engineer
  I want to see who publishes to and consumes from a stream
  So that I can tell whether a consumer is behind and which service is responsible

  Data comes from the management API (rabbitmq_stream_management).
  Fields confirmed on a real RabbitMQ 4.4.0 broker (M2-02 spike, docs/spec.md "Management API facts"):
  publisher reference, published, confirmed, errored (shown as "errors"), connection; consumer offset,
  offset_lag, active, connection. A consumer's lag can be higher than the number of user messages
  behind, because every stored offset (offset tracking entry) of any application occupies an offset (real broker,
  M2-06 spike S-A: 2 and 3 for a consumer that stored 3 times, varying between runs, not derivable from the API).
  Corrected by M2-17 (2026-11-15): that was the broker's first statistics window. After the second refresh every
  consumer that has read to the end of a stream shows the same lag, whether it stores offsets or not (0 on a stream
  where nobody stored, 2 or 4 where offsets were stored). M2-06 decided:
  "Caught up" only at lag 0; above 0 the status reads "N behind" with the broker's number, in a neutral style,
  and a tooltip says stored offsets count (docs/tasks/M2-06-publishers-consumers.md).
  Users with only the management tag see only their own connections' rows, and users with no management
  tag see nothing (product decisions S3a and S3b, decided: message, and a fixed notice).
  The stream screen opens from the stream list (the name is a link); only this tab exists until M3.

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
      | consumer  | offset | behind | status     |
      | billing   | 48299  | 0      | Caught up  |
      | analytics | 47950  | 349    | 349 behind |
      | audit     | 48100  | 199    | 199 behind |
      | reports   | 48297  | 2      | 2 behind   |

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

  @fake-broker
  Scenario: A user with only the management tag sees their own connections
    Given the user of "uat-eu" has the management tag but not the monitoring tag
    When I open the "Publishers and consumers" tab of "orders-events"
    Then the tab shows the notice "Showing only your own connections. The monitoring tag shows all."
    And the notice is shown even when the lists are empty

  @fake-broker
  Scenario: A user without the management tag
    Given the user of "uat-eu" has no management tag
    When I open the "Publishers and consumers" tab of "orders-events"
    Then I see the message "This user cannot read stream statistics. Ask for the monitoring tag."
    And no publishers or consumers are listed

  @fake-broker
  Scenario: A consumer that stored its offsets can read a few behind without being behind
    Given a consumer "billing" on "orders-events" that stored its offset and is 3 messages behind
    When I open the "Publishers and consumers" tab of "orders-events"
    Then its status is "3 behind"
    And the status explains that stored offsets count towards the number

  @broker
  Scenario: Counts on the tab match counts in the stream list
    Given a real broker where "orders-events" has 2 publishers and 3 consumers connected
    When I open the stream list
    And I open the "Publishers and consumers" tab of "orders-events"
    Then the tab lists 2 publishers and 3 consumers

  # M2-17 (2026-11-15). Live twins of the lag rule, bound to RealBrokerClientsTest "S-A ...". The broker's lag is read in its steady state
  # (after the second statistics refresh, about 5 s after a consumer attaches), not only in the first window, where the lags differ per consumer and a consumer on a stream where nobody stored reads 0.
  @broker
  Scenario: A consumer that has read everything on a stream where nobody stored an offset is caught up
    Given a real broker where nobody has stored an offset on "orders-events"
    And an unnamed consumer and a named consumer that have read every message
    When I open the "Publishers and consumers" tab of "orders-events" and keep it open for two refreshes
    Then each of them shows 0 messages behind
    And each status is "Caught up"

  @broker
  Scenario: The number of messages behind is the number the broker reports
    Given a real broker where applications stored offsets on "orders-events"
    And all consumers have read every message
    When I open the "Publishers and consumers" tab of "orders-events"
    Then each consumer shows the lag that the management API reports for it, nothing added and nothing taken away

  @fake-broker
  Scenario: Opening a stream from the list shows its publishers and consumers
    Given these publishers on "orders-events"
      | reference  | connection                         | published | confirmed | errors |
      | checkout-1 | 10.0.4.21:51234 - checkout-service | 48100     | 48100     | 0      |
    When I open the stream list
    And I open the stream "orders-events"
    Then the "Publishers and consumers" tab of "orders-events" lists 1 publisher

  @fake-broker
  Scenario: A client that has just attached is not shown as a settled row
    Given a consumer on "orders-events" whose connection name is not known yet
    When I open the "Publishers and consumers" tab of "orders-events"
    Then its connection reads "unknown"
    And its offset, messages behind and status read "updating"

  # M2-17c: a broker row without a messages-behind number is a settling row, never 0 and never "Caught up" (not seen on RabbitMQ 4; a robustness rule).
  @fake-broker
  Scenario: A consumer without a messages-behind number from the broker is not shown as caught up
    Given a consumer on "orders-events" for which the broker sent no messages-behind number
    When I open the "Publishers and consumers" tab of "orders-events"
    Then its offset, messages behind and status read "updating"

  @fake-broker
  Scenario: A row settles on the next refresh
    Given a consumer on "orders-events" whose connection name is not known yet
    And I am on the "Publishers and consumers" tab of "orders-events"
    When the broker now reports "billing" at offset 48299 with connection "10.0.5.14:38812 - billing-service"
    And the tab refreshes
    Then "billing" shows 0 messages behind
    And its status is "Caught up"

  @fake-broker
  Scenario: The tab refreshes only while it is shown
    Given I am on the "Publishers and consumers" tab of "orders-events"
    When I leave the tab
    And the refresh interval passes
    Then the broker is not asked for publishers or consumers

  @fake-broker
  Scenario: The stream is deleted while its tab is open
    Given I am on the "Publishers and consumers" tab of "orders-events"
    When "orders-events" is deleted on the broker and the tab refreshes
    Then I am told that the stream no longer exists
    And I can go back to the stream list

  @fake-broker
  Scenario: A lost connection keeps the rows and marks them stale
    Given a consumer "billing" on "orders-events" at offset 48299
    And I am on the "Publishers and consumers" tab of "orders-events"
    When the connection to the broker is lost
    Then "billing" is still listed and marked stale
    And the tab offers "Retry"

  @fake-broker
  Scenario: The last offset of the stream is shown above the consumers
    When I open the "Publishers and consumers" tab of "orders-events"
    Then the tab shows the last offset 48299
