@M1 @connections
Feature: Connection profiles
  As an engineer who works with several RabbitMQ brokers
  I want to save each broker as a connection profile
  So that I can switch between environments without retyping settings

  Each connection is its own environment. There is no separate "environment type".
  Default ports: stream protocol over TLS 5551, management API over HTTPS 15671.

  Background:
    Given the app is started with no saved connections

  @smoke
  Scenario: Save a new connection profile
    When I add a connection with these settings
      | name          | uat-eu                 |
      | host          | rabbit-uat.example.com |
      | virtual host  | /                      |
      | username      | svc-explorer           |
      | password      | s3cret                 |
    Then the connection "uat-eu" appears in the saved profiles
    And its stream port is 5551
    And its management port is 15671

  Scenario: A new connection defaults to read-only topology control
    When I add a connection with these settings
      | name          | uat-eu                 |
      | host          | rabbit-uat.example.com |
      | virtual host  | /                      |
      | username      | svc-explorer           |
      | password      | s3cret                 |
    Then its topology control level is "Read-only"

  Scenario: Raise a connection's topology control level
    Given a saved connection "uat-eu"
    When I set the topology control level of "uat-eu" to "Full control"
    Then its topology control level is "Full control"

  Scenario: Connection names must be unique
    Given a saved connection "uat-eu"
    When I try to save another connection named "uat-eu"
    Then the app refuses to save it
    And the message says a connection with that name already exists

  @smoke @fake-broker
  Scenario: Check connection succeeds when both endpoints are reachable
    Given a saved connection "uat-eu"
    And the broker's stream endpoint and management API are both reachable over TLS
    When I check the connection "uat-eu"
    Then the check reports that both endpoints are reachable

  @negative @fake-broker
  Scenario Outline: Check connection names the endpoint that failed
    Given a saved connection "uat-eu"
    And the <endpoint> is not reachable
    When I check the connection "uat-eu"
    Then the check reports that the <endpoint> cannot be reached
    And the other endpoint is still reported as reachable

    Examples:
      | endpoint        |
      | stream endpoint |
      | management API  |

  @negative @fake-broker
  Scenario: The management plugin is not enabled
    Given a saved connection "uat-eu"
    And the broker does not have the "rabbitmq_stream_management" plugin enabled
    When I check the connection "uat-eu"
    Then the check tells me to enable "rabbitmq_stream_management"

  @negative @security
  Scenario: TLS is always on
    Given I am editing a connection profile
    Then there is no option to turn TLS off

  @negative @security @fake-broker
  Scenario: The app refuses a broker that does not offer TLS
    Given a saved connection "plain-broker"
    And the broker only accepts connections without TLS
    When I check the connection "plain-broker"
    Then the connection is refused
    And the message says the app does not connect without TLS

  @fake-broker
  Scenario: Use a custom CA certificate
    Given a saved connection "uat-eu"
    And the broker presents a certificate signed by a private CA
    When I set the CA certificate of "uat-eu" to the private CA file
    And I check the connection "uat-eu"
    Then the check reports that both endpoints are reachable

  @negative
  Scenario: An unreadable CA certificate file is rejected
    Given I am editing a connection profile
    When I choose a CA certificate file that is not a valid certificate
    Then the file is rejected with a message saying it is not a valid certificate

  @security
  Scenario: Passwords are not stored in the profile file
    Given a saved connection "uat-eu" with password "s3cret"
    Then the profile file on disk does not contain "s3cret"
    And the password is kept in the operating system's secret store

  Scenario: Delete a connection profile
    Given a saved connection "uat-eu"
    When I delete the connection "uat-eu" and confirm
    Then the connection "uat-eu" is no longer in the saved profiles

  Scenario: Deleting a profile can be cancelled
    Given a saved connection "uat-eu"
    When I start deleting the connection "uat-eu" and then cancel
    Then the connection "uat-eu" is still in the saved profiles

  Scenario: Switch between connections
    Given saved connections "uat-eu" and "prod-eu"
    And I am connected to "uat-eu"
    When I switch to "prod-eu"
    Then the sidebar shows "prod-eu" as the current connection
    And the stream list shows the streams of "prod-eu"
