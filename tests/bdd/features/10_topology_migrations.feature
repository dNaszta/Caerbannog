@M5 @migrations
Feature: Topology migrations
  As an engineer
  I want to change broker topology through versioned JSON files
  So that changes are reviewable, repeatable and can be applied to several brokers

  Migrations behave like a database migration tool: files are applied in version order,
  applied versions are tracked on this machine per connection profile, and every action
  must be safe to run twice. The same JSON format is used to apply, export and import.
  The file format is a proposal and may still change.

  Background:
    Given I am connected to "uat-eu"
    And the user has configure permission on "/"

  @smoke @fake-broker
  Scenario: Apply pending migrations in version order
    Given migration files "0001", "0003" and "0002" in the migrations folder
    And none of them has been applied to "uat-eu"
    When I apply the pending migrations
    Then they are applied in the order "0001", "0002", "0003"
    And "0001", "0002" and "0003" are recorded as applied for "uat-eu" on this machine

  @fake-broker
  Scenario: Only pending migrations are applied
    Given "0001" and "0002" are already applied to "uat-eu" on this machine
    And a new migration file "0003"
    When I apply the pending migrations
    Then only "0003" is applied

  @fake-broker
  Scenario: History is kept per connection profile
    Given "0001" is applied to "uat-eu" on this machine
    When I look at the migrations of "prod-eu"
    Then "0001" is shown as pending for "prod-eu"

  @fake-broker
  Scenario: Running a migration twice changes nothing the second time
    Given the migration "0003" declares the quorum queue "orders" and a binding to the exchange "events"
    And "0003" has already been applied directly on the broker by someone else
    When I apply "0003" on this machine
    Then the broker topology is unchanged
    And "0003" is recorded as applied

  @fake-broker
  Scenario: A dry run shows what would happen
    Given a pending migration "0003" with 2 actions
    When I run a dry run of "0003"
    Then I see each action and whether it is allowed
    And nothing is sent to the broker

  @negative @fake-broker
  Scenario: Apply is disabled without configure permission
    Given the user has no configure permission on "/"
    And a pending migration "0003"
    When I open the migrations screen
    Then "Apply pending" is disabled
    And I am told that the user cannot change topology on "uat-eu"
    But I can still edit, validate and export the file

  @fake-broker
  Scenario: Export the current topology as a migration
    Given the broker has the quorum queue "orders" and the exchange "events"
    And the latest migration file is "0004"
    When I export the current topology
    Then a new migration file "0005" is created
    And it declares the queues, exchanges, bindings and policies of the broker

  @fake-broker
  Scenario: Import a migration file
    Given a valid migration file "0005.json" on disk
    When I import it
    Then "0005" appears in the migrations list as pending

  @negative @fake-broker
  Scenario Outline: Invalid migration files are rejected with a clear reason
    When I import a migration file that <problem>
    Then the import is refused
    And the message says <reason>
    And nothing is added to the migrations list

    Examples:
      | problem                              | reason                                |
      | is not valid JSON                    | the file is not valid JSON            |
      | has no version                       | the file has no version                |
      | repeats the version of another file  | that version already exists            |
      | uses an action the app does not know | which action is not supported          |
      | is nested ten thousand levels deep   | the file is nested too deep            |
      | declares a queue type inside the arguments | the queue type belongs in "type"   |
      | has a policy with an unknown apply-to      | the policy apply-to is not supported |
      | has a binding with an unknown destination type | the binding destination type must be queue or exchange |
      | has a queue whose name is an object       | the value of "name" has the wrong type |
      | declares a queue named ".."               | a name must not be "." or ".."          |

  # ---- M8-04: the format carries what a converted 3.x topology needs (additive; old files unchanged) ----

  @m8-04 @fake-broker
  Scenario: Queue arguments keep their type
    Given a migration file that declares the quorum queue "orders" with the arguments "x-delivery-limit" 5, "x-message-ttl" 60000 and "x-dead-letter-exchange" "dlx"
    When I import it
    And I apply the pending migrations
    Then the broker received the queue "orders" with "x-delivery-limit" as the number 5 and "x-message-ttl" as the number 60000
    And "x-dead-letter-exchange" is the text "dlx"
    And the dry run row for "orders" lists the three arguments

  @m8-04 @fake-broker
  Scenario: Exchange flags and arguments are declared
    Given a migration file that declares the exchange "events.x" as a topic exchange that is not durable, internal and auto-delete, with the argument "alternate-exchange" "ae"
    When I import it
    And I apply the pending migrations
    Then the broker has the exchange "events.x" with durable false, internal true and auto-delete true

  @m8-04 @fake-broker
  Scenario: A policy carries its apply-to, priority and typed definition
    Given a migration file that declares the policy "limits" on "^orders" applying to "quorum_queues" with priority 7 and the definition "max-length" 1000 and "overflow" "reject-publish"
    When I import it
    And I apply the pending migrations
    Then the broker received the policy "limits" with apply-to "quorum_queues", priority 7 and "max-length" as the number 1000

  @m8-04 @fake-broker
  Scenario: An operator policy is declared like a policy
    Given a migration file that declares the operator policy "op-limit" on ".*" applying to "queues" with the definition "max-length" 100000
    When I import it
    And I apply the pending migrations
    Then the broker received the operator policy "op-limit" with "max-length" as the number 100000

  @m8-04 @fake-broker
  Scenario: A binding can point at an exchange
    Given a migration file that binds the exchange "ex-e2e" to the exchange "dlx" with routing key "dead"
    When I import it
    And I apply the pending migrations
    Then the broker has a binding from "ex-e2e" to the exchange "dlx" with routing key "dead"

  @m8-04 @fake-broker
  Scenario: A file in the old format keeps its checksum and its meaning
    Given the migration file "0001" in the format of the first release was applied to "uat-eu"
    When I list the migrations
    Then "0001" is shown as applied, not as modified
    And its actions are the same as before

  @m8-04 @fake-broker
  Scenario: Exporting the topology keeps working and keeps exchange flags
    Given the broker has a durable queue "orders", a non-durable exchange "events.x" and a binding between them
    When I export the current topology
    Then the new migration declares "orders", "events.x" with durable false, and the binding
    And a file without any new feature does not contain the new keys

  @m8-04 @broker
  Scenario: Typed arguments reach a real 4.x broker as numbers
    Given a real RabbitMQ 4 broker
    When the requests of a migration that declares a quorum queue with "x-delivery-limit" 5, an exchange, an exchange-to-exchange binding, a policy and an operator policy are sent
    Then the broker reports "x-delivery-limit" 5 as a number on the queue
    And the exchange, the binding, the policy with its apply-to and priority and the operator policy exist as declared

  # ---- M5-08: real execution, rerun safety, persistence and the migrations folder ----
  # Sub-tags: @m5-08a executor, conflicts, rerun (core + data-rabbit, in-memory repositories, fake HTTP);
  # @m5-08b persistence and the migrations folder (real files in temp folders, fake chooser);
  # @m5-08c desktop wiring and the Migrations screen states. Owner decisions 2026-11-09: a failing action stops the
  # migration and it is not recorded; a rerun is safe by design; the user chooses the migrations folder.

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A failing action stops the migration
    Given a pending migration "0003" with three actions whose second action is refused by the broker
    When I apply "0003"
    Then the first action was applied and the third action was never sent
    And the message names the second action and a fixed reason, not the broker's text
    And "0003" is not recorded as applied

  @m5-08 @m5-08a @fake-broker
  Scenario: A rerun after a failure finishes the migration
    Given a pending migration "0003" with three actions whose second action was refused by the broker in the first run
    And the broker no longer refuses it
    When I apply "0003" again
    Then the first action changes nothing on the broker
    And the second and third actions are applied
    And "0003" is recorded as applied

  @m5-08 @m5-08a @fake-broker
  Scenario: A rerun after a success changes nothing
    Given a migration that declares a queue, an exchange, a binding and a policy was applied
    When its actions run again
    Then the broker topology is unchanged
    And no policy request changed anything and no request deleted anything

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A rerun when the broker changed in between is a conflict
    Given a pending migration "0003" that declares the quorum queues "orders" and "jobs"
    And the first run failed at "jobs"
    And someone declared "orders" with other arguments in the meantime
    When I apply "0003" again
    Then the apply stops at "orders" and says it exists with different settings
    And "orders" on the broker is unchanged
    And "0003" is not recorded as applied

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A queue that exists with other arguments is a conflict
    Given the broker has the quorum queue "orders" with "x-delivery-limit" 3
    And a pending migration declares the quorum queue "orders" with "x-delivery-limit" 5
    When I apply the pending migrations
    Then the apply stops and says "orders" exists with different settings
    And "orders" on the broker is unchanged
    And no request deleted or recreated anything
    And the migration is not recorded as applied

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A policy that exists with other settings is a conflict, never overwritten
    Given the broker has the policy "limits" on "^orders" with "max-length" 500
    And a pending migration declares the policy "limits" on "^orders" with "max-length" 1000
    When I apply the pending migrations
    Then the apply stops and says "limits" exists with different settings
    And the policy "limits" on the broker still has "max-length" 500
    And no policy was written

  @m5-08 @m5-08a @fake-broker
  Scenario: A policy that exists with the same settings is a no-op
    Given the broker has the policy "limits" on "^orders" with "max-length" 500
    And a pending migration declares the policy "limits" on "^orders" with "max-length" 500
    When I apply the pending migrations
    Then the migration is recorded as applied and no policy request changed anything

  @m5-08 @m5-08a @fake-broker
  Scenario: Deleting something that is already gone is a no-op
    Given a pending migration that deletes the queue "old" and the connection has the level "Full control"
    And the broker has no queue "old"
    When I apply the pending migrations
    Then the migration is recorded as applied

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: Applying reads back every declared queue
    Given a pending migration that declares the quorum queues "orders" and "jobs"
    And the broker silently does not create "jobs"
    When I apply the pending migrations
    Then the apply stops and says "jobs" was not found after it was declared
    And the migration is not recorded as applied

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A migration for another virtual host is blocked
    Given a pending migration whose actions belong to the virtual host "/orders"
    And the connection "uat-eu" is for the virtual host "/"
    When I open the migrations screen
    Then the dry run says the actions belong to another virtual host and "Apply pending" is disabled

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A migration that declares and deletes the same thing is refused
    Given a migration file that declares the queue "orders" and also deletes the queue "orders"
    When I import it
    Then the import is refused and the message says the file declares and deletes "orders" and must be split into two migrations

  @m5-08 @m5-08a @fake-broker @negative
  Scenario: An export that cannot read the broker writes no file
    Given the broker refuses to list the queues of "/" for this user
    When I export the current topology
    Then I am told the topology could not be read and no migration file was written

  @m5-08 @m5-08a @fake-broker
  Scenario: Exporting the topology reads every page
    Given the broker has 150 queues in "/"
    When I export the current topology
    Then the new migration declares all 150 queues

  @m5-08 @m5-08b @fake-broker
  Scenario: Applied migrations and files survive a restart
    Given the migrations folder is "/work/migrations" and the migration "0001" was imported and applied to "uat-eu"
    When the app is restarted
    Then "0001" is listed and shown as applied for "uat-eu"
    And its file in the folder holds exactly the imported text

  @m5-08 @m5-08b @fake-broker
  Scenario: The first visit to the Migrations screen asks for the folder
    Given no migrations folder has been chosen on this machine
    When I open the migrations screen
    Then I am asked to choose the folder where my migration files live
    And no file is read or written and "Apply pending", "Import" and "Export" are disabled

  @m5-08 @m5-08b @fake-broker
  Scenario: The chosen folder is remembered for every connection
    Given I chose the folder "/work/migrations" on the Migrations screen of "uat-eu"
    When I open the migrations screen of "prod-eu" after a restart
    Then the files of "/work/migrations" are listed
    And I am not asked for a folder again

  @m5-08 @m5-08b @fake-broker
  Scenario: The history stays with the connection, not with the folder
    Given "0001" is applied to "uat-eu" and I choose another folder that also holds "0001" with the same content
    When I open the migrations screen of "uat-eu"
    Then "0001" is shown as applied
    And the history is kept in the app's own data, not in the chosen folder

  @m5-08 @m5-08b @fake-broker @negative
  Scenario: A folder that has disappeared is reported and nothing is created
    Given the chosen folder "/work/migrations" no longer exists
    When I open the migrations screen
    Then I see the path and that the folder does not exist
    And I can try again or choose another folder
    And the folder was not created and "Apply pending" is disabled

  @m5-08 @m5-08b @fake-broker @negative
  Scenario: A folder that cannot be read is reported and nothing is changed
    Given the chosen folder cannot be read
    When I open the migrations screen
    Then I see the path and that the folder cannot be read
    And no file was written

  @m5-08 @m5-08b @fake-broker @negative
  Scenario: A read-only folder can be read but not written
    Given the chosen folder can be read but not written
    When I open the migrations screen
    Then its migrations are listed
    And "Import" and "Export" are disabled and the reason says the folder is read-only

  @m5-08 @m5-08b @fake-broker @negative
  Scenario: An unreadable history file blocks applying and is kept
    Given the applied-migrations history file on disk is not valid JSON
    When I open the migrations screen
    Then I see the path of the file and that it could not be read
    And "Apply pending" is disabled
    And the history file still has exactly its original bytes

  @m5-08 @m5-08b @fake-broker
  Scenario: Starting with an empty history keeps the old file under a new name
    Given the applied-migrations history file on disk is not valid JSON
    And the clock says 2026-11-09 14:30:05
    When I choose "Start with an empty history"
    Then I am asked "Move migration-history.json to migration-history.json.unreadable-20261109-143005 and start with an empty history? The old file is kept."
    When I confirm
    Then the old file exists under the new name with its original bytes and every migration is pending

  @m5-08 @m5-08b @fake-broker @negative
  Scenario: A migration file in the folder that is not valid is listed as invalid, not deleted
    Given the migrations folder holds a file "0007.json" that is not valid JSON
    When I open the migrations screen
    Then "0007.json" is listed as invalid with the reason
    And the file still exists unchanged

  @m5-08 @m5-08b @fake-broker @negative
  Scenario Outline: A version that could escape the folder is refused
    When I import a migration file whose version is <version>
    Then the import is refused and nothing is written outside the migrations folder

    Examples:
      | version      |
      | "../0001"    |
      | "a/b"        |
      | "0001.json"  |
      | NaN          |
      | 1e3          |

  @m5-08 @m5-08c @fake-broker @negative
  Scenario: The topology control level of the connection is honoured
    Given the connection "uat-eu" has the topology control level "Read-only" although the broker would allow changes
    When I open the migrations screen
    Then "Apply pending" is disabled and the reason says the level is Read-only

  # Fail closed (M5-08a review, F3): the executor exists only for a broker whose GET /api/overview reports a 4.x or later version; M8-02 owns the
  # connection pill text, the forced Read-only level and the live 3.x source reading.
  @m5-08 @m5-08a @fake-broker @negative
  Scenario: A 3.x source connection cannot apply migrations
    Given a connection "legacy-eu" to a broker whose overview reports RabbitMQ 3.13.7
    When I connect to "legacy-eu"
    Then the connection has no migration executor and says migrations are not offered because it is a migration source only
    And no request that changes anything was sent to "legacy-eu"

  @m5-08 @m5-08c @fake-broker
  Scenario: A failed apply shows which action failed and why
    Given a pending migration "0003" whose second action is refused by the broker
    When I apply "0003" on the screen
    Then the screen says "Action 2 of 3 failed: the broker refused the request"
    And it says migration "0003" was not recorded and that running it again is safe

  @m5-08 @m5-08c @fake-broker
  Scenario: A conflict names the resource
    Given the broker has the quorum queue "orders" with "x-delivery-limit" 3
    And a pending migration declares the quorum queue "orders" with "x-delivery-limit" 5
    When I apply the pending migrations on the screen
    Then the screen says "Action 1 of 1 stopped: orders exists with different settings"
    And it says the existing "orders" was not changed and "0003" was not recorded

  @m5-08 @m5-08c @fake-broker
  Scenario: Applying shows its progress
    Given a pending migration "0003" with three actions
    When I apply "0003" on the screen and the second action is running
    Then the screen says "Applying 0003: action 2 of 3"
    And Apply pending, Import and Export are disabled

  @m5-08 @m5-08c @fake-broker
  Scenario: An apply that could not be recorded says so
    Given the history cannot be written after the broker accepted every action of "0003"
    When I apply "0003" on the screen
    Then the screen says "The migration was applied but could not be recorded; running it again is safe"
    And "0003" is still listed as pending

  @m5-08 @m5-08c @fake-broker @negative
  Scenario: A connection that cannot apply migrations says why
    Given a connection whose migration executor is missing because "This broker runs RabbitMQ 3.x: it is a migration source only"
    When I open the migrations screen
    Then the screen says migrations are not offered on this connection and shows that reason
    And "Apply pending" is disabled while files, dry run, Import and Export keep working

  @m5-08 @m5-08c @fake-broker @negative
  Scenario: The unreadable history can be moved aside from the screen
    Given the applied-migrations history file is not valid JSON
    When I open the migrations screen and choose "Start with an empty history"
    Then a dialog asks to move migration-history.json to its timestamped name and says the old file is kept
    And cancelling changes nothing and confirming makes every migration pending

  @m5-08 @m5-08c @fake-broker @negative
  Scenario: Deleting a profile whose history cannot be updated leaves it as it is
    Given the history of the connection "uat-eu" cannot be updated
    When I confirm deleting the connection "uat-eu"
    Then the Connections screen shows "The migration history of this connection could not be updated, so the connection was left as it is"
    And "uat-eu" is still listed

  @m5-08 @m5-08c @fake-broker @negative
  Scenario: Renaming a profile whose history cannot be updated leaves it as it is
    Given the history of the connection "uat-eu" cannot be updated
    When I save the connection "uat-eu" under the name "uat-eu-2"
    Then the Connections screen shows "The migration history of this connection could not be updated, so the connection was left as it is"
    And the connection is still named "uat-eu"

  @m5-08 @m5-08c @fake-broker
  Scenario: The folder can be changed at any time
    Given a folder is chosen
    When I choose "Change folder" and pick another folder
    Then the files of the new folder are listed and the history is unchanged

  @fake-broker
  Scenario: Editing an applied migration is flagged
    Given "0002" is applied to "uat-eu" on this machine
    When the file "0002" is changed on disk
    Then the app warns that an applied migration was modified
    And it does not apply the changed file again

  @broker @m5-08 @m5-08a
  Scenario: Apply a migration to a real broker
    Given a real broker and a migration that declares the quorum queue "orders" and binds it to the exchange "events" with routing key "order.created"
    When I apply the pending migrations
    Then the broker has a quorum queue "orders" bound to "events" with routing key "order.created"

  @broker @m5-08 @m5-08a @gate-M5
  Scenario: Exported topology can be applied to an empty broker
    Given a real broker A with queues, exchanges and bindings
    And a second empty real broker B
    When I export the topology of A and apply the resulting migration to B
    Then B has the same queues, exchanges and bindings as A
