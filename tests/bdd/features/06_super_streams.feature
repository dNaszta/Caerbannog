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

  @fake-broker @needs-decision
  Scenario: Expand a super stream to see its partitions
    Given the partitions hold 31200, 32900 and 32000 messages
    When I open the stream list
    And I expand "orders"
    Then I see each partition with its own messages, offsets, size, publishers and consumers
    And the super stream row shows 96,100 messages in total

  @fake-broker
  Scenario: Browse one partition
    When I choose to browse the partition "orders-1"
    Then the message browser opens on "orders-1"
    And the page title makes clear that it is a partition of "orders"

  @negative @fake-broker
  Scenario: There is no merged view of all partitions
    When I open the super stream "orders"
    Then there is no option to browse all partitions as one sequence
    And I am told that order only exists inside a partition

  @fake-broker
  Scenario: Filter super streams
    When I choose the "Super streams" filter in the stream list
    Then only "orders" is listed

  @fake-broker
  Scenario: Global search finds partitions under their super stream
    When I search globally for "orders"
    Then the results include the super stream "orders"
    And its partitions are reachable from that result
