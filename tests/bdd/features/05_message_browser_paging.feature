@M3 @browser @paging
Feature: Message browser with paging
  As an engineer
  I want to read the messages of a stream page by page
  So that I can inspect any part of a stream without disturbing real consumers

  Each page is a short-lived read with an unnamed consumer, so browsing stores no offsets.
  Offsets start at 0. A page shows up to "page size" messages.

  Background:
    Given I am connected to "uat-eu"
    And a stream "events" with 250 messages at offsets 0 to 249
    And the page size is 100

  @smoke @fake-broker
  Scenario: Start from the first messages
    When I browse "events" starting from "First"
    Then I see offsets 0 to 99
    And the page says "Offsets 0 to 99 of about 250"

  @smoke @fake-broker
  Scenario: Start from the last page
    When I browse "events" starting from "Last page"
    Then I see offsets 150 to 249

  @fake-broker
  Scenario: Start from a given offset
    When I browse "events" starting from offset 120
    Then I see offsets 120 to 219

  @fake-broker
  Scenario: Start from a point in time
    Given the message at offset 100 was published at "2026-11-03 09:00:00"
    And the message at offset 99 was published at "2026-11-03 08:59:59"
    When I browse "events" starting from the time "2026-11-03 09:00:00"
    Then the first message shown is at offset 100

  @fake-broker
  Scenario: A time after the last message gives an empty page
    Given the last message was published at "2026-11-03 10:00:00"
    When I browse "events" starting from the time "2026-11-04 00:00:00"
    Then I see no messages
    And the page says that no message exists at or after that time

  @smoke @fake-broker
  Scenario: Next starts after the last offset shown
    Given I am browsing "events" and see offsets 0 to 99
    When I choose "Next"
    Then I see offsets 100 to 199

  @fake-broker
  Scenario Outline: Previous goes back one page, but never before the first offset
    Given I am browsing "events" and see offsets starting at <shown first>
    When I choose "Previous"
    Then I see offsets starting at <new first>

    Examples:
      | shown first | new first |
      | 200         | 100       |
      | 100         | 0         |
      | 50          | 0         |

  @fake-broker
  Scenario: Next is disabled at the end of the stream
    Given the stream "events" has 250 messages
    When I browse "events" starting from offset 200
    Then I see offsets 200 to 249
    And "Next" is disabled

  @fake-broker
  Scenario: Previous is disabled at the start of the stream
    When I browse "events" starting from "First"
    Then "Previous" is disabled

  @fake-broker
  Scenario: Jump to an offset while browsing
    Given I am browsing "events" and see offsets 0 to 99
    When I jump to offset 180
    Then I see offsets 180 to 249

  @negative @fake-broker
  Scenario Outline: An offset outside the stream is handled
    When I browse "events" starting from offset <offset>
    Then the app tells me the offset is outside the range 0 to 249
    And it offers to jump to the nearest valid offset

    Examples:
      | offset |
      | 250    |
      | 9999   |

  @fake-broker
  Scenario Outline: Change the page size
    Given I am browsing "events" from "First"
    When I set the page size to <size>
    Then I see <shown> messages

    Examples:
      | size | shown |
      | 25   | 25    |
      | 100  | 100   |
      | 500  | 250   |

  @fake-broker
  Scenario: Delivery starts earlier than asked
    Given the broker delivers a chunk that begins at offset 90
    When I browse "events" starting from offset 100
    Then the messages at offsets 90 to 99 are not shown
    And the first message shown is at offset 100

  @fake-broker
  Scenario: Retention moves the first offset while paging
    Given I am browsing "events" and see offsets 100 to 199
    And retention removes the messages before offset 150
    When I choose "Previous"
    Then the app shows a notice that the stream start moved to offset 150
    And I see offsets starting at 150
    And nothing fails

  @fake-broker
  Scenario: A page ends early when no more messages arrive
    Given the stream "events" has 40 messages at offsets 0 to 39
    When I browse "events" starting from "First"
    Then I see offsets 0 to 39
    And the page does not wait longer than the idle timeout

  @fake-broker
  Scenario: An empty stream
    Given a stream "empty-stream" with no messages
    When I browse "empty-stream" starting from "First"
    Then I see a message that the stream has no messages yet

  @broker @safety
  Scenario: Browsing never changes any consumer's stored offset
    Given a real broker with a stream "events" and a named consumer "billing" at offset 50
    When I browse "events" starting from "First" and then from "Last page"
    Then the stored offset of "billing" is still 50
    And no new consumer name appears on the stream

  @broker
  Scenario: Read real messages in order
    Given a real broker where "events" holds 250 messages published in order
    When I browse "events" starting from "First" and choose "Next" twice
    Then I see offsets 0 to 99, then 100 to 199, then 200 to 249 in order
