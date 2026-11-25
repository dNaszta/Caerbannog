@M2 @connections
Feature: Connect and disconnect
  As an engineer
  I want to connect to one broker explicitly and disconnect from it
  So that I know which broker the app is talking to and nothing stays open behind my back

  "Connected" means the app holds its connections to the broker until I disconnect.
  Only one connection is held at a time: connecting to another one disconnects the first.
  Connect runs the permission check first (see 02). With TLS on: the certificate authority is honoured,
  the host name is not verified (product decision D7), and the connection panel says so.
  With TLS off (M2-14) the connection is not encrypted and the app says "Not encrypted" in the connection panel,
  the app bar chip (text and accessible label) and the chip popup, in every state of that connection.

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
    And the app holds one connection

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

  # M2-14c. The hint only tells. The app never turns TLS off by itself and never retries without TLS.
  # Which real failures count as "the broker does not answer the TLS handshake" is measured first (brief M2-14c, step 2);
  # a wrong CA must keep its own reason.
  @negative @security @fake-broker
  Scenario: Connecting with TLS on to a plain broker says the broker may not use TLS
    Given a saved connection "plain-broker" with TLS on
    And the broker only offers plain connections
    When I connect to "plain-broker"
    Then the state of "plain-broker" is "Failed"
    And the reason says the broker may not use TLS
    And the reason says the switch can be turned off in the connection settings
    And the app holds no connection to the broker
    And I can retry
    And the saved connection "plain-broker" still has TLS on
    And no attempt without TLS was made
    And no credential was sent

  @negative @security @fake-broker
  Scenario: A certificate that is not trusted does not suggest turning TLS off
    Given the connection "uat-eu" fails because the certificate is not trusted
    When I connect to "uat-eu"
    Then the state of "uat-eu" is "Failed" with the reason "The certificate was not trusted"
    And the reason does not mention turning TLS off

  @fake-broker
  Scenario: A connection with TLS off never shows the TLS hint
    Given the connection "uat-eu" has TLS off
    And the connection "uat-eu" fails because the broker is not reachable
    When I connect to "uat-eu"
    Then the state of "uat-eu" is "Failed" with the reason "The broker could not be reached"
    And the reason does not mention TLS

  @negative @fake-broker
  Scenario: Connecting without a stored password does not open the clients
    Given no password is stored for "uat-eu"
    When I connect to "uat-eu"
    Then the state of "uat-eu" is "Failed" with the reason "No password is stored"
    And the clients were not opened
    And the app holds no connection to the broker

  @fake-broker
  Scenario: A user who cannot read permissions is still connected
    Given the broker does not let the user of "uat-eu" read permissions
    When I connect to "uat-eu"
    Then its state is "Connected"
    And topology is locked (see 02, safest mode)

  # Checked at the catalog level (Capabilities.visibility NONE, every list not authorised); the screen is M2-04/M2-06.
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

  # Product decision D2 (confirmed 2026-11-04): no automatic reconnect in M2. Runs at core level (M2-05b): the stream list
  # view model and the connection control model on the same fakes.
  @fake-broker
  Scenario: A lost connection offers a retry, not a silent reconnect
    Given I am connected to "uat-eu"
    When the broker stops answering
    Then the state of "uat-eu" is "Failed" with the reason "Connection lost"
    And the last data stays visible and is marked stale
    And the app does not reconnect by itself
    When the broker answers again
    And I retry
    Then the state of "uat-eu" is "Connected" once the broker answers

  # M2-16 (2026-11-15). RabbitMQ 4 closes an idle management connection after 5 seconds and the default refresh interval is 5 seconds,
  # so a refresh can land on a connection the broker is just closing. The app asks again at once; that is not a lost connection.
  # A broker that really stops answering is still reported (scenario above), and a change (create, delete, apply a migration)
  # is never sent twice. Live twin: RealBrokerClientsTest "the tab view model on a real broker ..."; deterministic proof without
  # Docker: ManagementHttpClosedConnectionTest.
  @broker @negative
  Scenario: A refresh that meets a connection the broker just closed is not a lost connection
    Given a real broker that closes idle management connections after 5 seconds
    And I am connected to it with the refresh interval at 5 seconds
    When the screen has refreshed several times, each refresh landing as the broker closes the idle connection
    Then the state stays "Connected" and the rows stay live, not stale
    And no refresh reports "Connection lost"

  @fake-broker
  Scenario Outline: The password is never shown, with TLS on or off
    Given the connection "<name>" has TLS <tls>
    And the connection "<name>" fails because the credentials are refused
    When I connect to "<name>"
    Then the failure message does not contain the password

    Examples:
      | name      | tls |
      | uat-eu    | on  |
      | dev-local | off |

  @fake-broker
  Scenario: The connection panel says the host name is not verified
    Given the connection "uat-eu" has TLS on
    When I open the edit panel of "uat-eu"
    Then I see the note "Host name is not verified."

  @fake-broker
  Scenario: The note about host name verification is shown only with TLS on
    Given the connection "dev-local" has TLS off
    When I open the edit panel of "dev-local"
    Then I do not see the note "Host name is not verified."

  @fake-broker
  Scenario Outline: Connecting reaches Connected with TLS on and off
    Given the connection "<name>" has TLS <tls>
    When I connect to "<name>"
    Then its state is "Connected"
    And the connection was opened <mode>

    Examples:
      | name      | tls | mode        |
      | uat-eu    | on  | with TLS    |
      | dev-local | off | without TLS |

  @fake-broker
  Scenario Outline: A connection without TLS says it is not encrypted in every state
    Given the connection "dev-local" has TLS off
    And the connection "dev-local" is in the state "<state>"
    When I open the edit panel of "dev-local"
    Then the edit panel shows the warning "Not encrypted"
    And the app bar chip says "Not encrypted"
    And the chip's accessible label says "Not encrypted"
    And the popup row of "dev-local" says "Not encrypted"

    Examples:
      | state        |
      | Connected    |
      | Failed       |
      | Disconnected |

  @fake-broker
  Scenario: A connection with TLS on says nothing about encryption
    Given the connection "uat-eu" has TLS on
    And the connection "uat-eu" is in the state "Connected"
    When I open the edit panel of "uat-eu"
    Then the edit panel shows no warning
    And the app bar chip does not say "Not encrypted"
    And the chip's accessible label does not say "Not encrypted"
    And the popup row of "uat-eu" does not say "Not encrypted"

  @fake-broker
  Scenario: A plain connection is not encrypted before it is connected
    Given the connection "dev-local" has TLS off
    When I open any screen
    Then the popup row of "dev-local" says "Not encrypted"
    And the popup row of "uat-eu" does not say "Not encrypted"

  # The connection control in the app bar (task M2-05b, docs/ux/Streams.dc.html). The steps drive the core model behind the
  # chip (ConnectionControlModel); that the chip and popup draw it, on every screen, is pinned by the Compose UI tests in `ui`.

  @fake-broker
  Scenario: The app bar always shows the current connection
    Given I am connected to "uat-eu"
    When I open any screen
    Then the app bar shows "uat-eu" with the state "Connected" and its permission pill

  @fake-broker
  Scenario: With no connection the app bar says so
    Given no connection is current
    When I open any screen
    Then the app bar shows "No connection"
    And opening it lists "uat-eu" and "prod-eu" with a Connect action each

  @fake-broker
  Scenario: I connect to another connection from the app bar
    Given I am connected to "uat-eu"
    When I open the connection control and press Connect on "prod-eu"
    Then the state of "uat-eu" is "Disconnected"
    And the app bar shows "prod-eu" with the state "Connected"

  # ---------------------------------------------------------------- M10-10 token expired (owner decision Q4, 2026-11-25)
  # Wording "Token expired" is the owner's. How an expired token is told from another refusal is
  # measured by the spike M10-00 (S-D, S-J).

  @M10 @needs-spike @fake-broker @m10-10
  Scenario: A refused token shows Token expired on the chip and the popup
    Given the connection "prod-eu" holds a token as its password
    When the broker refuses the sign-in of "prod-eu" as an expired token
    Then the chip and the popup row say "Token expired"

  @M10 @needs-spike @fake-broker @m10-10
  Scenario: A refused token is told from a refused password
    Given the connection "uat-eu" holds a password and the broker refuses it
    Then the chip and the popup do not say "Token expired"

  @M10 @fake-broker @m10-10
  Scenario: A connection with Token expired keeps the last data as stale and offers Retry
    Given the connection "prod-eu" showed data
    When the broker refuses the sign-in of "prod-eu" as an expired token
    Then the last data stays on screen marked as stale
    And Retry is offered and no automatic reconnect happens

  @M10 @fake-broker @m10-10
  Scenario: The stream refresh and the Access tabs stop refreshing while the token is expired
    Given the connection "prod-eu" is in the state "Token expired"
    Then no further refresh request is sent

  @M10 @security @fake-broker @m10-10
  Scenario: The token and the password never appear in the chip, the popup or the message
    Given the held password is a JWT that the broker refuses
    When I open the connection chip popup
    Then the token, and any part of it, is shown nowhere

  @M10 @fake-broker @m10-10
  Scenario: Retry after a new password is saved connects again
    Given the connection "prod-eu" is in the state "Token expired"
    And I saved a new password for "prod-eu"
    When I press Retry
    Then "prod-eu" is the current connection
    And its state is "Connected"
