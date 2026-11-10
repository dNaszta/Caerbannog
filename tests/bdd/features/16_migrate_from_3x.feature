@M8 @migrate-3x
Feature: Migrate from RabbitMQ 3.x to 4.x
  As an engineer who runs RabbitMQ 3.x brokers and has to move to 4.x
  I want to see what in my 3.x topology does not work on 4.x, fix it item by item,
  and carry the messages of my old queues over
  So that the move is reviewed, repeatable and loses nothing by accident

  RabbitMQ 3.x is a read-only source: the app only reads from it. The fixes are written as an
  ordinary migration (see 10_topology_migrations.feature). Messages are moved by a Shovel that runs
  inside the 4.x broker; the app itself never consumes, acknowledges, deletes or purges queue messages.
  The M8-00 spike measured RabbitMQ 3.13.7 and 4.4.0 (docs/tasks/M8-00-spike-results.md); the rules below
  follow those results. Scenarios marked @needs-spike still wait for a follow-up spike (M8-13).

  # ---- Sources ---------------------------------------------------------------------------------

  @smoke @fake-broker @m8-03
  Scenario: Analyse a definitions file exported from RabbitMQ 3.x
    Given a definitions file exported from RabbitMQ 3.13 with 3 queues, 2 exchanges and 4 bindings in "/"
    When I analyse the file
    Then I see a compatibility report for "/"
    And the report says how many items are compatible as they are and how many need a decision

  @fake-broker @m8-02
  Scenario: Analyse a live RabbitMQ 3.x connection
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the user may export definitions
    When I analyse "legacy-eu"
    Then the report is built from the definitions the broker returns
    And the report names the source as "legacy-eu" with version 3.13

  @negative @fake-broker @m8-02
  Scenario: A user who may not export definitions is offered the file import
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker refuses the definitions export for this user because it is not an administrator
    When I analyse "legacy-eu"
    Then I am told that this user cannot export definitions
    And I am offered to import a definitions file instead

  @negative @fake-broker @m8-01
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

  # M8-01 twins: the model half of the two report scenarios around here. They run in M8-01, before the
  # analyzer (M8-03) exists, so they stop at the parsed document.

  @smoke @fake-broker @m8-01
  Scenario: Read the definitions file exported from RabbitMQ 3.13
    Given the definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    When I read the file
    Then the document comes from RabbitMQ version "3.13.7"
    And the document has 2 virtual hosts, 13 queues, 4 exchanges, 3 bindings and 3 policies
    And the document has 1 operator policy, 4 users, 4 permissions and 1 topic permission
    And the queue "classic-prio" in "/" is a classic queue with the argument "x-max-priority" set to the number 5
    And the queue "quorum-q" in "/" is a quorum queue
    And the queue "classic-transient" in "/" is not durable
    And the user "svc" has the tags "management" and "policymaker" and the algorithm "rabbit_password_hashing_sha256"

  @fake-broker @m8-01
  Scenario: A single virtual host export asks for the virtual host name
    Given the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    When I read the file
    Then I am asked which virtual host name to give the content of this file
    And nothing is analysed until a name is given
    And no report is shown

  @fake-broker @m8-01
  Scenario: Naming the virtual host assigns it to every entry
    Given the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    And I read the file
    When I give the virtual host name "orders"
    Then the queue "v1-q" is in the virtual host "orders"
    And the document has no virtual hosts, no users, no permissions and no topic permissions
    And the document says it comes from a single virtual host export named "orders"

  @negative @fake-broker @m8-01
  Scenario Outline: A virtual host name that cannot be used is refused
    Given the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    And I read the file
    When I give the virtual host name <name>
    Then the name is refused and the file stays unassigned

    Examples:
      | name              |
      | an empty text     |
      | only spaces       |
      | a text with a tab |

  @security @fake-broker @m8-01
  Scenario: The model of a 3.x export holds no secret in a readable form
    Given a definitions file with a user "svc" that has a password hash
    And a shovel parameter whose URI contains a password
    When I read the file
    Then the user "svc" is listed with its tags and hashing algorithm
    And the text form of the document, of the user and of every parameter contains neither the password hash nor the password
    And the shovel parameter is listed by its component and name only

  @security @fake-broker @m8-03
  Scenario: Secrets in a 3.x export never reach the report
    Given a definitions file with a user "svc" that has a password hash
    And a shovel parameter whose URI contains a password
    When I analyse the file
    Then neither the password hash nor the password appears in the report, the logs or the exported report
    And the user "svc" is listed with its tags and hashing algorithm but without its hash

  # ---- RabbitMQ 3.x is a read-only source --------------------------------------------------------

  @fake-broker @negative @m8-02
  Scenario: A RabbitMQ 3.x connection is a migration source only
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    When I connect to "legacy-eu"
    Then the connection says "RabbitMQ 3.x: migration source only"
    And topology changes are locked whatever the user's level and permissions are
    And test publishing is disabled
    And the only offered action is to migrate from 3.x

  @fake-broker @negative @m8-02
  Scenario: A RabbitMQ 3.x connection stays a migration source for a user with every permission
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13 whose user has configure, write and read permission
    And the connection "legacy-eu" is set to the level "Full control"
    When I connect to "legacy-eu"
    Then topology changes are locked whatever the user's level and permissions are
    And test publishing is disabled

  @fake-broker @m8-02
  Scenario: A RabbitMQ 4 connection shows no migration source marking
    Given a connection "new-eu" to a broker running RabbitMQ 4
    When I connect to "new-eu"
    Then the connection does not say "RabbitMQ 3.x: migration source only"
    And the permission pill of "new-eu" is the one the permission check gave

  @fake-broker @m8-02
  Scenario: The broker version is read once when the connection opens
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    When I connect to "legacy-eu" and analyse "legacy-eu"
    Then the broker was asked for its overview once
    And the report names the source as "legacy-eu" with version 3.13

  @negative @fake-broker @m8-02
  Scenario: A broker whose version cannot be read is not analysed as a source
    Given a connection "odd-eu" to a broker whose version cannot be read
    When I analyse "odd-eu"
    Then I am told the broker version could not be read
    And no report is shown
    And the connection "odd-eu" is not called a RabbitMQ 3.x migration source

  @negative @fake-broker @m8-02
  Scenario: A RabbitMQ 4 connection cannot be analysed as a live source
    Given a connection "new-eu" to a broker running RabbitMQ 4
    When I analyse "new-eu"
    Then I am told that only a RabbitMQ 3.x connection can be analysed live
    And no report is shown

  @negative @fake-broker @m8-02
  Scenario: A live 3.x broker that cannot be reached shows no report
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker stops answering during the export
    When I analyse "legacy-eu"
    Then I am told that "legacy-eu" could not be reached
    And no report is shown

  @safety @fake-broker @m8-02
  Scenario: Analysing a live 3.x broker sends only read requests
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    When I analyse "legacy-eu"
    Then every request sent to "legacy-eu" is a read request
    And nothing on "legacy-eu" has changed

  # Runs as the data-rabbit JUnit test LiveThreeXReadsGetOnlyTest (a recording Ktor MockEngine behind the real ManagementHttp); not a Cucumber scenario.
  @safety @m8-02b
  Scenario: Every request the live source reading makes to a 3.x broker is a GET, also when a read fails
    Given a recording stand-in for the management API of a RabbitMQ 3.13 broker
    And the stand-in refuses the deprecated feature list and the second queue page
    When the live source is read
    Then every recorded request has the method GET
    And no recorded path contains "/get", "/contents" or "/purge"

  @fake-broker @m8-06c
  Scenario: The analysis survives switching to the 4.x connection
    Given I analysed "legacy-eu" and the report is shown
    When I connect to "new-eu", a broker running RabbitMQ 4
    Then the report is still available
    And only "new-eu" is the held connection

  # ---- Compatibility report ----------------------------------------------------------------------

  @fake-broker @m8-03
  Scenario: Every finding says what, how serious, why and what to do on 4.x
    Given a definitions file with a classic queue "orders" that is mirrored by a policy
    When I analyse the file
    Then every finding shows the severity, the item, a reason and a proposed 4.x equivalent
    And the severity is one of blocker, warning or info

  @fake-broker @m8-03
  Scenario: Mirroring settings of a policy are reported as rejected on 4.x
    Given a definitions file with the policy "ha-all" that sets "ha-mode" and "ha-sync-mode" on "^orders"
    When I analyse the file
    Then there is a blocker for "ha-all" that RabbitMQ 4 rejects these keys when the policy is created or imported
    And the proposal is to drop the mirroring keys and to move the matching queues to quorum queues

  @fake-broker @m8-03
  Scenario Outline: Every mirroring policy key is a blocker
    Given a definitions file with a policy that sets "<key>"
    When I analyse the file
    Then there is a blocker for that policy

    Examples:
      | key                    |
      | ha-mode                |
      | ha-params              |
      | ha-sync-mode           |
      | ha-sync-batch-size     |
      | ha-promote-on-shutdown |
      | ha-promote-on-failure  |
      | queue-master-locator   |

  @fake-broker @m8-03
  Scenario: A live 3.x broker that uses mirroring is flagged by its deprecated feature list
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker reports the deprecated feature "classic_queue_mirroring" as in use
    When I analyse "legacy-eu"
    Then the report says mirroring is in use on this broker

  @fake-broker @m8-03
  Scenario: A mirrored classic queue is proposed as a quorum queue
    Given a definitions file with the classic queue "orders" matched by a mirroring policy
    When I analyse the file
    Then the report proposes to declare "orders" as a quorum queue
    And the report says classic queues are no longer replicated on 4.x

  @fake-broker @m8-03
  Scenario: A queue entry without a type is a classic queue
    Given a definitions file whose queue "orders" has no "type" and no "x-queue-type" argument
    When I analyse the file
    Then "orders" is treated as a classic queue

  @fake-broker @m8-03
  Scenario Outline: Queue settings that a quorum queue rejects block that conversion
    Given a definitions file with a classic queue "legacy" with <setting>
    When I analyse the file
    Then there is a blocker for converting "legacy" to a quorum queue about <setting>
    And the proposal for that blocker is to remove <setting> when the queue is converted

    # x-queue-master-locator is rejected on classic and on quorum queues alike: it is covered by the
    # next outline, where it is a blocker whatever the target type is.
    Examples:
      | setting                    |
      | x-max-priority 5           |
      | x-queue-mode lazy          |
      | x-queue-version 1          |
      | auto-delete set            |

  @fake-broker @m8-03
  Scenario Outline: Transient queues and master-locator queues are blockers because a 4.x import skips them silently
    Given a definitions file with the queue "legacy" with <setting>
    When I analyse the file
    Then there is a blocker for "legacy" that RabbitMQ 4 does not allow it
    And the report says an import of the raw file would skip it without an error

    Examples:
      | setting                 |
      | durable set to false    |
      | x-queue-master-locator  |

  @fake-broker @m8-03
  Scenario Outline: Settings a quorum queue accepts convert as they are
    Given a definitions file with a classic queue "jobs" with <setting>
    When I analyse the file
    Then converting "jobs" to a quorum queue keeps <setting>

    Examples:
      | setting                         |
      | x-message-ttl 60000             |
      | x-max-length 1000               |
      | x-max-length-bytes 1048576      |
      | x-overflow reject-publish       |
      | x-single-active-consumer true   |
      | x-dead-letter-exchange "dlx"    |

  @fake-broker @m8-03
  Scenario: Converting a classic queue to quorum warns that ordering follows message priority
    Given a definitions file with a classic queue "jobs" without "x-max-priority"
    When I analyse the file
    Then there is a warning for "jobs" that a quorum queue on 4.x orders messages by priority
    And the warning says messages that carry a priority may be delivered in a different order than in a classic queue

  @fake-broker @m8-03
  Scenario: A queue with x-max-priority is proposed to stay classic
    Given a definitions file with a classic queue "prio" with "x-max-priority" 5
    When I analyse the file
    Then the default proposal for "prio" is to keep it a classic queue and to say it is not replicated on 4.x
    And converting to a quorum queue is offered as an alternative with the ordering warning
    And accepting the quorum alternative needs the tick "Ordering by priority may change"

  @fake-broker @m8-03
  Scenario: Classic to quorum differences the spike has not measured are shown as unverified
    Given a definitions file with a classic queue "jobs"
    When I analyse the file
    Then the report names redelivery, the delivery limit and exclusive consumers as "unverified" differences

  @fake-broker @m8-03
  Scenario: A classic queue with the old index is a message-loss blocker
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the classic queue "orders" reports storage version 1
    When I analyse "legacy-eu"
    Then there is a blocker with severity "message loss" for "orders"
    And it says RabbitMQ 4.3 and later cannot read this queue index and an in-place upgrade would silently empty the queue
    And it offers three fixes: set the policy "queue-version" to 2 and restart the node, upgrade through 4.0 to 4.2 first, or migrate to a new 4.x broker and move the messages with a Shovel
    And it recommends the new broker with the Shovel as the safest way

  @fake-broker @m8-03
  Scenario: A classic queue with the new index is not flagged
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the classic queue "events" reports storage version 2
    When I analyse "legacy-eu"
    Then there is no message-loss finding for "events"

  @fake-broker @m8-03
  Scenario: The message-loss findings come first in the report
    Given a live report with a mirroring policy finding and a message-loss blocker
    When I open the report
    Then the message-loss blocker is the first item
    And the report recommends a new 4.x broker with topology migration and a Shovel
    And the policy fix and the upgrade through 4.0 to 4.2 are shown as secondary options done by hand
    And the report never offers an in-place upgrade as a safe option

  @fake-broker @m8-03
  Scenario: A definitions file cannot tell the index version, so every classic queue is warned about
    Given a definitions file with the classic queues "orders" and "events" where "events" has "x-queue-version" 2
    When I analyse the file
    Then there is a message-loss warning for "orders"
    And there is no message-loss warning for "events"
    And the warning says the file does not carry the index version

  @fake-broker @m8-02
  Scenario: The live scan retries a storage version that is not there yet
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker reports no storage version for "orders" until the second read
    When I analyse "legacy-eu"
    Then the storage version of "orders" is read after a retry

  @fake-broker @m8-02
  Scenario: The live scan stops retrying after a fixed number of reads
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker never reports a storage version for "orders"
    When I analyse "legacy-eu"
    Then the storage version of "orders" was read at most 6 times
    And the storage version of "orders" is reported as unknown
    And the report is still built

  @fake-broker @m8-02
  Scenario: Only classic queues are scanned for a storage version
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the export holds the classic queue "orders" and the quorum queue "audit"
    When I analyse "legacy-eu"
    Then the storage version of "orders" was asked for
    And the storage version of "audit" was not asked for

  @fake-broker @m8-02
  Scenario: A deprecated feature list that cannot be read does not fail the analysis
    Given a connection "legacy-eu" to a broker running RabbitMQ 3.13
    And the broker refuses the deprecated feature list
    When I analyse "legacy-eu"
    Then the report is built from the definitions the broker returns
    And the report does not claim that mirroring is in use

  @fake-broker @m8-03
  Scenario: The report says the loss cannot be detected after an upgrade
    Given a message-loss blocker in the report
    When I open its details
    Then it says the management API does not show the loss after the upgrade
    And it says only the broker log shows it

  @fake-broker @m8-03
  Scenario: Client-visible changes are listed as checks, not as definition findings
    Given a definitions file from RabbitMQ 3.13
    When I analyse the file
    Then the report lists "check your clients" items for global QoS, transient queues and AMQP 1.0 address v1

  @fake-broker @m8-03
  Scenario: Operator policies are read from the parameters
    Given a definitions file whose parameters contain an operator policy that sets "queue-mode"
    When I analyse the file
    Then the operator policy is reported and its keys are checked like a policy

  @fake-broker @m8-03
  Scenario: Items that need no change are not listed as problems
    Given a definitions file with the quorum queue "events", the exchange "events.x" and a binding between them
    When I analyse the file
    Then there is no blocker and no warning
    And the report says all 3 items are compatible as they are

  @fake-broker @m8-03
  Scenario: Users, virtual hosts and permissions become proposals for the access migration
    Given a definitions file with the virtual host "/orders", the user "svc" and its permissions
    When I analyse the file
    Then the report proposes to declare the virtual host, the user and its permissions on the 4.x broker
    And these proposals belong to the access migration and not to a topology migration

  @fake-broker @m8-03
  Scenario: Parameters the app does not migrate are listed as not covered
    Given a definitions file with a federation upstream and a static policy for federation
    When I analyse the file
    Then the report lists them as not covered by the migration

  @fake-broker @m8-03
  Scenario: Export the report
    Given the report for "legacy-eu" is shown
    When I export the report
    Then I get a JSON file with the schema "caerbannog.compat-report/1"
    And every finding in it has a severity, a rule id, an item and a proposal
    And it contains no password, no password hash and no shovel URI

  # ---- Review and the generated migration --------------------------------------------------------

  @fake-broker @m8-05
  Scenario: A classic to quorum conversion needs an explicit tick about ordering
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I try to accept that proposal without ticking "Ordering by priority may change"
    Then the proposal is not accepted
    And after I tick it I can accept the proposal

  @fake-broker @m5-08 @m5-08a
  # Owned by M5-08a (the real executor reads back after each action); M8-05 only generates the file.
  Scenario: Applying a generated migration reads back every declared queue
    Given a generated migration that declares the quorum queues "orders" and "jobs"
    And the broker silently does not create "jobs"
    When I apply it
    Then the apply stops and says "jobs" was not found after it was declared
    And the migration is not recorded as applied

  # ---- M8-05: review and generation (pure core; one decision per ITEM, the findings explain it) ------------------------------
  # An item (queue, exchange, binding, policy, operator policy) needs a decision when it has a blocker or a warning that always applies and is
  # not a message-loss, not-covered, client-check or access finding (access is M8-12). Items without such a finding are written as they are.

  @smoke @fake-broker @m8-05
  Scenario: Accept a proposal and generate a migration
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I accept that proposal with the tick "Ordering by priority may change"
    And I generate the migration for "/"
    Then I get a migration file that declares the quorum queue "orders"

  @fake-broker @m8-05
  Scenario: Change a proposal before accepting it
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I replace the proposal by a quorum queue "orders" with the argument "x-delivery-limit" 5
    And I generate the migration for "/"
    Then the declared queue "orders" carries "x-delivery-limit" 5

  @fake-broker @m8-05
  Scenario: A skipped item stays out of the migration and in the report
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I skip that item
    And I generate the migration for "/"
    Then the migration does not declare "orders"
    And the result lists "orders" as skipped

  @negative @fake-broker @m8-05
  Scenario: The migration cannot be generated while an item is undecided
    Given the report has 3 items that need a decision and I decided only 2
    When I try to generate the migration
    Then I am told that 1 item still needs a decision
    And no migration is produced

  @negative @fake-broker @m8-05
  Scenario: An item whose blocker has no automatic fix needs a manual decision
    Given a definitions file with the non-durable queue "temp"
    When I try to accept the default proposal for "temp"
    Then it is refused and I can only skip it, choose the alternative "declare it durable", or replace it with my own action

  @fake-broker @m8-05
  Scenario: A queue with x-max-priority stays classic unless the quorum alternative is chosen with the tick
    Given a definitions file with the classic queue "prio" with "x-max-priority" 5
    When I accept the default proposal for "prio"
    Then the migration declares "prio" as a classic queue with "x-max-priority" 5
    When I choose the quorum alternative without the tick
    Then it is refused
    When I choose the quorum alternative with the tick "Ordering by priority may change"
    Then the migration declares "prio" as a quorum queue without "x-max-priority"

  @fake-broker @m8-05
  Scenario: The migration never sets the queue index version and a message-loss finding does not block it
    Given a definitions file with the classic queue "orders" and no "x-queue-version"
    And the report has a message-loss warning for "orders"
    When I accept the proposal for "orders" with the tick and generate the migration for "/"
    Then the migration declares "orders" without "x-queue-version"
    And the result still lists the message-loss note for "orders" with the recommendation of a new broker and a Shovel

  @fake-broker @m8-05
  Scenario: A policy that only holds mirroring keys is not written
    Given a definitions file with the policy "ha-all" that only sets "ha-mode" and "ha-sync-mode"
    When I accept the proposal for "ha-all" and generate the migration for "/"
    Then the migration does not declare "ha-all"
    And the result says the policy held only keys that 4.x rejects

  @fake-broker @m8-05
  Scenario: A policy with other keys is written without the rejected ones
    Given a definitions file with the policy "limits" that sets "ha-mode" and "max-length" 1000
    When I accept the proposal for "limits" and generate the migration for "/"
    Then the migration declares "limits" with "max-length" 1000 and without "ha-mode"

  @fake-broker @m8-05
  Scenario: Bindings of a skipped item are left out and listed
    Given a definitions file with the queue "orders", the exchange "events" and a binding between them
    When I skip "orders" and generate the migration for "/"
    Then the migration declares "events" and no binding
    And the result lists the binding as left out because "orders" is skipped

  @negative @fake-broker @m8-05
  Scenario: A name that cannot be written into a migration is reported
    Given a definitions file with a queue named ".."
    When I generate the migration for "/"
    Then the result lists the queue as left out because its name cannot be written
    And nothing else is lost silently

  @fake-broker @m8-05
  Scenario: Actions are written in a safe order
    Given a definitions file with a binding, a policy, a queue and an exchange that are all compatible
    When I generate the migration for "/"
    Then the actions are in the order exchange, queue, policy, binding

  @fake-broker @m8-05
  Scenario: The generated migration is an ordinary migration file
    Given I accepted every proposal for "/"
    When I generate the migration for "/"
    Then the file has the next free version number
    And the file is valid for the migrations screen
    And a dry run of it shows each action and whether it is allowed
    And the file contains no delete action

  @fake-broker @m8-05
  Scenario: One migration is generated per source virtual host
    Given the report covers the virtual hosts "/" and "/orders"
    When I generate the migrations
    Then I get one migration for "/" and one for "/orders"
    And each can only be applied on a connection whose virtual host matches

  @fake-broker @m8-05
  Scenario: A single virtual host export writes a topology migration for the assigned name and no access migration
    Given the per-virtual-host export was given the virtual host name "orders"
    When I generate the migrations
    Then I get one migration for "orders"
    And the result says the file holds no users, virtual hosts or permissions and that "orders" must already exist on the 4.x broker

  @security @fake-broker @m8-05
  Scenario: Generating sends nothing to any broker and leaks no secret
    Given a definitions file with a user "svc" that has a password hash and a shovel parameter whose URI contains a password
    When I generate the migration for "/"
    Then no request was sent to any broker
    And neither the migration file nor the result nor its text contains the password hash or the password

  @negative @fake-broker @m8-05
  Scenario: Applying a generated migration follows the usual guardrails
    Given a generated migration and the user has no configure permission on the 4.x connection
    When I open the migrations screen
    Then "Apply pending" is disabled
    And I am told that the user cannot change topology

  @fake-broker @m8-05
  Scenario: Applying a generated migration twice changes nothing the second time
    Given a generated migration that declares the quorum queue "orders"
    And it was applied on "new-eu"
    When I apply it again on "new-eu"
    Then the broker topology is unchanged

  # ---- M8-06a: the Migrate from 3.x screen, file source, report (view model level; the Compose tests bind the same words) ------
  # The screen works with no broker connection at all. The live 3.x source (M8-02 reads it, M8-06c shows it), the 3.x pill and source locking arrive with M8-02 and M8-06c,
  # the access migration with M8-12 (M8-06d). Mockup: docs/ux/Migrate3x.dc.html (states 1 to 12).

  @smoke @fake-broker @m8-06a
  Scenario: Open a definitions file and read the report on the screen
    Given a definitions file "legacy.json" exported from RabbitMQ 3.13.7 with 3 classic queues, 2 exchanges and 4 bindings in "/"
    When I open that file on the Migrate from 3.x screen
    Then the screen shows the report for "legacy.json" from RabbitMQ 3.13.7
    And the severity chips show the counts of blockers, warnings and infos
    And the screen says how many items are compatible as they are and how many need a decision
    And the compatible items are not listed as problems

  @fake-broker @m8-06a
  Scenario: The message-loss block is the first block and recommends a new broker with a Shovel
    Given a definitions file with 2 classic queues without "x-queue-version"
    When I open that file on the Migrate from 3.x screen
    Then the first block of the report says an in-place upgrade to RabbitMQ 4.3 or later would silently empty classic queues
    And it lists both queues and says the loss cannot be detected after the upgrade
    And it recommends a new 4.x broker with this migration and a Shovel
    And "upgrade in place" is never shown as a green option

  @negative @fake-broker @m8-06a
  Scenario Outline: A file that cannot be read shows its reason and no report
    Given a file that <problem>
    When I open that file on the Migrate from 3.x screen
    Then the screen shows the error "<reason>" and no report
    And I can choose another file

    Examples:
      | problem                          | reason                                    |
      | is not valid JSON                | The file is not valid JSON                |
      | is a JSON array                  | The file is not a definitions file        |
      | is larger than the limit         | The file is larger than 64 MB             |
      | cannot be read from the disk     | The file could not be read                |

  @fake-broker @m8-06a
  Scenario: A per-virtual-host export asks for the name and analyses nothing until it gets one
    Given the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    When I open that file on the Migrate from 3.x screen
    Then the screen asks for the virtual host name with an empty field and no default
    And no report is shown
    When I give the virtual host name "  orders  "
    Then the report is shown for the virtual host "orders"
    And the report says the file holds no users, virtual hosts or permissions, so the access migration is empty

  @negative @fake-broker @m8-06a
  Scenario: A virtual host name that cannot be used is shown as an inline error
    Given the per-virtual-host definitions export taken from RabbitMQ 3.13.7 by the M8-00 spike
    And I opened that file on the Migrate from 3.x screen
    When I give the virtual host name only spaces
    Then the field shows the refusal of the name as an inline error
    And no report is shown
    And I can try another name or choose another file

  @fake-broker @m8-06a
  Scenario: The message-loss block shows the first 10 queues and all of them on request
    Given a definitions file with 60,000 classic queues without "x-queue-version"
    When I open that file on the Migrate from 3.x screen
    Then the message-loss block names the first 10 queues and says there are 60,000
    When I choose "Show all 60,000"
    Then the block offers every queue name as a lazy list

  @fake-broker @m8-06a
  Scenario: Names can be copied exactly as shown
    Given a definitions file with 12 classic queues without "x-queue-version"
    When I open that file on the Migrate from 3.x screen
    And I copy the name of the first row of a group
    Then the clipboard holds that name, with control characters escaped as shown
    When I copy the queue names of the message-loss block
    Then the clipboard holds all 12 names, one per line
    And the clipboard holds no password hash and no password

  @fake-broker @m8-06a
  Scenario: The report is filtered by severity and keeps the message-loss block first
    Given the report of a file with blockers, warnings and infos is shown
    When I turn off the chips "Warning" and "Info"
    Then only blockers are listed
    And the message-loss block is still first
    And each chip still shows its own count

  @fake-broker @m8-06a
  Scenario: A very large report is shown as groups with counts and loads rows only when a group is opened
    Given a definitions file with 60,000 classic queues
    When I open that file on the Migrate from 3.x screen
    Then the report shows groups with the number of findings in each
    And the screen state holds no finding rows for a group that is closed
    And opening a group offers its findings as a lazy list

  @security @fake-broker @m8-06a
  Scenario: A user row shows the hash as hidden and no hash reaches the screen
    Given a definitions file with a user "svc" that has a password hash and a shovel parameter whose URI contains a password
    When I open that file on the Migrate from 3.x screen
    Then the row for the user "svc" shows its tags, its hashing algorithm and the text "hash hidden"
    And no text, no semantics description and no screenshot of the screen contains the password hash or the password

  @fake-broker @m8-06a
  Scenario: A policy pattern the app could not evaluate says so and asks the user to check by hand
    Given a definitions file with a policy whose pattern is "^(?!amq\.).*"
    When I open that file on the Migrate from 3.x screen
    Then the finding says the app could not evaluate the pattern and did not use it to decide which queues are mirrored
    And it asks the user to check by hand

  @security @fake-broker @m8-06a
  Scenario: Names from the file are shown as plain text
    Given a definitions file with a queue named "<b>x</b>" followed by a control character
    When I open that file on the Migrate from 3.x screen
    Then the row shows the name as plain text with the control character escaped
    And nothing is interpreted as markup

  @security @fake-broker @m8-06a
  Scenario: A name with a right-to-left override cannot display reversed
    Given a definitions file with a queue named "a", the right-to-left override U+202E and "b"
    When I open that file on the Migrate from 3.x screen and export the report as text
    Then the row and the text show the name as "a\u202Eb" with the override escaped
    And neither contains the override character itself

  @fake-broker @m8-06a
  Scenario: Export the report from the screen as JSON or copy it as text
    Given the report for "legacy.json" is shown
    When I export the report
    Then the app asks where to save a JSON file with the schema "caerbannog.compat-report/1"
    And the saved file has no password, no password hash and no shovel URI
    When I copy the report as text
    Then the clipboard holds the text form of the report

  @negative @fake-broker @m8-06a
  Scenario: A report that cannot be saved says so and keeps the report
    Given the report for "legacy.json" is shown
    And the chosen place cannot be written
    When I export the report
    Then the screen says the report could not be saved, with a fixed reason
    And the report is still shown

  @fake-broker @m8-06a
  Scenario: The report of a file survives connecting to a 4.x broker
    Given I analysed the file "legacy.json" and the report is shown
    When I connect to "new-eu", a broker running RabbitMQ 4
    And I open the Migrate from 3.x screen again
    Then the report for "legacy.json" is still shown

  @safety @fake-broker @m8-06a
  Scenario: The screen with a file source sends no request to any broker
    Given a definitions file exported from RabbitMQ 3.13.7
    When I open that file on the Migrate from 3.x screen and export the report
    Then no request was sent to any broker

  # ---- M8-06b: review, generate and save (view model level) ----------------------------------------------------------------------
  # Uses the merged core review (ConversionReview). The screen never builds an action itself: it shows what the core returns.

  @fake-broker @m8-06b
  Scenario: The screen counts the items that need a decision
    Given the report of a file with 3 items that need a decision is shown
    Then the screen says "3 items need a decision"
    When I accept the default proposal of the first item
    Then the screen says "2 items need a decision"
    And "Generate migration" is disabled

  @smoke @fake-broker @m8-06b
  Scenario: Accept the default proposal of an item with its tick
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I open the review of "orders"
    Then the review shows the finding, why it matters, the default proposal and the alternatives
    And the tick "Ordering by priority may change" is unticked and "Accept" is disabled
    When I tick it and accept
    Then the item shows as accepted with the tick

  @fake-broker @m8-06b
  Scenario: Choose an alternative for a finding
    Given a definitions file with the classic queue "prio" with "x-max-priority" 5
    When I open the review of "prio"
    Then the default is to keep it classic and needs no tick
    When I choose the quorum alternative and tick "Ordering by priority may change"
    And I accept
    Then the item shows as accepted as a quorum queue

  @negative @fake-broker @m8-06b
  Scenario: An item with only a manual fix cannot be accepted as it is
    Given a definitions file with the non-durable queue "temp"
    When I open the review of "temp"
    Then the review shows the manual steps and "Accept" is disabled until an automatic alternative is chosen
    And "Skip" and "Replace with my own actions" are offered

  @fake-broker @m8-06b
  Scenario: Skip an item and change the decision later
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I skip "orders"
    Then "orders" is listed in the group "Skipped by you" and no longer in its rule group
    And the count of undecided items goes down
    When I un-skip "orders"
    Then "orders" is undecided again, is back in its rule group and is no longer in the group "Skipped by you"
    And the count of undecided items goes up
    When I skip "orders" and open its review from the group "Skipped by you" and accept it with the tick
    Then the item shows as accepted and the group "Skipped by you" is gone

  @fake-broker @m8-06b
  Scenario: Replace an item with my own actions
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    When I open the review of "orders" and choose "Replace with my own actions"
    Then the sheet shows the actions the app would write, as editable migration actions
    When I change the queue to have the argument "x-delivery-limit" 5 and save
    Then the item shows as replaced and the generated migration carries "x-delivery-limit" 5

  @negative @fake-broker @m8-06b
  Scenario Outline: A replacement the migration format refuses is shown as an error and not stored
    Given the report proposes to declare the classic queue "orders" as a quorum queue
    And I opened the replace sheet of "orders"
    When I enter <actions>
    Then the sheet shows the error "<error>" and keeps what I typed
    And the item is still undecided

    Examples:
      | actions                                   | error                                                  |
      | an action that deletes a queue            | A replacement must not delete anything                 |
      | a queue named ".."                        | the migration parser's own reason                      |
      | an action for another virtual host        | the core's own reason about the other virtual host     |
      | text that is not JSON                     | the migration parser's own reason                      |

  @negative @fake-broker @m8-06b
  Scenario: Generate is blocked while items are undecided and says how many
    Given the report of a file with 3 items that need a decision and 2 of them are decided
    Then "Generate migration" is disabled and the screen says "1 item needs a decision"
    And choosing "Show next undecided item" opens the review of that item

  @smoke @fake-broker @m8-06b
  Scenario: Generate the migrations and save them into the migrations folder
    Given every item of the report is decided
    And a migrations folder is chosen that holds the versions 0001 and 0002
    When I generate the migration
    Then the screen lists one file per source virtual host with its version 0003, description, action count and the skipped items and notes
    And the files are saved into the migrations folder
    And nothing was sent to any broker

  @fake-broker @m8-06b
  Scenario: Generating asks for the migrations folder when none is chosen
    Given every item of the report is decided and no migrations folder is chosen
    When I generate the migration
    Then the app asks for the migrations folder
    And when I cancel nothing is saved and the screen says no folder was chosen

  @negative @fake-broker @m8-06b
  Scenario: A folder that cannot be written is reported and nothing is saved
    Given every item of the report is decided and the migrations folder is read-only
    When I generate the migration
    Then the screen says the folder is read-only and no file was saved
    And I can choose another folder or export the files instead

  @negative @fake-broker @m8-06b
  Scenario: A file that cannot be saved after another one was saved says which was saved
    Given every item of the report is decided and the file holds two virtual hosts
    And the second file cannot be saved
    When I generate the migration
    Then the screen says which file was saved and which was not
    And the saved file stays in the migrations folder
    And I can try again, choose another folder or export the file that was not saved

  @fake-broker @m8-06b
  Scenario: Generating again after files were saved asks first and writes new versions
    Given the migrations "0003" and "0004" were generated and saved from this report
    When I change one decision and generate the migration again
    Then the screen first says that files were already saved and asks "Generate again?"
    And when I cancel nothing is written
    When I generate again and confirm
    Then new files with the versions 0005 and 0006 are saved and "0003" and "0004" are unchanged

  @fake-broker @m8-06b
  Scenario: Generated files show up on the Migrations screen as pending for a matching connection
    Given the migration "0003" was generated and saved for the virtual host "/"
    When I connect to "new-eu", a 4.x broker on the virtual host "/", and open the Migrations screen
    Then "0003 · Migrated from RabbitMQ 3.x legacy.json vhost /" is listed as Pending
    And on a connection to another virtual host it is listed but cannot be applied

  @fake-broker @m8-06b
  Scenario: The group of skipped items is last, closed, not filtered by the chips and absent when nothing is skipped
    Given the report of a file with blockers and warnings is shown and nothing is skipped
    Then there is no group "Skipped by you"
    When I skip one item
    Then the group "Skipped by you" is the last group, shows 1 item and is closed
    When I turn off every severity chip
    Then the group "Skipped by you" is still shown

  @fake-broker @m8-06b
  Scenario: The result lists what was skipped and left out, and the notes
    Given I skipped the queue "orders", which has a binding, and the file holds a message-loss queue
    When I generate the migration
    Then the result lists "orders" as skipped by me and the binding as left out
    And the notes repeat the message-loss recommendation of a new broker with a Shovel

  @negative @fake-broker @m8-04
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

  @broker-3x @m8-02
  Scenario: A real RabbitMQ 3.13 export is read and analysed
    Given a real RabbitMQ 3.13 broker with a mirrored classic queue, a priority queue and a binding
    When I analyse it live
    Then the report lists the mirroring policy and both queues with their proposals
    And the report names the version read from the broker
    And mirroring is flagged from the broker's deprecated feature list

  @broker-3x @m8-02
  Scenario: A real RabbitMQ 3.13 broker reports the storage version of its classic queues
    Given a real RabbitMQ 3.13 broker with a classic queue that has been running for a few seconds
    When I analyse it live
    Then the report lists the queue as an index version 1 message-loss blocker

  @broker-3x @negative @m8-02
  Scenario: A real RabbitMQ 3.13 user without the administrator tag is offered the file import
    Given a real RabbitMQ 3.13 broker and a user with the monitoring tag
    When I analyse it live
    Then I am told that this user cannot export definitions
    And I am offered to import a definitions file instead

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
