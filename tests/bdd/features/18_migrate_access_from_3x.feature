@M8 @migrate-3x @access
Feature: Migrate virtual hosts, users and permissions from RabbitMQ 3.x
  As an engineer who moves from RabbitMQ 3.x to 4.x
  I want the virtual hosts, users and permissions of the old broker in the migration
  So that the new broker has the same access without anyone retyping passwords

  Owner decision 2026-11-09 (P2): they are part of the generated migration. The password hash of a user
  is passed through from the export into the migration file; the app never computes, stores or shows it.
  Access actions need an administrator and the level Full control. Decisions P10 to P13 were
  accepted by the owner on 2026-11-09; scenarios marked @needs-spike depend on measurements of M8-00 (S-M).
  See docs/spec.md "Users, virtual hosts and permissions in the migration".

  Background:
    Given the 4.x connection "new-eu" is the held connection
    And a definitions file exported from RabbitMQ 3.13 with the virtual host "/orders", the user "svc" with tags "management" and permissions on "/orders"

  @smoke @fake-broker
  Scenario: The access migration declares vhosts, users and permissions in order
    Given I accepted every proposal
    When I generate the migrations
    Then there is an access migration with the actions in the order: declare virtual host, declare user, set permissions, set topic permissions
    And it has a lower version than the topology migrations

  @security @fake-broker
  Scenario: The review and the report show a user without the hash
    When I review the proposal for the user "svc"
    Then I see the name, the tags, the hashing algorithm and "password hash hidden"
    And the hash is shown nowhere on the screen

  @security @fake-broker
  Scenario: A dry run never shows a hash
    Given the access migration is generated
    When I run a dry run of it
    Then I see "Declare user svc" for each user
    And no hash is shown

  @security @safety @fake-broker
  Scenario: No hash appears in any log, report text, error string or MCP output
    Given I accepted every proposal and applied the access migration, and one action fails with an error
    Then the hash appears in no log, no report, no exported report, no error text, no history entry and no MCP output

  @security @fake-broker
  Scenario: Saving a migration file that holds hashes warns the user
    When I save or export the access migration
    Then I am warned that the file contains password hashes and must be kept secret
    And the file is readable by its owner only where the system allows it

  @negative @fake-broker
  Scenario: Access actions are disabled without the administrator tag
    Given the user of "new-eu" has no administrator tag
    When I open the access migration
    Then "Apply pending" is disabled
    And I am told that creating virtual hosts, users and permissions needs the administrator tag

  @negative @fake-broker
  Scenario: Access actions are disabled below Full control
    Given the user of "new-eu" is an administrator and the level is Declare and edit
    When I open the access migration
    Then "Apply pending" is disabled
    And I am told that this needs the level Full control

  @fake-broker
  Scenario: The access migration applies from an administrator profile of any virtual host
    Given the connection "new-eu" has the virtual host "/"
    And the virtual host "/orders" does not exist on the 4.x broker
    When I apply the access migration on "new-eu"
    Then the virtual host "/orders" is created
    And the single-virtual-host rule is not applied to these actions

  @fake-broker
  Scenario: Applying the access migration twice changes nothing the second time
    Given the access migration was applied on "new-eu"
    When I apply it again
    Then nothing on the broker changes
    And it is recorded as applied

  @negative @fake-broker
  Scenario: A user that exists with a different password hash is a conflict
    Given the user "svc" already exists on "new-eu" with another password hash
    When I apply the access migration
    Then the apply stops with a conflict for "svc"
    And the password hash of "svc" is unchanged
    And the message names what differs without showing any hash

  @negative @fake-broker
  Scenario: Permissions that exist with other patterns are a conflict
    Given "svc" has other permissions on "/orders" on "new-eu"
    When I apply the access migration
    Then the apply stops with a conflict and the permissions are unchanged

  @fake-broker
  Scenario: guest is proposed for skipping
    Given the export contains the user "guest" with the default password hash
    When I analyse the file
    Then I see a warning that "guest" has a publicly known password
    And the proposal for "guest" is to skip it
    And accepting it asks for an explicit confirmation

  @negative @fake-broker @needs-spike
  Scenario: A user with an unsupported hash algorithm is a manual step
    Given the export contains a user whose hashing algorithm the 4.x broker does not accept
    When I analyse the file
    Then there is a blocker for that user
    And the proposal is to set a new password on the broker outside the app
    And the app offers no field to type a password

  @fake-broker
  Scenario: A user without a password hash is reported
    Given the export contains a user with no password hash
    When I analyse the file
    Then the user is reported as info and the app does not invent a password

  @fake-broker
  Scenario: Administrator tags are called out
    Given the export contains a user with the tag "administrator"
    When I analyse the file
    Then there is a warning that the migration gives this user the administrator tag on 4.x

  @fake-broker
  Scenario: The migration never deletes a virtual host, user or permission
    Given I accepted every proposal
    When I generate the migrations
    Then no action deletes a virtual host, a user or a permission

  @negative @mcp @safety @fake-broker
  Scenario: The MCP apply tools refuse access actions
    Given a migration with a "declareUser" action
    When an agent asks to apply it
    Then it is refused and told to use the app

  @broker-3x
  Scenario: A real hash from RabbitMQ 3.13 lets the user log in on 4.x
    Given a real RabbitMQ 3.13 broker with the user "svc" and a known password
    And a real RabbitMQ 4 broker
    When the access migration generated from the 3.13 definitions is applied to the 4 broker
    Then "svc" can authenticate on the 4 broker with the old password
