@M2 @connections
Feature: Connect and disconnect
  As an engineer
  I want to connect to one broker explicitly and disconnect from it
  So that I know which broker the app is talking to and nothing stays open behind my back

  "Connected" means the app holds its connections to the broker until I disconnect.
  Only one connection is held at a time: connecting to another one disconnects the first.
  Connect runs the permission check first (see 02). TLS: the certificate authority is honoured,
  the host name is not verified (product decision D7), and the connection panel says so.

  Background:
    Given saved connections "uat-eu" and "prod-eu"

  @smoke @fake-broker
  Scenario: Connect holds the connection
    When I connect to "uat-eu"
    Then "uat-eu" is the current connection
    And its state is "Connected"
    And the permission check has run before the state became "Connected"

  @fake-broker
  Scenario: Connecting shows progress
    Given the broker of "uat-eu" answers slowly
    When I connect to "uat-eu"
    Then its state is "Connecting" until the broker answered

  @fake-broker
  Scenario: Disconnect releases the connection
    Given I am connected to "uat-eu"
    When I disconnect
    Then the state of "uat-eu" is "Disconnected"
    And the app holds no connection to the broker

  @fake-broker
  Scenario: Connecting to another connection disconnects the first
    Given I am connected to "uat-eu"
    When I connect to "prod-eu"
    Then the state of "uat-eu" is "Disconnected"
    And the state of "prod-eu" is "Connected"
    And "prod-eu" is the current connection

  @negative @fake-broker
  Scenario Outline: A connection that cannot be used says why
    Given the connection "uat-eu" fails because <cause>
    When I connect to "uat-eu"
    Then the state of "uat-eu" is "Failed" with the reason "<reason>"
    And the app holds no connection to the broker
    And I can retry

    Examples:
      | cause                          | reason                         |
      | the broker is not reachable    | The broker could not be reached |
      | the certificate is not trusted | The certificate was not trusted |
      | no password is stored          | No password is stored           |
      | the credentials are refused    | Credentials were refused        |

  @fake-broker
  Scenario: A user who cannot read permissions is still connected
    Given the broker does not let the user of "uat-eu" read permissions
    When I connect to "uat-eu"
    Then its state is "Connected"
    And topology is locked (see 02, safest mode)

  @fake-broker
  Scenario: A user without the management tag is connected but sees no lists
    Given the broker answers "Not management user" for the user of "uat-eu"
    When I connect to "uat-eu"
    Then its state is "Connected"
    And the stream and publisher lists are replaced by the message "This user cannot read stream statistics. Ask for the monitoring tag."

  @fake-broker
  Scenario: The stream protocol is unavailable but the connection is usable
    Given the stream port of "uat-eu" does not answer
    When I connect to "uat-eu"
    Then its state is "Connected"
    And first offsets will read "unavailable"

  @fake-broker
  Scenario: Editing the connected profile disconnects it
    Given I am connected to "uat-eu"
    When I save a change to "uat-eu"
    Then the state of "uat-eu" is "Disconnected"
    And I am told "Connection settings changed. Connect again."

  @fake-broker
  Scenario: Deleting the connected profile disconnects it
    Given I am connected to "uat-eu"
    When I delete "uat-eu"
    Then the app holds no connection to the broker
    And there is no current connection

  @fake-broker
  Scenario: Closing the window releases the connection
    Given I am connected to "uat-eu"
    When the window is closed
    Then the app holds no connection to the broker

  # Product decision D2 is proposed and awaits confirmation: no automatic reconnect in M2.
  @fake-broker @needs-decision
  Scenario: A lost connection offers a retry, not a silent reconnect
    Given I am connected to "uat-eu"
    When the broker stops answering
    Then the state of "uat-eu" is "Failed" with the reason "Connection lost"
    And the last data stays visible and is marked stale
    And the app does not reconnect by itself
    When I retry
    Then the state of "uat-eu" is "Connected" once the broker answers

  @fake-broker
  Scenario: The password is never shown
    Given the connection "uat-eu" fails because the credentials are refused
    When I connect to "uat-eu"
    Then the failure message does not contain the password

  @fake-broker
  Scenario: The connection panel says the host name is not verified
    When I open the edit panel of "uat-eu"
    Then I see the note "Host name is not verified."
