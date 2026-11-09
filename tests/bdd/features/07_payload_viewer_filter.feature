@M4 @viewer @filter
Feature: Payload viewer and filter
  As an engineer
  I want to read a message's body and properties in the most useful form
  So that I can understand what was published without leaving the app

  The viewer never assumes UTF-8. Filtering runs in the app over a bounded scan.

  Background:
    Given I am connected to "uat-eu"
    And I am browsing the stream "orders-events"

  @smoke @fake-broker
  Scenario: Show a JSON body pretty-printed
    Given the message at offset 1202 has the JSON body {"orderId":"A-1202","status":"created","items":2}
    When I select the message at offset 1202
    And I choose the "JSON" view
    Then the body is shown indented, one field per line

  @fake-broker
  Scenario: Show the same body as text and as hex
    Given the message at offset 1204 has the text body "heartbeat"
    When I select the message at offset 1204
    Then in the "Text" view I see "heartbeat"
    And in the "Hex" view I see the bytes of "heartbeat"

  @negative @fake-broker
  Scenario: A body that is not valid UTF-8 falls back to hex
    Given the message at offset 1206 has a binary body of 2.1 KB
    When I select the message at offset 1206
    Then the body is shown in the "Hex" view
    And the "Text" view does not show garbled characters

  @negative @fake-broker
  Scenario: A body that claims to be JSON but is not
    Given the message at offset 1210 has content type "application/json" and the body "not json"
    When I select the message at offset 1210
    Then the "JSON" view tells me the body is not valid JSON
    And the "Text" view still shows the body

  @negative @fake-broker
  Scenario: A body nested ten thousand levels deep does not crash the viewer
    Given the message at offset 1211 has content type "application/json" and a body of 10000 nested JSON arrays
    When I select the message at offset 1211
    Then the "JSON" view tells me the body is nested too deeply to format
    And the "Text" view still shows the body
    And the "Hex" view still shows the body
    And the app is still running

  @fake-broker
  Scenario: Very large bodies are capped
    Given the message at offset 1300 has a body larger than the display cap
    When I select the message at offset 1300
    Then only the first part of the body is shown
    And a "Show all" action is offered
    When I choose "Show all"
    Then the whole body is shown

  @fake-broker
  Scenario: Properties and application properties are shown
    Given the message at offset 1202 has
      | content-type | application/json |
      | message-id   | A-1202           |
    And the application properties
      | tenant | eu       |
      | source | checkout |
    When I select the message at offset 1202
    Then I see the timestamp, the properties and the application properties

  @fake-broker
  Scenario Outline: Copy buttons
    Given the message at offset 1202 is selected
    When I choose "<button>"
    Then the clipboard contains <content>

    Examples:
      | button      | content                              |
      | Copy offset | the offset 1202                      |
      | Copy body   | the body exactly as published        |

  @fake-broker
  Scenario: Filter by text within the scan
    Given the next 500 messages contain 3 messages with "A-1202" in the body
    When I filter by the text "A-1202"
    Then only those 3 messages are listed
    And the page says how many messages were scanned

  @fake-broker
  Scenario: Filter by an application property
    Given messages with application property "tenant" set to "eu" and to "us"
    When I filter by application property "tenant" equal to "eu"
    Then only messages with "tenant" equal to "eu" are listed

  @fake-broker
  Scenario: The scan stops at its limit
    Given a stream with 50,000 messages and no match for "needle"
    When I filter by the text "needle"
    Then the scan stops after 10,000 messages
    And the page says the scan limit was reached and the rest was not searched

  @fake-broker
  Scenario: A scan can be cancelled
    Given a long scan is running
    When I choose "Cancel scan"
    Then the scan stops at once
    And the messages found so far stay on screen

  @broker @safety
  Scenario: Filtering is read-only on the broker
    Given a real broker with a stream "orders-events"
    When I filter by the text "A-1202" and then clear the filter
    Then no message was published, deleted or acknowledged on the broker

  @gate-M4 @broker
  Scenario: Large bodies keep the screen responsive
    Given a real broker with a stream holding 100 messages of 1 MB each
    When I browse the stream from "First"
    Then the page appears and I can still scroll and select messages
