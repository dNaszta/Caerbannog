@M8 @migrate-3x @access
Feature: Migrate virtual hosts, users and permissions from RabbitMQ 3.x
  As an engineer who moves from RabbitMQ 3.x to 4.x
  I want the virtual hosts, users and permissions of the old broker in the migration
  So that the new broker has the same access without anyone retyping passwords

  Owner decision 2026-11-09 (P2): they are part of the generated migration. The password hash of a user
  is passed through from the export into the migration file; the app never computes, stores or shows it.
  Access actions need an administrator and the level Full control. Decisions P10 to P13 were
  accepted by the owner on 2026-11-09; scenarios marked @needs-spike depend on measurements not yet taken (M8-13).
  See docs/spec.md "Users, virtual hosts and permissions in the migration".

  Background:
    Given the 4.x connection "new-eu" is the held connection
    And a definitions file exported from RabbitMQ 3.13 with the virtual host "/orders", the user "svc" with tags "management" and permissions on "/orders"

  @smoke @fake-broker @m8-12b
  Scenario: The access migration declares vhosts, users and permissions in order
    Given I accepted every proposal
    When I generate the migrations
    Then there is an access migration with the actions in the order: declare virtual host, declare user, set permissions, set topic permissions
    And it has a lower version than the topology migrations

  @security @fake-broker @m8-12b
  Scenario: The review and the report show a user without the hash
    When I review the proposal for the user "svc"
    Then I see the name, the tags, the hashing algorithm and "password hash hidden"
    And the hash is shown nowhere on the screen

  @security @fake-broker @m8-12a
  Scenario: A dry run never shows a hash
    Given the access migration is generated
    When I run a dry run of it
    Then I see "Declare user svc" for each user
    And no hash is shown

  @security @safety @fake-broker @m8-12a
  Scenario: No hash appears in any log, report text, error string or MCP output
    Given I accepted every proposal and applied the access migration, and one action fails with an error
    Then the hash appears in no log, no report, no exported report, no error text, no history entry and no MCP output

  @security @fake-broker @m8-12b
  Scenario: Saving a migration file that holds hashes warns the user
    When I save or export the access migration
    Then I am warned that the file contains password hashes and must be kept secret
    And the file is readable by its owner only where the system allows it

  @negative @fake-broker @m8-12a
  Scenario: Access actions are disabled without the administrator tag
    Given the user of "new-eu" has no administrator tag
    When I open the access migration
    Then "Apply pending" is disabled
    And I am told that creating virtual hosts, users and permissions needs the administrator tag

  @negative @fake-broker @m8-12a
  Scenario: Access actions are disabled below Full control
    Given the user of "new-eu" is an administrator and the level is Declare and edit
    When I open the access migration
    Then "Apply pending" is disabled
    And I am told that this needs the level Full control

  @fake-broker @m8-12b
  Scenario: Access needs the full export of the source
    Given a live 3.x connection whose user is an administrator
    When I analyse it
    Then the users, virtual hosts and permissions come from the full export
    And not from a per-virtual-host export

  @fake-broker @m8-12a
  Scenario: The access migration applies from an administrator profile of any virtual host
    Given the connection "new-eu" has the virtual host "/"
    And the virtual host "/orders" does not exist on the 4.x broker
    When I apply the access migration on "new-eu"
    Then the virtual host "/orders" is created
    And the single-virtual-host rule is not applied to these actions

  @fake-broker @m8-12a
  Scenario: Applying the access migration twice changes nothing the second time
    Given the access migration was applied on "new-eu"
    When I apply it again
    Then nothing on the broker changes
    And it is recorded as applied

  @negative @fake-broker @m8-12a
  Scenario: A user that exists with a different password hash is a conflict
    Given the user "svc" already exists on "new-eu" with another password hash
    When I apply the access migration
    Then the apply stops with a conflict for "svc"
    And the password hash of "svc" is unchanged
    And the message names what differs without showing any hash

  @negative @fake-broker @m8-12a
  Scenario: Permissions that exist with other patterns are a conflict
    Given "svc" has other permissions on "/orders" on "new-eu"
    When I apply the access migration
    Then the apply stops with a conflict and the permissions are unchanged

  @fake-broker @m8-03
  Scenario: guest is proposed for skipping
    Given the export contains the user "guest" with the default password hash
    When I analyse the file
    Then I see a warning that "guest" has a publicly known password
    And the proposal for "guest" is to skip it
    And accepting it asks for an explicit confirmation

  @negative @fake-broker @m8-03
  Scenario Outline: A user whose hash algorithm 4.x does not accept is a manual step
    Given the export contains a user whose hashing algorithm is "<algorithm>"
    When I analyse the file
    Then there is a blocker for that user
    And the proposal is to set a new password on the broker outside the app
    And the app offers no field to type a password

    Examples:
      | algorithm                       |
      | rabbit_password_hashing_bcrypt  |
      | a_name_nobody_knows             |

  @fake-broker @m8-12b
  Scenario Outline: Hash algorithms of the allow-list are carried over
    Given the export contains a user whose hashing algorithm is "<algorithm>"
    When I generate the access migration
    Then the user is declared with its hash and the algorithm "<algorithm>"

    Examples:
      | algorithm                         |
      | rabbit_password_hashing_sha256    |
      | rabbit_password_hashing_sha512    |
      | rabbit_password_hashing_md5       |

  @fake-broker @m8-03
  Scenario: The user is always declared with the hashing algorithm
    Given the export contains a user with a hash and no hashing algorithm
    When I analyse the file
    Then the user is reported as a blocker because without the algorithm the login would fail

  @security @fake-broker @m8-12c
  Scenario: A different hash is never overwritten, the comparison reads the target definitions
    Given the user "svc" exists on "new-eu" with another password hash
    When I apply the access migration
    Then the app compared the hashes before writing
    And the apply stops with a conflict and the existing hash is unchanged
    And a shovel password that the target definitions contain is not kept anywhere

  @fake-broker @m8-03
  Scenario: A user without a password hash is reported
    Given the export contains a user with no password hash
    When I analyse the file
    Then the user is reported as info and the app does not invent a password

  @fake-broker @m8-03
  Scenario: Administrator tags are called out
    Given the export contains a user with the tag "administrator"
    When I analyse the file
    Then there is a warning that the migration gives this user the administrator tag on 4.x

  @fake-broker @m8-12a
  Scenario: The migration never deletes a virtual host, user or permission
    Given I accepted every proposal
    When I generate the migrations
    Then no action deletes a virtual host, a user or a permission

  @negative @mcp @safety @fake-broker @m8-12a
  Scenario: No MCP tool applies a migration, so access actions can only be applied in the app
    Given a migration with a "declareUser" action
    When an agent lists the available tools
    Then there is no tool that applies a migration
    And the dry run tool lists the action as "Declare user" without any hash

  @broker-3x @m8-12c
  Scenario: A real hash from RabbitMQ 3.13 lets the user log in on 4.x
    Given a real RabbitMQ 3.13 broker with the user "svc" and a known password
    And a real RabbitMQ 4 broker
    When the access migration generated from the 3.13 definitions is applied to the 4 broker
    Then "svc" can authenticate on the 4 broker with the old password

  @fake-broker @m8-12b
  Scenario: guest and users that need a manual password are not generated and are listed
    Given I accepted every proposal
    And the export contains the user "guest" and a user "old" whose hashing algorithm is "rabbit_password_hashing_bcrypt"
    When I generate the migrations
    Then the access migration declares neither "guest" nor "old"
    And the result lists "guest" and "old" as not migrated, with "set a new password on the broker" for "old"

  @fake-broker @m8-12b
  Scenario: Permissions of users that are not declared are left out and listed
    Given I accepted every proposal
    And the export contains the user "guest" with permissions on "/orders"
    When I generate the migrations
    Then the access migration has no permission action for "guest"
    And the result lists the left out permissions of "guest"

  @negative @fake-broker @m8-12a
  Scenario: A virtual host that exists with another description or tags is a conflict
    Given the export gives the virtual host "/orders" the description "Orders" and the tag "prod"
    And the virtual host "/orders" already exists on "new-eu" with the description "Something else"
    When I apply the access migration
    Then the apply stops with a conflict for the virtual host "/orders"
    And the message names the description as what differs
    And the virtual host "/orders" is unchanged

  @fake-broker @m8-12a
  Scenario: Settings the export does not carry are not compared
    Given the export gives the virtual host "/orders" no description, no tags and no default queue type
    And the virtual host "/orders" already exists on "new-eu" with the description "Orders" and the tag "prod"
    When I apply the access migration
    Then the apply continues and the virtual host "/orders" is unchanged

  @fake-broker @m8-12a
  Scenario: The default virtual host is never created or compared
    Given the export gives the virtual host "/" the description "Production"
    And "new-eu" has the default virtual host "/" with its own description
    When I apply the access migration
    Then the apply continues and the virtual host "/" is unchanged

  @fake-broker @m8-12b
  Scenario: The default virtual host is not declared by the access migration
    Given the export has the virtual hosts "/" and "/orders"
    And I accepted every proposal
    When I generate the migrations
    Then the access migration declares the virtual host "/orders" and not "/"
    And the result notes that the default virtual host already exists on 4.x and is not changed

