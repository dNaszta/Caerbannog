@M2 @M3 @super-streams
Feature: Super streams
  As an engineer
  I want partitioned (super) streams to be easy to understand and browse
  So that I can inspect any partition without confusing it with a different stream

  A super stream is a set of partitions; each partition is a regular stream.
  Order exists only inside a partition, so v1 never merges partitions into one sequence.

  Background:
    Given I am connected to "uat-eu"
    And a super stream "orders" with partitions "orders-0", "orders-1" and "orders-2"

  @smoke @fake-broker
  Scenario: A super stream is one entry in the list
    When I open the stream list
    Then "orders" is listed once, as a "Super stream" with 3 partitions
    And "orders-0", "orders-1" and "orders-2" are not listed as separate top-level entries

  @fake-broker
  Scenario: Expand a super stream to see its partitions
    Given the partitions hold 31200, 32900 and 32000 messages
    When I open the stream list
    And I expand "orders"
    Then I see each partition with its own messages, offsets, publishers and consumers, and the dash in the Size cell
    And the super stream row shows 96,100 messages in total

  # The browser is M3. M2-07 only proves that the row carries "partition of orders" (core unit test); the scenario runs in M3.
  @fake-broker
  Scenario: Browse one partition
    When I choose to browse the partition "orders-1"
    Then the message browser opens on "orders-1"
    And the page title makes clear that it is a partition of "orders"

  @negative @fake-broker
  Scenario: There is no merged view of all partitions
    When I open the stream list
    And I expand "orders"
    Then the super stream "orders" has no option to open or browse it
    And I am told that order only exists inside a partition

  # Filtering by kind is the scenario "Filter by kind" in 03_stream_list.feature (one behaviour, one scenario).

  @fake-broker
  Scenario: Partitions are listed in the order the broker defines
    Given the super stream "regions" with partitions "regions-us" and "regions-eu", ordered us first
    When I open the stream list
    And I expand "regions"
    Then the partitions of "regions" are listed as "regions-us", "regions-eu"

  @fake-broker
  Scenario: A super stream is found by the name of one of its partitions
    When I search the list for "orders-1"
    Then "orders" is listed
    And "orders-1" is not listed as a separate top-level entry

  @fake-broker
  Scenario: A super stream counts as one entry in paging
    Given 11 plain streams exist besides "orders"
    When I open the stream list with a page size of 12
    Then the list says "1 to 12 of 12"

  @fake-broker
  Scenario: A refresh keeps a super stream expanded
    Given I have the stream list open and "orders" is expanded
    When the list refreshes
    Then "orders" is still expanded

  @negative @fake-broker
  Scenario: A partition whose message count is unknown gives no partial total
    Given the partition "orders-1" has no statistics yet
    When I open the stream list
    Then the super stream row shows "n/a" messages, not a partial total

  @negative @fake-broker
  Scenario: The super stream structure cannot be read
    Given the broker refuses to list the exchanges of the virtual host
    When I open the stream list
    Then "orders-0", "orders-1" and "orders-2" are listed as separate streams
    And the list says that super stream grouping is unavailable

  @broker
  Scenario: A super stream on a real broker is grouped
    Given a real broker with the super stream "orders" with 3 partitions
    When I open the stream list
    Then "orders" is listed once, as a "Super stream" with 3 partitions

  # Global search is not part of M2-07; the scenario runs with the search task.
  @fake-broker @needs-M3
  Scenario: Global search finds partitions under their super stream
    When I search globally for "orders"
    Then the results include the super stream "orders"
    And its partitions are reachable from that result
