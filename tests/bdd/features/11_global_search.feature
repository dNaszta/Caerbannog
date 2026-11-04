@M2 @M3 @search
Feature: Global search
  As an engineer
  I want one search box that finds streams, queues and exchanges by name
  So that I can jump to a thing without knowing which screen it lives on

  The search looks at names only, never at message contents.
  For queues and exchanges it uses the management API's server-side name filter with paging
  (100 results per page by default, 500 at most).

  Background:
    Given I am connected to "uat-eu"
    And these things exist
      | kind     | name          |
      | stream   | orders-events |
      | stream   | audit-log     |
      | queue    | orders        |
      | queue    | legacy-orders |
      | queue    | invoices      |
      | exchange | orders.events |

  @smoke @fake-broker
  Scenario: Results are grouped by kind
    When I search globally for "orders"
    Then I see a "Streams" group with "orders-events"
    And I see a "Queues" group with "orders" and "legacy-orders"
    And I see an "Exchanges" group with "orders.events"
    And each result shows its kind

  @fake-broker
  Scenario: Filter chips show counts
    When I search globally for "orders"
    Then the chips show "All 4", "Streams 1", "Queues 2" and "Exchanges 1"

  @fake-broker
  Scenario: Narrow the results with a chip
    Given I searched globally for "orders"
    When I choose the "Queues" chip
    Then only "orders" and "legacy-orders" are shown

  @fake-broker
  Scenario: Search with a regular expression
    Given I searched globally for "^orders"
    When I turn on "Regex"
    Then "orders-events", "orders" and "orders.events" are shown
    But "legacy-orders" is not shown

  @negative @fake-broker
  Scenario: An invalid regular expression is explained
    When I turn on "Regex" and search globally for "orders("
    Then I see a message that the expression is not valid
    And the previous results stay on screen

  @smoke @fake-broker
  Scenario: Enter opens the first result
    Given I searched globally for "orders"
    When I press Enter
    Then the stream "orders-events" opens in the message browser

  @fake-broker
  Scenario: Choose a result with the mouse or keyboard
    Given I searched globally for "orders"
    When I choose the queue "legacy-orders"
    Then the topology screen opens with "legacy-orders" selected

  @fake-broker
  Scenario: Nothing found
    When I search globally for "no-such-thing"
    Then I see that nothing matches "no-such-thing"

  @negative @fake-broker
  Scenario: Message contents are never searched
    Given the stream "audit-log" has a message whose body contains "needle"
    When I search globally for "needle"
    Then nothing matches "needle"

  @fake-broker
  Scenario: Large brokers are searched on the server in pages
    Given the broker has 600 queues whose names contain "orders"
    When I search globally for "orders"
    Then the app asks the management API for queues by name, one page at a time
    And I can load more results without waiting for all 600

  @fake-broker
  Scenario: Search is case-insensitive for plain text
    When I search globally for "ORDERS"
    Then the same results are shown as for "orders"

  # Confirmed by the M2-02 spike: streams are queues with type "stream", so the same server-side name filter finds them.
  @broker
  Scenario: Streams are found by name on a real broker
    Given a real broker with the stream "orders-events"
    When I search globally for "orders"
    Then "orders-events" is listed in the "Streams" group
