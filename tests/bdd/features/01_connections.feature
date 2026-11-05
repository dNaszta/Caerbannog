@M1 @connections
Feature: Connection profiles
  As an engineer who works with several RabbitMQ brokers
  I want to save each broker as a connection profile
  So that I can switch between environments without retyping settings

  Each connection is its own environment. There is no separate "environment type".
  TLS is a switch on the connection, on by default (M2-14, replaces the old "TLS is always on" rule).
  Default ports with TLS on: stream protocol 5551, management API over HTTPS 15671.
  Default ports with TLS off: stream protocol 5552, management API over plain HTTP 15672.
  The ports follow the switch only while they are still the default of the old mode.
  A connection with TLS off is not encrypted: the app says so wherever the connection is shown (see 13).

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

  Scenario: A new connection can be created with a chosen topology control level
    When I add a connection with these settings
      | name                   | uat-eu                 |
      | host                   | rabbit-uat.example.com |
      | virtual host           | /                      |
      | username               | svc-explorer           |
      | password               | s3cret                 |
      | topology control level | Full control           |
    Then its topology control level is "Full control"

  Scenario: Editing a connection without touching the level keeps it
    Given a saved connection "uat-eu" at topology control level "Full control"
    When I change the host of "uat-eu" to "rabbit-new.example.com" and save
    Then its topology control level is "Full control"

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

  @security
  Scenario: A new connection has TLS on
    When I add a connection with these settings
      | name          | uat-eu                 |
      | host          | rabbit-uat.example.com |
      | virtual host  | /                      |
      | username      | svc-explorer           |
      | password      | s3cret                 |
    Then its TLS switch is on
    And its stream port is 5551
    And its management port is 15671

  @negative @security @fake-broker
  Scenario: A TLS connection to a plain broker is refused with a hint
    Given a saved connection "plain-broker" with TLS on
    And the broker only offers plain connections
    When I check the connection "plain-broker"
    Then the connection is refused
    And the message says the broker may not use TLS
    And the message says the switch can be turned off in the connection settings
    And no attempt without TLS was made

  @fake-broker
  Scenario: A connection can be saved with TLS off
    When I add a connection with these settings
      | name          | dev-local |
      | host          | localhost |
      | virtual host  | /         |
      | username      | admin     |
      | password      | s3cret    |
      | tls           | off       |
    Then its TLS switch is off
    And its stream port is 5552
    And its management port is 15672

  Scenario Outline: Turning TLS off changes only the ports that are still the defaults
    Given a saved connection "uat-eu" with TLS on, stream port <stream> and management port <management>
    When I turn TLS off for "uat-eu" and save
    Then its TLS switch is off
    And its stream port is <new stream>
    And its management port is <new management>

    Examples:
      | stream | management | new stream | new management |
      | 5551   | 15671      | 5552       | 15672          |
      | 5600   | 15671      | 5600       | 15672          |
      | 5551   | 15700      | 5552       | 15700          |
      | 5600   | 15700      | 5600       | 15700          |

  Scenario Outline: Turning TLS on changes only the ports that are still the defaults
    Given a saved connection "dev-local" with TLS off, stream port <stream> and management port <management>
    When I turn TLS on for "dev-local" and save
    Then its TLS switch is on
    And its stream port is <new stream>
    And its management port is <new management>

    Examples:
      | stream | management | new stream | new management |
      | 5552   | 15672      | 5551       | 15671          |
      | 5600   | 15672      | 5600       | 15671          |
      | 5552   | 15700      | 5551       | 15700          |
      | 5600   | 15700      | 5600       | 15700          |

  Scenario: Editing a connection without touching the TLS switch keeps it
    Given a saved connection "dev-local" with TLS off
    When I change the host of "dev-local" to "localhost.localdomain" and save
    Then its TLS switch is off
    And its stream port is 5552

  @fake-broker
  Scenario: Check connection over plain succeeds when both endpoints are reachable
    Given a saved connection "dev-local" with TLS off
    And the broker's stream endpoint and management API are both reachable over plain HTTP
    When I check the connection "dev-local"
    Then the check reports that both endpoints are reachable
    And the message says the connection is not encrypted

  @negative @fake-broker
  Scenario Outline: Check connection names the endpoint that failed over plain
    Given a saved connection "dev-local" with TLS off
    And the <endpoint> is not reachable
    When I check the connection "dev-local"
    Then the check reports that the <endpoint> cannot be reached
    And the other endpoint is still reported as reachable
    And the message does not mention TLS or a certificate

    Examples:
      | endpoint        |
      | stream endpoint |
      | management API  |

  @fake-broker
  Scenario: The CA certificate is ignored when TLS is off
    Given a saved connection "dev-local" with TLS off and the CA certificate path "/no/such/folder/ca.pem"
    And the broker's stream endpoint and management API are both reachable over plain HTTP
    When I change the host of "dev-local" to "localhost.localdomain" and save
    And I check the connection "dev-local"
    Then the check reports that both endpoints are reachable
    And no message about the CA certificate is shown
    And the saved CA certificate path of "dev-local" is still "/no/such/folder/ca.pem"

  @negative @fake-broker
  Scenario: The same missing CA certificate is a problem with TLS on
    Given a saved connection "dev-local" with TLS on and the CA certificate path "/no/such/folder/ca.pem"
    When I check the connection "dev-local"
    Then the check reports that the stream endpoint cannot be reached

  @fake-broker
  Scenario: Use a custom CA certificate
    Given a saved connection "uat-eu"
    And the broker presents a certificate signed by a private CA
    When I set the CA certificate of "uat-eu" to the private CA file
    And I check the connection "uat-eu"
    Then the check reports that both endpoints are reachable

  # M2-14a R2: the form sends the CA path with Save, so a NEW connection keeps it (was a known gap: the path was dropped on add).
  @fake-broker
  Scenario: A new connection keeps the CA certificate typed in the form
    Given the broker presents a certificate signed by a private CA
    When I add a connection "uat-eu" with the private CA file typed in the form
    Then the saved CA certificate path of "uat-eu" is the private CA file
    When I check the connection "uat-eu"
    Then the check reports that both endpoints are reachable

  # The result appears when the user leaves the field (and on Save), not per keystroke, and the file is read off the UI thread:
  # pinned by the Compose tests in ConnectionsScreenUiTest. Here the rule is the validation itself.
  @negative
  Scenario: An unreadable CA certificate file is rejected
    Given I am editing a connection profile
    When I enter the path of a file that is not a valid certificate and leave the field
    Then the file is rejected with a message saying it is not a valid certificate

  @negative @fake-broker
  Scenario Outline: A CA certificate path that is not a readable file is rejected without an error
    Given I am editing a connection profile
    When I type the CA certificate path "<path>" and leave the field
    Then the file is rejected with a message saying the file could not be read

    Examples:
      | path                |
      | /                   |
      | the temp folder     |
      | the temp folder/    |
      | a file that is gone |

  # M2-14d: the file is read off the UI thread, so the answer can arrive after the user renamed or deleted the profile.
  # The late answer is dropped: nothing is saved, no message is shown, nothing fails. The ordering needs a validator that is held
  # until the step releases it (core-testing), so the scenario stays deterministic. The panel-switch case is a unit test.
  @negative @fake-broker
  Scenario Outline: A late CA certificate answer for a profile that was renamed or deleted is dropped
    Given a saved connection "uat-eu"
    And the check of a valid CA certificate file for "uat-eu" is still running
    When the connection "uat-eu" is <change> before the check finishes
    And the check finishes
    Then the late answer is dropped without an error
    And no CA certificate message is shown
    And no saved connection has a CA certificate path

    Examples:
      | change              |
      | renamed to "uat-us" |
      | deleted             |

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

  # Un-skipped in M2-05c (it was @needs-M2, deferred by M1-02 until the stream list existed). Switching means Connect on the other
  # profile in the chip popup; the steps read the app bar model and the stream list view model
  # (acceptance-tests/.../connections/SwitchBetweenConnectionsSteps.kt).
  @fake-broker
  Scenario: Switch between connections
    Given saved connections "uat-eu" and "prod-eu"
    And I am connected to "uat-eu"
    When I switch to "prod-eu"
    Then the app bar shows "prod-eu" as the current connection
    And the stream list shows the streams of "prod-eu"
