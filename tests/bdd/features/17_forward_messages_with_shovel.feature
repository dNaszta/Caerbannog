@M8 @migrate-3x @shovel
Feature: Forward the messages of a RabbitMQ 3.x queue with a Shovel
  As an engineer who moves from RabbitMQ 3.x to 4.x
  I want the broker to carry the messages of an old queue into the new queue
  So that nothing is lost when I switch the clients over

  The app configures a dynamic Shovel on the 4.x broker through the management API; the broker moves the
  messages. The app itself never gets, consumes, acknowledges, deletes or purges queue messages, never
  deletes the source queue, and never writes anything to the 3.x broker. The messages are moved: they
  leave the source queue once the destination confirms. See docs/spec.md "Forwarding messages with a
  dynamic Shovel". Decisions P1 to P9 were taken by the owner on 2026-11-09. Shovel field names and behaviour that the M8-00 spike has not measured are proposals.

  Background:
    Given the 4.x connection "new-eu" is the held connection
    And the user of "new-eu" has the policymaker and monitoring tags and configure permission on "/"
    And the topology level of "new-eu" is Declare and edit
    And "legacy-eu" is a RabbitMQ 3.13 broker with the classic queue "orders" holding 120 messages
    And "new-eu" has the quorum queue "orders" with no messages

  @smoke @fake-broker @m8-07
  Scenario: A dry run shows what forwarding would do and sends nothing
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then I see 120 messages in the source queue
    And I see that the destination queue "orders" exists and is a quorum queue
    And I see that the shovel plugins are enabled and the shovel "caerbannog-migrate-orders" does not exist yet
    And no shovel is created and nothing on either broker changes

  @negative @fake-broker @m8-07
  Scenario: Forwarding is blocked when the destination queue does not exist
    Given "new-eu" has no queue "orders"
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is blocked
    And I am told to apply the migration that declares "orders" first
    And no destination queue is created by the shovel

  @fake-broker @m8-07
  Scenario: A destination that is not a quorum queue gets a warning
    Given the destination queue "orders" on "new-eu" is a classic queue
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then I see a warning that the destination is not a quorum queue

  @safety @fake-broker @m8-07
  Scenario: The confirmation names both queues and the consequences
    When I choose to forward "orders" from "legacy-eu" to "new-eu"
    Then the confirmation names the source "legacy-eu" "/" "orders" and the destination "new-eu" "/" "orders"
    And it says the messages are moved and leave the source queue when the destination confirms
    And it says the source queue is not deleted
    And it says the shovel keeps credentials on "new-eu" until it is removed
    And nothing is sent until I type the source queue name "orders"

  @fake-broker @m8-09
  Scenario: Cancelling the confirmation sends nothing
    Given the confirmation for forwarding "orders" is open
    When I cancel
    Then no shovel is created

  @safety @fake-broker @m8-07
  Scenario: Starting creates one confirmed dynamic shovel on the 4.x broker
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding starts
    Then one shovel "caerbannog-migrate-orders" is created on "new-eu" in "/"
    And its ack mode is "on-confirm"
    And its source queue is "orders" and its destination queue is "orders"
    And nothing is written to "legacy-eu"

  @safety @fake-broker @m8-07
  Scenario: The app itself never reads or settles queue messages while forwarding
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding runs to the end
    Then no request that gets, consumes, acknowledges or purges messages was sent by the app to either broker

  @fake-broker @m8-09
  Scenario: Progress is shown while the broker moves the messages
    Given the forwarding of "orders" is running and 40 of 120 messages were moved
    When the screen refreshes
    Then I see the shovel state and about 40 of 120 messages moved

  @safety @fake-broker @m8-07
  Scenario: Removing the shovel never deletes the source queue
    Given a forwarding of "orders" is running
    And the topology level of "new-eu" is Full control
    When I remove the shovel
    Then the shovel "caerbannog-migrate-orders" is deleted on "new-eu"
    And no queue is deleted on either broker

  @negative @fake-broker @m8-07
  Scenario: Removing a shovel needs Full control
    Given a forwarding of "orders" is running
    When I try to remove the shovel at the level Declare and edit
    Then I am told that removing needs Full control
    And no request is sent to the broker

  @negative @fake-broker @m8-07
  Scenario: A user without the policymaker tag cannot forward
    Given the user of "new-eu" has no policymaker tag
    When I choose to forward "orders" from "legacy-eu" to "new-eu"
    Then forwarding is disabled
    And I am told the user needs the policymaker tag

  @negative @fake-broker @m8-07
  Scenario: A broker without the shovel plugins cannot forward
    Given the shovel plugins are not enabled on "new-eu"
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is blocked because the shovel plugins are off

  @negative @fake-broker @m8-07
  Scenario: Forwarding is blocked when the level is Read-only
    Given the topology level of "new-eu" is Read-only
    When I choose to forward "orders" from "legacy-eu" to "new-eu"
    Then forwarding is disabled because topology changes are locked on "new-eu"
    And no request is sent to either broker

  @negative @fake-broker @m8-07
  Scenario: An existing shovel is never overwritten
    Given a shovel "caerbannog-migrate-orders" already exists on "new-eu"
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then I see its state instead of a new start
    And no shovel is created or replaced

  @security @fake-broker @m8-08
  Scenario: The shovel's credentials are never shown or logged
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding starts and then fails with a broker error that contains the password
    Then the password and the full shovel URI appear nowhere in the screen, the logs, the error text, the history or any exported file
    And the address is shown without a password

  @security @fake-broker @m8-07
  Scenario: A TLS profile never falls back to plain for the shovel
    Given "legacy-eu" and "new-eu" use TLS
    When the forwarding starts
    Then the shovel addresses use "amqps"
    And if the secure endpoint cannot be used the start is refused with the reason
    And no plain "amqp" address is used

  @security @fake-broker @m8-07
  Scenario: A plain profile says it is not encrypted
    Given "legacy-eu" has "Use TLS" turned off
    When I choose to forward "orders" from "legacy-eu" to "new-eu"
    Then the confirmation says "Not encrypted"

  @fake-broker @m8-09
  Scenario: One queue at a time
    When I open the forwarding of "orders"
    Then I can forward only that queue
    And there is no action that forwards every queue

  @negative @fake-broker @m8-07
  Scenario: A shovel that cannot reach the source is reported as failed
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    And the broker accepts the shovel but its state becomes "terminated" with the reason "failed to connect to source"
    When the screen polls the shovel status
    Then I am told the forwarding failed with that reason
    And the shovel is removed so no stored credentials remain

  @fake-broker @m8-09
  Scenario: A finished shovel is gone and messages published meanwhile are reported
    Given the forwarding of "orders" moved the messages that were present when it started
    And 20 more messages were published to the source meanwhile
    When the shovel finishes and removes itself
    Then I see that about 20 messages stayed on the source
    And no shovel and no stored credentials remain on "new-eu"

  @safety @security @fake-broker @m8-07
  Scenario: The confirmation says that administrators of the 4.x broker can read the source password
    When I choose to forward "orders" from "legacy-eu" to "new-eu"
    Then the confirmation says the source password is stored on "new-eu" in clear text
    And it says any administrator of "new-eu" can read it until the shovel finishes or is removed

  @security @fake-broker @m8-08
  Scenario: The app never reads the shovel parameters
    Given a shovel "caerbannog-migrate-orders" exists on "new-eu"
    When I open the forwarding of "orders"
    Then the app read the shovel status only through the shovel status endpoint
    And it never requested the shovel parameter endpoints

  @negative @fake-broker @m8-07
  Scenario: A user who can create shovels but not read their status is told
    Given the user of "new-eu" has the policymaker tag but no monitoring or administrator tag
    When I open the forwarding of "orders"
    Then I am told the user cannot read the shovel status and needs the monitoring tag

  @fake-broker @m8-08
  Scenario: The destination of the shovel is the local broker without credentials
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding starts
    Then the shovel destination carries no user name and no password
    And only the source address has credentials

  @security @fake-broker @m8-09
  Scenario: Use another user for this shovel
    Given I chose to forward "orders" from "legacy-eu" to "new-eu"
    When I choose "Use another user for this Shovel" and type a user name and a password
    Then the shovel source address uses that user instead of the profile user
    And the typed password is masked on screen and is not saved in the profile, the keychain, the history or the logs
    And the password is cleared when the dialog closes

  @fake-broker @m8-07
  Scenario: By default the profile user is used for the shovel source
    When I choose to forward "orders" from "legacy-eu" to "new-eu" and keep the default user
    Then the shovel source address uses the user of the profile "legacy-eu"

  @negative @fake-broker @m8-07
  Scenario: A wrong typed name starts nothing
    Given the confirmation for forwarding "orders" is open
    When I type "order" and confirm
    Then no shovel is created

  @safety @fake-broker @m8-07
  Scenario: A shovel that appeared after the dry run is not overwritten
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    And a shovel "caerbannog-migrate-orders" was created on "new-eu" after the dry run
    When the forwarding starts
    Then the start is refused because the shovel exists
    And no shovel is created or replaced

  @safety @fake-broker @m8-07
  Scenario: Removing a failed shovel does not need Full control
    Given the forwarding of "orders" failed and the topology level of "new-eu" is Declare and edit
    When the app removes the failed shovel
    Then the shovel "caerbannog-migrate-orders" is deleted on "new-eu"
    And no queue is deleted on either broker

  @fake-broker @m8-07
  Scenario: Forwarding goes from started to running to finished
    Given I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding starts
    Then the state is started
    When the shovel is listed as running with 40 forwarded
    Then the state is running with 40 forwarded
    When the shovel is no longer listed
    Then the state is finished

  @security @fake-broker @m8-07
  Scenario: The typed source password never appears in the request text
    Given I chose to forward "orders" from "legacy-eu" to "new-eu"
    When I type the user "mover" and the password "s3cret" for this shovel
    Then the text of the request, of the credentials and of the address does not contain "s3cret"
    And the address reads "amqps://mover@legacy.example:5671/%2F"

  @fake-broker @m8-09
  Scenario: The screen says why forwarding is blocked and that a plain connection is not encrypted
    Given the shovel plugins are not enabled on "new-eu"
    When I open the forwarding of "orders"
    Then I am told which plugins to enable
    And with the level Read-only I am told that topology changes are locked on "new-eu"
    And with "Use TLS" turned off on "legacy-eu" the progress view says "Not encrypted"

  # M8-15 (2026-11-19, owner decision A): since RabbitMQ 4.4.1 the broker refuses the direct destination
  # `amqp://` (measured: HTTP 400 "may not use a URI without a hostname or a username") from a user who is not
  # an administrator. The app keeps the credential-free destination and blocks with a message. The fake
  # destination broker is RabbitMQ 4.3.7 unless a step says otherwise, so the scenarios above are unchanged.

  @negative @safety @fake-broker @m8-15
  Scenario Outline: A policymaker who is not an administrator is blocked on RabbitMQ 4.4.1 and later
    Given the destination broker "new-eu" is RabbitMQ <version>
    And the user of "new-eu" has the policymaker and monitoring tags but not the administrator tag
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is blocked
    And I am told that RabbitMQ 4.4.1 and later lets only an administrator create a shovel that sends to its own broker
    And no shovel is created and nothing is written to either broker

    Examples:
      | version |
      | 4.4.1   |
      | 4.4.2   |
      | 4.5.0   |
      | 5.0.0   |

  @fake-broker @m8-15
  Scenario Outline: A broker before 4.4.1 does not block a policymaker for this reason
    Given the destination broker "new-eu" is RabbitMQ <version>
    And the user of "new-eu" has the policymaker and monitoring tags but not the administrator tag
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is not blocked
    And the destination of the shovel is still the local broker without credentials

    Examples:
      | version |
      | 4.4.0   |
      | 4.3.7   |
      | 4.0.2   |

  @fake-broker @m8-15
  Scenario: An administrator is not blocked on RabbitMQ 4.4.1
    Given the destination broker "new-eu" is RabbitMQ 4.4.1
    And the user of "new-eu" has the administrator tag
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is not blocked

  @negative @safety @fake-broker @m8-15
  Scenario: A broker that hides its version is not blocked in advance and the refusal at the start is told the same way
    Given the version of the destination broker "new-eu" cannot be read
    And the user of "new-eu" has the policymaker and monitoring tags but not the administrator tag
    And the broker will refuse the direct destination
    And I confirmed forwarding "orders" from "legacy-eu" to "new-eu"
    When the forwarding starts
    Then no shovel exists on "new-eu"
    And I am told that RabbitMQ 4.4.1 and later lets only an administrator create a shovel that sends to its own broker
    And the text I see contains no broker text and no credentials

  @security @fake-broker @m8-15
  Scenario: The refusal never makes the app put credentials into the destination
    Given the destination broker "new-eu" is RabbitMQ 4.4.1
    And the user of "new-eu" has the policymaker and monitoring tags but not the administrator tag
    When I run a dry run of forwarding "orders" from "legacy-eu" to "new-eu"
    Then forwarding is blocked
    And no request was sent that names a destination address with a user or a host

  @broker @safety @m8-15
  Scenario: On a real RabbitMQ 4.4.1 the policymaker is blocked and the administrator forwards
    Given a RabbitMQ 4.4.1 broker with the user "pm" (management, policymaker, monitoring) and the user "adm" (administrator)
    When "pm" runs a dry run of forwarding a queue and then tries to start it
    Then forwarding is blocked with the 4.4.1 message and no shovel exists
    And "adm" starts the same forwarding and the messages arrive in the destination queue
