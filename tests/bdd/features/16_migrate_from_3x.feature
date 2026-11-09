@M8 @migrate-3x
Feature: Migrate from RabbitMQ 3.x to 4.x
  As an engineer who runs RabbitMQ 3.x brokers and has to move to 4.x
  I want to see what in my 3.x topology does not work on 4.x, fix it item by item,
  and carry the messages of my old queues over
  So that the move is reviewed, repeatable and loses nothing by accident

  RabbitMQ 3.x is a read-only source: the app only reads from it. The fixes are written as an
  ordinary migration (see 10_topology_migrations.feature). Messages are moved by a Shovel that runs
  inside the 4.x broker; the app itself never consumes, acknowledges, deletes or purges queue messages.
  Rules marked @needs-spike are written with the proposed behaviour and depend on what the M8-00 spike
  measures on real 3.13 and 4.x brokers (docs/tasks/M8-00-spike-live-3x-vs-4x.md).

  # ---- Sources ---------------------------------------------------------------------------------

  @smoke @fake-broker
  Scenario: Analyse a definitions file exported from RabbitMQ 3.x
    Given a definitions file exported from RabbitMQ 3.13 with 3 queues, 2 exchanges and 4 bindings in "/"
    When I analyse the file
    Then I see a compatibility report for "/"
    And the report says how many items are compatible as they are and how many need a decision

  @fake-broker
  Scenario: Analyse a live RabbitMQ 3.x connection
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the user may export definitions
    When I analyse "legacy-eu"
    Then the report is built from the definitions the broker returns
    And the report names the source as "legacy-eu" with version 3.13

  @negative @fake-broker @needs-spike
  Scenario: A user who may not export definitions is offered the file import
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker refuses the definitions export for this user
    When I analyse "legacy-eu"
    Then I am told that this user cannot export definitions
    And I am offered to import a definitions file instead

  @negative @fake-broker
  Scenario Outline: A file that is not a definitions export is rejected
    Given a file whose content is <content>
    When I analyse the file
    Then the file is rejected with the reason "<reason>"
    And no report is shown

    Examples:
      | content                          | reason                            |
      | not JSON at all                  | The file is not valid JSON        |
      | an empty JSON object             | The file has no definitions       |
      | a JSON array                     | The file is not a definitions file|

  @security @fake-broker
  Scenario: Secrets in a 3.x export never reach the report
    Given a definitions file with a user "svc" that has a password hash
    And a shovel parameter whose URI contains a password
    When I analyse the file
    Then neither the password hash nor the password appears in the report, the logs or the exported report
    And the user "svc" is listed with its tags and hashing algorithm but without its hash

  # ---- RabbitMQ 3.x is a read-only source --------------------------------------------------------

  @fake-broker @negative
  Scenario: A RabbitMQ 3.x connection is a migration source only
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    When I connect to "legacy-eu"
    Then the connection says "RabbitMQ 3.x: migration source only"
    And topology changes are locked whatever the user's level and permissions are
    And test publishing is disabled
    And the only offered action is to migrate from 3.x

  @safety @fake-broker
  Scenario: Analysing a live 3.x broker sends only read requests
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    When I analyse "legacy-eu"
    Then every request sent to "legacy-eu" is a read request
    And nothing on "legacy-eu" has changed

  @fake-broker
  Scenario: The analysis survives switching to the 4.x connection
    Given I analysed "legacy-eu" and the report is shown
    When I connect to "new-eu", a broker running RabbitMQ 4
    Then the report is still available
    And only "new-eu" is the held connection

  # ---- Compatibility report ----------------------------------------------------------------------

  @fake-broker
  Scenario: Every finding says what, how serious, why and what to do on 4.x
    Given a definitions file with a classic queue "orders" that is mirrored by a policy
    When I analyse the file
    Then every finding shows the severity, the item, a reason and a proposed 4.x equivalent
    And the severity is one of blocker, warning or info

  @fake-broker
  Scenario: Mirroring settings of a policy are reported as having no effect
    Given a definitions file with the policy "ha-all" that sets "ha-mode" and "ha-sync-mode" on "^orders"
    When I analyse the file
    Then there is a warning for "ha-all" that mirroring is removed in RabbitMQ 4.0
    And the proposal is to drop the mirroring keys and to move the matching queues to quorum queues

  @fake-broker
  Scenario: A mirrored classic queue is proposed as a quorum queue
    Given a definitions file with the classic queue "orders" matched by a mirroring policy
    When I analyse the file
    Then the report proposes to declare "orders" as a quorum queue
    And the report says classic queues are no longer replicated on 4.x

  @fake-broker @needs-spike
  Scenario Outline: Queue settings that a quorum queue does not accept are reported
    Given a definitions file with a queue "legacy" with <setting>
    When I analyse the file
    Then there is a <severity> for "legacy" about <setting>
    And the proposal is "<proposal>"

    Examples:
      | setting                         | severity | proposal                                   |
      | exclusive set                   | blocker  | keep it classic or make it a normal queue   |
      | durable set to false            | blocker  | make it durable                             |
      | x-max-priority 5                | warning  | review the priority behaviour of quorum     |
      | x-queue-mode lazy               | warning  | drop the argument                           |
      | x-queue-master-locator          | warning  | drop the argument                           |
      | x-max-length-bytes 1048576      | info     | keep the argument if quorum accepts it      |

  @fake-broker @needs-spike
  Scenario: Switching a classic queue to quorum is reported when behaviour changes
    Given a definitions file with a classic queue "jobs"
    When I analyse the file
    Then the report warns about each difference the spike confirmed between classic and quorum queues
    And a difference that the spike could not confirm is shown as "unverified"

  @fake-broker
  Scenario: Items that need no change are not listed as problems
    Given a definitions file with the quorum queue "events", the exchange "events.x" and a binding between them
    When I analyse the file
    Then there is no blocker and no warning
    And the report says all 3 items are compatible as they are

  @fake-broker
  Scenario: Users, virtual hosts and permissions become proposals for the access migration
    Given a definitions file with the virtual host "/orders", the user "svc" and its permissions
    When I analyse the file
    Then the report proposes to declare the virtual host, the user and its permissions on the 4.x broker
    And these proposals belong to the access migration and not to a topology migration

  @fake-broker
  Scenario: Parameters the app does not migrate are listed as not covered
    Given a definitions file with a federation upstream and a static policy for federation
    When I analyse the file
    Then the report lists them as not covered by the migration

  @fake-broker
  Scenario: Export the report
    Given the report for "legacy-eu" is shown
    When I export the report
    Then I get a JSON file with the schema "caerbannog.compat-report/1"
    And every finding in it has a severity, a rule id, an item and a proposal
    And it contains no password, no password hash and no shovel URI

  # ---- Review and the generated migration --------------------------------------------------------

  @smoke @fake-broker
  Scenario: Accept a proposal and generate a migration
    Given the report proposes to declare "orders" as a quorum queue
    When I accept that proposal
    And I generate the migration for "/"
    Then I get a migration file that declares the quorum queue "orders"

  @fake-broker
  Scenario: Change a proposal before accepting it
    Given the report proposes to declare "orders" as a quorum queue
    When I change the proposal to add the argument "x-delivery-limit" with the value 5
    And I accept it
    And I generate the migration for "/"
    Then the declared queue "orders" carries "x-delivery-limit" 5

  @fake-broker
  Scenario: A skipped item stays out of the migration and in the report
    Given the report proposes to declare "orders" as a quorum queue
    When I skip that item
    And I generate the migration for "/"
    Then the migration does not declare "orders"
    And the report shows "orders" as skipped

  @negative @fake-broker
  Scenario: The migration cannot be generated while an item is undecided
    Given the report has 3 findings and I decided only 2
    When I try to generate the migration
    Then I am told that 1 item still needs a decision
    And no file is written

  @negative @fake-broker
  Scenario: A finding without a proposal needs a manual decision
    Given a finding that has no proposed 4.x equivalent
    When I review it
    Then I can only skip it or write my own action
    And I cannot accept a proposal

  @fake-broker
  Scenario: The generated migration is an ordinary migration file
    Given I accepted every proposal for "/"
    When I generate the migration for "/"
    Then the file has the next free version number
    And the file is valid for the migrations screen
    And a dry run of it shows each action and whether it is allowed

  @fake-broker
  Scenario: One migration is generated per source virtual host
    Given the report covers the virtual hosts "/" and "/orders"
    When I generate the migrations
    Then I get one migration for "/" and one for "/orders"
    And each can only be applied on a connection whose virtual host matches

  @negative @fake-broker
  Scenario: Applying a generated migration follows the usual guardrails
    Given a generated migration and the user has no configure permission on the 4.x connection
    When I open the migrations screen
    Then "Apply pending" is disabled
    And I am told that the user cannot change topology

  @fake-broker
  Scenario: Applying a generated migration twice changes nothing the second time
    Given a generated migration that declares the quorum queue "orders"
    And it was applied on "new-eu"
    When I apply it again on "new-eu"
    Then the broker topology is unchanged

  @negative @fake-broker
  Scenario: A migration file cannot contain a shovel action
    Given a migration file with the action "declareShovel"
    When I import it
    Then it is rejected because the action is unknown

  # ---- MCP -----------------------------------------------------------------------------------------

  @mcp @fake-broker
  Scenario: The MCP report tool is read-only and returns no secrets
    When an agent calls "caerbannog_analyze_3x_definitions" for "legacy-eu"
    Then it returns the compatibility report
    And only read requests were sent to "legacy-eu"
    And the output contains no password, no password hash and no shovel URI

  @mcp @safety @security @fake-broker
  Scenario: No MCP tool returns a password hash
    Given a definitions file with a user "svc" that has a password hash
    When an agent calls "caerbannog_analyze_3x_definitions" for that file
    Then the output does not contain the hash
    And no other tool returns a hash for a generated or imported migration

  @mcp @safety @fake-broker
  Scenario: No MCP tool can create or remove a shovel
    When an agent lists the available tools
    Then there is no tool that creates or removes a shovel
    And there is no tool that converts or forwards on its own

  @mcp @fake-broker
  Scenario: The MCP shovel status tool shows no address
    Given a shovel "caerbannog-migrate-orders" is running on "new-eu"
    When an agent calls "caerbannog_list_shovels" for "new-eu"
    Then it returns the name, state and message counts
    And it returns no URI

  # ---- Real brokers --------------------------------------------------------------------------------

  @broker-3x
  Scenario: A real RabbitMQ 3.13 export is read and analysed
    Given a real RabbitMQ 3.13 broker with a mirrored classic queue, a priority queue and a binding
    When I analyse it live
    Then the report lists the mirroring policy and both queues with their proposals

  @gate-M8 @broker-3x
  Scenario: Migrate a 3.13 topology to 4.x and move a message
    Given a real RabbitMQ 3.13 broker with the classic queue "orders" holding the message "hello" and a mirroring policy
    And the 3.13 broker has the user "svc" with a known password
    And a real RabbitMQ 4 broker with the shovel plugins enabled
    When I analyse the 3.13 broker, accept every proposal and apply the generated migration to the 4 broker
    And I confirm forwarding "orders" and the shovel runs to the end
    Then the quorum queue "orders" on the 4 broker holds the message "hello"
    And the user "svc" can log in on the 4 broker with the old password
    And the queue "orders" still exists on the 3.13 broker
    And no password, no password hash and no shovel URI appears in any log of the app
