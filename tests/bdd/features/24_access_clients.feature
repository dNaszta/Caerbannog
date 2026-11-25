@M10 @access-view @clients
Feature: Access view, part 4: who produces and consumes, by user
  As an engineer
  I want to see which connections, channels and users publish to and consume from a queue or stream
  So that I know who is behind the traffic, identified by the user the broker reports

  Sources: GET /api/connections, /api/channels, /api/consumers and the stream endpoints of M2-06.
  The app only reads these lists. It never closes a connection, never reads queue messages.
  Connection and channel records carry client-supplied properties, so every value is allow-listed
  and scanned before it is shown, copied or returned by MCP (spec "Access view (M10)", "Redaction").
  Field names and what a classic queue's publishers look like are measured by the spike M10-00
  (S-D to S-H); scenarios tagged @needs-spike wait for it.

  Background:
    Given the connection "prod-eu" is the held connection
    And the connection user is "ops-admin" with the tag "administrator"
    And the queue "orders" on "/" has the consumer "c1" on a channel of a connection of the user "svc-orders"
    And the stream "events" on "/" has a publisher and a consumer of the user "svc-events"

  # ---------------------------------------------------------------- M10-06 connections, channels, queues

  @smoke @fake-broker @m10-06
  Scenario: Connections are grouped by user with counts
    Given the user "svc-orders" has 3 connections and "svc-events" has 1
    When I open the Clients tab of the Access view
    Then I see the user "svc-orders" with 3 connections
    And I see the user "svc-events" with 1 connection

  @fake-broker @m10-06
  Scenario: The consumers of a queue show connection, channel and user
    When I choose the queue "orders" on "/" on the Clients tab
    Then I see the consumer "c1" with its channel, its connection and the user "svc-orders"
    And I see whether each consumer is active

  @needs-spike @fake-broker @m10-06
  Scenario: The publishers of a queue are shown through the exchange they publish to
    Given a channel of the user "svc-orders" publishes to the exchange "orders-in"
    And "orders-in" is bound to the queue "orders"
    When I choose the queue "orders" on the Clients tab
    Then I see the user "svc-orders" as publishing to "orders-in", which routes to "orders"
    And I do not see it presented as a direct publisher of the queue

  @needs-spike @fake-broker @m10-06
  Scenario: When the broker does not tell who publishes, the view says so
    Given the broker reports no publisher information for the exchange "orders-in"
    When I choose the queue "orders" on the Clients tab
    Then I see that the publishers could not be told from the broker's data
    And no publisher is guessed from names

  @fake-broker @m10-06
  Scenario: A user with the management tag sees only their own connections and is told so
    Given the connection user is "svc-orders" with the tag "management"
    When I open the Clients tab of the Access view
    Then I see only the connections of "svc-orders"
    And I see a fixed notice that this user sees only their own connections

  @fake-broker @m10-06
  Scenario: A user with the monitoring tag sees the connections of the whole broker
    Given the connection user is "svc-orders" with the tag "monitoring"
    When I open the Clients tab of the Access view
    Then I see the connections of every user the broker lists

  @fake-broker @m10-06
  Scenario: A connection of a token user shows the user name the broker reports
    Given the broker reports the connection user "9f1c2e7a" for a client that signed in with a token
    When I open the Clients tab of the Access view
    Then I see the user "9f1c2e7a"
    And I do not see a name made up from anything else

  @fake-broker @m10-06
  Scenario: Filter by user and by queue
    When I filter the Clients tab by the user "svc-events"
    Then I see only the connections and consumers of "svc-events"
    When I clear the filter and filter by the queue "orders"
    Then I see only the consumers of "orders"

  @fake-broker @m10-06
  Scenario: A broker with thousands of connections is read in pages
    Given the broker has 2300 connections
    When I open the Clients tab of the Access view
    Then the app reads them in pages of at most 500
    And the screen stays responsive while the pages arrive
    And the screen says how many connections there are in total

  @fake-broker @m10-06
  Scenario: An empty broker shows an empty state
    Given the broker has no connections
    When I open the Clients tab of the Access view
    Then I see that nothing is connected
    And I do not see an error

  @fake-broker @m10-06
  Scenario: The lists refresh while the tab is shown and only then
    When I open the Clients tab of the Access view
    Then the lists refresh at the app-wide refresh interval
    When I leave the tab
    Then no further request is sent

  # ---------------------------------------------------------------- redaction

  @security @fake-broker @m10-06
  Scenario: Only allow-listed client properties are shown
    Given a connection carries the client properties "product", "version", "platform", "connection_name" and "internal_note"
    When I open the details of that connection
    Then I see "product", "version", "platform" and "connection_name"
    And I do not see "internal_note"

  @security @fake-broker @m10-06
  Scenario Outline: A value that looks like a secret is replaced before it is shown
    Given a connection whose "connection_name" is "<value>"
    When I open the Clients tab of the Access view
    Then the connection is shown with the name replaced by "hidden"
    And "<fragment>" appears nowhere on the screen, in the copied text or in the log

    Examples:
      | value                                           | fragment          |
      | eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln       | eyJzdWIiOiJ4In0   |
      | Bearer abcdef0123456789                         | abcdef0123456789  |
      | amqps://svc:hunter2@broker.internal:5671        | hunter2           |
      | token=abc123def456                              | abc123def456      |

  @security @fake-broker @m10-06
  Scenario: The held password and its parts are never shown
    Given the held password is a JWT
    And a connection carries a client property that contains a part of that JWT
    When I open the Clients tab of the Access view
    Then that part appears nowhere on the screen, in the copied text or in the log

  @security @fake-broker @m10-06
  Scenario: The app asks the broker only for the columns it shows
    When I open the Clients tab of the Access view
    Then the connections request names the columns it needs
    And the client properties are not among them unless an allow-listed field needs them

  @security @safety @fake-broker @m10-06
  Scenario: No control closes a connection or reads a queue message
    When I open the Clients tab of the Access view
    Then there is no control to close a connection or a channel
    And there is no control to get, consume, acknowledge or purge a queue message

  # ---------------------------------------------------------------- M10-07 streams and MQTT

  @smoke @fake-broker @m10-07
  Scenario: Stream publishers and consumers show the user
    When I choose the stream "events" on "/" on the Clients tab
    Then I see the publisher with its connection and the user "svc-events"
    And I see the consumer with its connection and the user "svc-events"

  @fake-broker @m10-07
  Scenario: A publisher whose connection is not yet known is shown as settling
    Given a stream publisher attached a moment ago and its connection is not yet reported
    When I choose the stream "events" on "/" on the Clients tab
    Then the publisher is shown as settling and not as an error

  @fake-broker @m10-07
  Scenario: MQTT clients are listed by user
    Given an MQTT client "sensor-7" connected as the user "svc-iot"
    When I open the Clients tab of the Access view
    Then I see the client "sensor-7" under the user "svc-iot"

  @fake-broker @m10-07
  Scenario: The stream plugin is missing
    Given the broker has no stream management plugin
    When I choose the stream "events" on "/" on the Clients tab
    Then I see that publishers and consumers of streams are not available on this broker
    And the queue lists are still shown

  @gate-M10 @broker @m10-09
  Scenario: A real broker's clients are listed by user and nothing leaks
    Given a real broker with an AMQP consumer, an AMQP publisher, a stream publisher and an MQTT client of different users
    When the clients are read through the app's gateway
    Then every client appears under the user the broker reports
    And no value read contains a password or a token
    And no queue message was read, acknowledged or removed
