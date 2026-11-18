@M9 @planner
Feature: Topology planner
  As an engineer
  I want to build a topology change step by step in a form
  So that I do not have to write migration JSON by hand

  The planner is a front end on the migration files. A plan is a draft migration: a description, one
  virtual host and an ordered list of steps in plain words. It becomes a migration file only with
  "Save as migration" and it is applied only by the existing Apply of the Migrations screen. Nothing the
  planner does changes the broker.

  PLANNED 2026-11-15, not built. The owner answered every open question on 2026-11-15 (docs/spec.md,
  "Topology planner (M9)", "Owner answers to the questions", O12 to O14); @needs-spike marks what still waits
  for a broker fact.
  Sub-tags: @m9-01 plan core (forms, plain words, validation, naming), @m9-01b broker name rules measured by S-H, @m9-02 draft, save and dismiss, @m9-03 the
  screen and its states, @m9-04 the review graph, @m9-12 a pending file opened as steps.
  The check against the broker is in 20, streams, policies, dead-lettering and MQTT routes are in 21.

  Background:
    Given I am connected to "uat-eu" with the virtual host "/"
    And the user has configure permission on "/"
    And the connection "uat-eu" has the topology control level "Declare and edit"
    And the migrations folder is "/work/migrations" and its latest migration file is "0004"

  # ---- M9-01: the wizard, the steps in plain words, validation ----

  @m9-01 @smoke @fake-broker
  Scenario: Add a queue, an exchange and a binding with the wizard
    When I start a new plan
    And I add a quorum queue "orders" with the wizard
    And I add a direct exchange "events" with the wizard
    And I add a binding from "events" to the queue "orders" with routing key "order.created" with the wizard
    Then the plan lists these steps in this order
      | Declare quorum queue orders              |
      | Declare direct exchange events           |
      | Bind events to orders with order.created |
    And nothing was sent to the broker

  @m9-01 @fake-broker
  Scenario: The wizard ends with a summary before anything is added
    Given a new plan
    When I choose "Queue" in the wizard and enter the name "orders"
    Then the summary says "This adds 1 step" and lists "Declare quorum queue orders"
    And the plan still has no step
    When I choose "Add to plan"
    Then the plan lists "Declare quorum queue orders"

  @m9-01 @fake-broker
  Scenario: Cancelling the wizard adds nothing
    Given a new plan
    When I choose "Queue" in the wizard, enter the name "orders" and cancel
    Then the plan has no step

  @m9-01 @fake-broker
  Scenario: The wizard offers exactly these kinds
    When I open the wizard
    Then I can choose "Queue", "Exchange", "Binding", "Stream", "Policy", "Dead-lettering for a queue" and "MQTT route"
    And there is no kind that deletes anything
    And there is no kind for users, virtual hosts or permissions
    And there is no kind that reads, moves or purges messages

  @m9-01 @fake-broker
  Scenario Outline: A fanout or a direct exchange
    Given a new plan
    When I add a <type> exchange "ex-1" with the wizard
    Then the plan lists "Declare <type> exchange ex-1"

    Examples:
      | type   |
      | fanout |
      | direct |

  @m9-01 @fake-broker
  Scenario Outline: A topic or a headers exchange
    Given a new plan
    When I add a <type> exchange "ex-1" with the wizard
    Then the plan lists "Declare <type> exchange ex-1"

    Examples:
      | type    |
      | topic   |
      | headers |

  @m9-01 @fake-broker
  Scenario: A new queue is a quorum queue unless I choose otherwise
    Given a new plan
    When I add a queue "jobs" with the wizard without choosing a type
    Then the plan lists "Declare quorum queue jobs"

  @m9-01 @negative @fake-broker
  Scenario Outline: A form that cannot be added says why
    Given a new plan
    When I fill the "<kind>" form with <input>
    Then the form says "<message>"
    And "Next" is disabled

    Examples:
      | kind     | input                                   | message                                |
      | Queue    | an empty name                           | Give the queue a name                  |
      | Queue    | the name ".."                           | a name must not be "." or ".."         |
      | Exchange | an empty name                           | Give the exchange a name               |
      | Binding  | no exchange                             | Choose the exchange to bind from       |
      | Binding  | no destination                          | Choose what to bind to                 |

  # Measured by M9-00c (S-H, docs/spec.md "Naming"). The messages are decided wording (owner, 2026-11-16, "do arch suggest": owner review list item 1, docs/tasks/M9-00c-spike-policies-equivalence-names.md).
  @m9-01b @negative @fake-broker
  Scenario Outline: A name that RabbitMQ would refuse is refused by the form
    Given a new plan
    When I fill the "<kind>" form with <input>
    Then the form says "<message>"
    And "Next" is disabled

    Examples:
      | kind     | input                     | message                                    |
      | Queue    | the name "amq.orders"     | Names that start with "amq." are reserved  |
      | Exchange | the name "amq.orders"     | Names that start with "amq." are reserved  |
      | Stream   | the name "amq.orders"     | Names that start with "amq." are reserved  |
      | Queue    | a name of 256 letters     | A queue name can be at most 255 bytes long |
      | Queue    | a name of 128 letters "é" | A queue name can be at most 255 bytes long |

  # The dot style derives "amq.dln" from "amq": the derived response name is a stream name like any other and the broker refuses it.
  @m9-01b @negative @fake-broker
  Scenario: A response stream name that RabbitMQ would refuse is refused by the form
    Given a new plan with the naming style "dot.separated"
    When I fill the "Stream" form with the name "amq" and its response stream
    Then the form says "Names that start with "amq." are reserved"
    And "Next" is disabled

  @m9-01b @fake-broker
  Scenario Outline: A name that RabbitMQ accepts is not refused by the form
    Given a new plan
    When I fill the "<kind>" form with <input>
    Then "Next" is enabled

    Examples:
      | kind     | input                 |
      | Queue    | the name "amq"        |
      | Queue    | a name of 255 letters |
      | Exchange | a name of 256 letters |
      | Stream   | a name of 256 letters |
      | Queue    | the name "orders eu"  |

  @m9-01 @negative @fake-broker
  Scenario: The same thing declared twice with other settings is a problem in the plan
    Given a plan that declares the quorum queue "orders"
    When I add a classic queue "orders" with the wizard
    Then the plan shows the problem "The plan already declares orders with other settings"
    And "Save as migration" is disabled and says "Fix the 1 problem in the plan first"

  @m9-01 @negative @fake-broker
  Scenario: A binding placed before the thing it needs is a problem in the plan
    Given a plan with the steps
      | Bind events to invoices with invoice.created |
      | Declare quorum queue invoices                |
    Then step 1 shows the problem "The queue invoices is declared later, in step 2. Move this step below it."
    When I move step 1 down
    Then the plan has no problem

  @m9-01 @fake-broker
  Scenario: A wizard binding to something the plan declares is placed after it
    Given a plan that declares the quorum queue "orders" and the direct exchange "events"
    When I add a binding from "events" to the queue "orders" with routing key "order.created" with the wizard
    Then the binding is the last step
    And the plan has no problem

  @m9-01 @fake-broker
  Scenario: Steps can be edited, moved and removed
    Given a plan with the steps
      | Declare quorum queue orders              |
      | Declare direct exchange events           |
      | Bind events to orders with order.created |
    When I edit step 3 and change the routing key to "order.paid"
    And I move step 2 up
    And I remove the step "Declare quorum queue orders"
    Then the plan lists these steps in this order
      | Declare direct exchange events        |
      | Bind events to orders with order.paid |

  @m9-01 @fake-broker
  Scenario: The destination of a binding can be picked from the plan and from the broker
    Given the broker has the quorum queue "audit"
    And a plan that declares the quorum queue "orders"
    When I open the binding form
    Then the destinations offered are "orders" from this plan and "audit" from the broker

  @m9-01 @fake-broker
  Scenario: A plan is for one virtual host
    When I start a new plan
    And I add a quorum queue "orders" with the wizard
    Then every step of the plan belongs to the virtual host "/"

  # ---- M9-01: naming (owner decisions O10 and O11, 2026-11-15) ----

  @m9-01 @fake-broker
  Scenario: A name that does not follow the naming style gets a hint and can still be added
    Given a new plan with the naming style "kebab-case"
    When I enter the queue name "Orders_EU" in the wizard
    Then the name field shows the hint "This name does not follow kebab-case, for example orders-eu"
    And "Next" is enabled
    When I finish the wizard
    Then the plan lists "Declare quorum queue Orders_EU"
    And the plan has no problem

  @m9-01 @fake-broker
  Scenario Outline: The hint follows the chosen naming style
    Given a new plan with the naming style "<style>"
    When I enter the queue name "<typed>" in the wizard
    Then the name field shows <hint>

    Examples:
      | style         | typed      | hint                                                                    |
      | kebab-case    | orders-eu  | no hint                                                                 |
      | kebab-case    | orders_eu  | the hint "This name does not follow kebab-case, for example orders-eu"    |
      | snake_case    | orders-eu  | the hint "This name does not follow snake_case, for example orders_eu"    |
      | dot.separated | ordersEu   | the hint "This name does not follow dot.separated, for example orders.eu" |
      | camelCase     | orders-eu  | the hint "This name does not follow camelCase, for example ordersEu"      |
      | No convention | Orders_EU  | no hint                                                                 |

  @m9-01 @fake-broker
  Scenario: Names that already exist on the broker are never flagged
    Given the broker has the direct exchange "Legacy_Events"
    And a plan with the naming style "kebab-case" that declares the quorum queue "orders"
    When I add a binding from "Legacy_Events" to the queue "orders" with routing key "Order.Created" with the wizard
    Then no hint is shown for "Legacy_Events" and none for the routing key

  @m9-01 @fake-broker
  Scenario: Changing the naming style renames nothing
    Given a plan with the naming style "kebab-case" that declares the quorum queue "orders-eu"
    When I choose the naming style "snake_case"
    Then the plan still lists "Declare quorum queue orders-eu"

  @m9-02 @fake-broker
  Scenario: The naming style is remembered on this machine
    Given I chose the naming style "snake_case" in the planner
    When the app is restarted and I start a new plan
    Then the naming style is "snake_case"

  @m9-02 @fake-broker
  Scenario: The naming style is kebab-case until I choose another
    Given no naming style was ever chosen on this machine
    When I start a new plan
    Then the naming style is "kebab-case"

  # ---- M9-02: the plan is a draft; save as migration; dismiss (owner decision O5) ----

  @m9-02 @smoke @fake-broker
  Scenario: A plan is a draft until it is saved
    When I start a new plan
    And I add a quorum queue "orders" with the wizard
    Then the planner says the plan is a draft that is not saved as a migration
    And the migrations folder holds no new file
    And the migrations list shows no new migration
    And nothing was sent to the broker

  @m9-02 @smoke @fake-broker
  Scenario: Save as migration writes the next version
    Given a plan described as "Add orders queue and binding" with the steps
      | Declare quorum queue orders              |
      | Bind events to orders with order.created |
    When I choose "Save as migration"
    Then the file "0005.json" is written to the migrations folder
    And it is a valid migration "0005" described as "Add orders queue and binding" with the actions "declareQueue" and "declareBinding"
    And "0005" appears in the migrations list as pending and is selected
    And I am told "Plan saved as 0005. Nothing was sent to the broker. Apply it when you are ready."
    And there is no draft any more
    And nothing was sent to the broker

  @m9-02 @fake-broker
  Scenario: A saved plan is applied by the existing Apply
    Given a plan that declares the quorum queue "orders" was saved as "0005"
    When I apply "0005" on the Migrations screen
    Then the broker is asked to create the quorum queue "orders"
    And "0005" is recorded as applied for "uat-eu" on this machine

  @m9-02 @fake-broker
  Scenario: A plan with a name that does not follow the naming style can be saved
    Given a plan with the naming style "kebab-case" that declares the quorum queue "Orders_EU"
    Then "Save as migration" is enabled
    When I choose "Save as migration"
    Then the saved file declares the queue "Orders_EU" exactly as typed

  @m9-02 @fake-broker
  Scenario: Saving never overwrites a migration file
    Given a plan that declares the quorum queue "orders"
    And a file "0005.json" appeared in the migrations folder after the planner was opened
    When I choose "Save as migration"
    Then the plan is saved as "0006"
    And "0005.json" still has exactly its original bytes

  @m9-02 @fake-broker
  Scenario: Dismiss discards the draft after a question
    Given a plan with 3 steps
    When I choose "Dismiss"
    Then I am asked "Dismiss this plan? Its 3 steps are discarded. Nothing was sent to the broker."
    When I choose "Keep the plan"
    Then the plan still has 3 steps
    When I choose "Dismiss" and confirm
    Then there is no draft any more
    And the migrations folder holds no new file
    And nothing was sent to the broker

  @m9-02 @fake-broker
  Scenario: An empty plan is dismissed without a question
    Given a new plan
    When I choose "Dismiss"
    Then there is no draft any more and no question was asked

  @m9-02 @fake-broker
  Scenario: A draft survives a restart
    Given a plan described as "Add orders" that declares the quorum queue "orders"
    When the app is restarted
    Then the Migrations screen offers "Continue plan" for a draft with 1 step
    When I choose "Continue plan"
    Then the plan is described as "Add orders" and lists "Declare quorum queue orders"

  @m9-02 @fake-broker
  Scenario: There is one draft at a time
    Given a plan with 3 steps
    When I open the Migrations screen
    Then the button reads "Continue plan", not "New plan"

  @m9-02 @fake-broker
  Scenario: The draft is not kept in the migrations folder
    Given a plan that declares the quorum queue "orders"
    Then the draft is kept in the app's own data, not in "/work/migrations"

  @m9-02 @negative @fake-broker
  Scenario: A draft that cannot be read is reported and kept
    Given the draft file on disk is not a valid plan
    When I open the planner
    Then I see the path of the draft file and that it could not be read
    And the draft file still has exactly its original bytes
    When I choose "Start a new plan"
    Then I am asked to move the draft file to a name ending in ".unreadable-" and a timestamp, and told the old file is kept
    When I confirm
    Then the old file exists under the new name with its original bytes and the plan is empty

  @m9-02 @negative @security @fake-broker
  Scenario: A draft that holds a password hash is reported and kept
    Given the draft file on disk holds a "declareUser" action with a password hash
    When I open the planner
    Then I see the path of the draft file and that it could not be read
    And the draft file still has exactly its original bytes
    And nothing is written to the draft file

  @m9-02 @negative @fake-broker
  Scenario: An empty plan cannot be saved
    Given a new plan
    Then "Save as migration" is disabled and says "Add a step first"

  @m9-02 @negative @fake-broker
  Scenario: Saving needs a migrations folder
    Given no migrations folder has been chosen on this machine
    And a plan that declares the quorum queue "orders"
    Then "Save as migration" is disabled and says "Choose a migrations folder first"
    And I can still add a step
    When I choose the folder "/work/migrations" from the planner
    Then "Save as migration" is enabled

  @m9-02 @negative @fake-broker
  Scenario: A read-only folder cannot take the plan
    Given the chosen folder can be read but not written
    And a plan that declares the quorum queue "orders"
    Then "Save as migration" is disabled and says "The folder is read-only"

  @m9-02 @negative @fake-broker
  Scenario: A failed write keeps the draft
    Given a plan with 3 steps
    And the migrations folder refuses the write
    When I choose "Save as migration"
    Then I am told "The file could not be written"
    And the plan still has 3 steps
    And the migrations folder holds no new file

  @m9-02 @negative @safety @fake-broker
  Scenario: The planner cannot change the broker by itself
    Given a plan with a queue, an exchange and a binding
    When I check the plan, save it as a migration and dismiss a second plan
    Then no request that creates, changes or deletes anything was sent to the broker
    And the only requests sent were reads

  @m9-02 @negative @security @fake-broker
  Scenario: A plan can never hold a password or a password hash
    Given a plan built with every kind of the wizard
    When I choose "Save as migration"
    Then the saved file holds no user, no password and no password hash
    And the draft file never held one

  # ---- M9-03: the screen, where it is opened, and its limited states ----

  @m9-03 @fake-broker
  Scenario: The planner is opened from the Migrations screen
    When I open the Migrations screen
    Then I can choose "New plan"
    When I choose "New plan"
    Then the planner opens with an empty plan and the text "No steps yet"
    And "Check", "Save as migration" and "Dismiss" are disabled

  @m9-03 @fake-broker
  Scenario: After saving, Apply is pressed on the Migrations screen
    Given a plan that declares the quorum queue "orders"
    When I choose "Save as migration"
    Then the Migrations screen is shown with "0005" selected and its dry run
    And "Apply 0005" is offered there

  @m9-03 @negative @fake-broker
  Scenario: A Read-only connection can plan, check and save, and is told that applying is locked
    Given the connection "uat-eu" has the topology control level "Read-only"
    When I open the planner
    Then the planner says "This connection is set to Read-only. You can plan, check and save; applying needs the level Declare and edit."
    And I can add a step and save the plan as a migration

  @m9-03 @negative @fake-broker
  Scenario: Without configure permission the planner says that applying is locked
    Given the user has no configure permission on "/"
    When I open the planner
    Then the planner says the user cannot change topology on "uat-eu" and that the plan can be saved and applied on a connection that allows it
    And I can add a step and save the plan as a migration

  @m9-03 @negative @safety @fake-broker
  Scenario: A RabbitMQ 3.x connection gets no planner
    Given a plan with 3 steps
    And I connect to "legacy-eu", a broker whose overview reports RabbitMQ 3.13.7
    When the planner is shown
    Then it says "The planner is not offered on this connection" with the reason "This broker runs RabbitMQ 3.x: it is a migration source only"
    And there is no wizard, no check and no save
    And the planner sent no request at all to "legacy-eu"
    And the draft still has 3 steps

  @m9-03 @fake-broker
  Scenario: Without a connection a plan can be written and saved but not checked
    Given I am not connected
    When I start a new plan and add a quorum queue "orders" with the wizard
    Then the planner says "Not connected. You can plan and save; connect to a RabbitMQ 4 broker to check the plan."
    And "Check" is disabled
    And I can save the plan as a migration

  @m9-03 @fake-broker
  Scenario: The plan can be read as the migration text it will be saved as
    Given a plan that declares the quorum queue "orders"
    When I choose the "JSON" view of the review
    Then I see the migration text with the action "declareQueue" for "orders"
    And the text cannot be edited there

  # ---- M9-04: the review graph (owner decision O4: a review only) ----

  @m9-04 @smoke @fake-broker
  Scenario: The review graph shows the result with the new parts marked
    Given the broker has the direct exchange "events" bound to the quorum queue "audit" with routing key "order.#"
    And a plan with the steps
      | Declare quorum queue orders              |
      | Bind events to orders with order.created |
    When I look at the review graph
    Then it shows the exchange "events" as already on the broker
    And it shows the queue "orders" marked "new"
    And it shows an edge from "events" to "orders" labelled "order.created" marked "new"
    And it does not show the queue "audit"

  @m9-04 @negative @fake-broker
  Scenario: Nothing can be changed on the review graph
    Given a plan with a queue, an exchange and a binding
    When I look at the review graph
    Then no control on the graph adds, changes, moves or removes a step

  @m9-04 @fake-broker
  Scenario: The graph follows the plan
    Given a plan with a queue, an exchange and a binding
    When I remove the binding step
    Then the review graph shows the queue and the exchange without an edge

  @m9-04 @fake-broker
  Scenario: A plan that is too large to draw says so and keeps the step list
    Given a plan that declares 61 quorum queues
    When I look at the review graph
    Then it says "This plan is too large to draw" and that the step list is the review
    And the step list shows all 61 steps

  @m9-04 @fake-broker
  Scenario: Without a connection only the plan is drawn
    Given I am not connected
    And a plan that binds "events" to the new queue "orders"
    When I look at the review graph
    Then it says "Only the plan is drawn"
    And the exchange "events" is drawn as "not in this plan"

  @m9-04 @fake-broker
  Scenario: Graph nodes can be reached by the keyboard and say what is new
    Given a plan that declares the quorum queue "orders"
    When I move through the review graph with the keyboard
    Then the node "orders" is announced as "queue orders, new"

  @m9-04 @fake-broker
  Scenario: When the broker cannot be read only the plan is drawn
    Given the broker cannot be read
    And a plan that binds "events" to the new queue "orders"
    When I look at the review graph
    Then it says "Only the plan is drawn" and that the broker could not be read
    And the exchange "events" is drawn as "not in this plan"
    And the queue "orders" is marked "new"

  @m9-04 @fake-broker
  Scenario: Something the plan declares and the broker already has is not marked new
    Given the broker has the quorum queue "orders"
    And a plan that declares the quorum queue "orders"
    When I look at the review graph
    Then it shows the queue "orders" as already on the broker

  @m9-04 @smoke @fake-broker
  Scenario: The review card shows the graph first and the JSON text on request
    Given a plan with a queue, an exchange and a binding
    When I look at the review card
    Then the graph is shown
    When I switch the review to JSON
    Then the migration text with the version "draft" is shown and can be copied

  # ---- M9-12: a pending migration file opened as steps ----

  @m9-12 @smoke @fake-broker
  Scenario: A pending migration opens as steps
    Given a pending migration "0005" that declares the quorum queue "orders" and binds "events" to it with routing key "order.created"
    When I choose "Edit steps" for "0005"
    Then the planner lists these steps in this order
      | Declare quorum queue orders              |
      | Bind events to orders with order.created |
    And the planner says it is editing "0005"

  @m9-12 @fake-broker
  Scenario: Saving the steps of a pending file writes the same version
    Given the pending migration "0005" is open in the planner
    When I add a quorum queue "invoices" with the wizard
    And I choose "Save 0005"
    Then the file "0005.json" holds the actions of the three steps
    And no other file was written
    And "0005" is still pending for "uat-eu"

  @m9-12 @fake-broker
  Scenario: Opening and closing a file without a change does not rewrite it
    Given the pending migration "0005" is open in the planner
    When I go back to the Migrations screen without a change
    Then "0005.json" still has exactly its original bytes
    And no question was asked

  @m9-12 @fake-broker
  Scenario: Leaving with unsaved changes asks first
    Given the pending migration "0005" is open in the planner and I removed a step
    When I go back to the Migrations screen
    Then I am asked "Discard the changes to 0005?"
    And choosing to keep editing leaves the planner open with my change

  @m9-12 @fake-broker
  Scenario: Steps the wizard cannot build are kept as written
    Given a pending migration "0006" that declares the quorum queue "jobs" with the argument "x-single-active-consumer" true, deletes the queue "legacy-orders" and declares the operator policy "op-limit"
    When I choose "Edit steps" for "0006"
    Then the three steps are shown in plain words and marked read only
    And I can move and remove them but not edit them
    When I add a quorum queue "invoices" with the wizard and choose "Save 0006"
    Then the file still declares "jobs" with "x-single-active-consumer" as the boolean true, deletes "legacy-orders" and declares the operator policy "op-limit"

  @m9-12 @negative @fake-broker
  Scenario: A migration that is applied on any connection of this machine is not edited
    Given "0002" is applied to "prod-eu" on this machine and pending for "uat-eu"
    When I select "0002" on the Migrations screen
    Then "Edit steps" is not offered and I am told "0002 is applied on prod-eu, so it is not edited"
    And I can choose "Copy into a new plan"
    When I choose "Copy into a new plan"
    Then a new plan holds the steps of "0002"
    And "0002.json" still has exactly its original bytes

  @m9-12 @negative @security @fake-broker
  Scenario: A file with password hashes is never opened in the planner
    Given a pending migration "0001" that declares the user "svc" with a password hash
    When I select "0001" on the Migrations screen
    Then "Edit steps" is not offered and the text reads "This file holds password hashes. Edit it outside the app."
    And no draft was written

  @m9-12 @negative @fake-broker
  Scenario: A file with virtual hosts or permissions is not opened in the planner
    Given a pending migration "0001" that declares the virtual host "orders" and sets permissions
    When I select "0001" on the Migrations screen
    Then "Edit steps" is not offered and the text reads "This file holds virtual hosts, users or permissions. The planner works on topology only."

  @m9-12 @negative @fake-broker
  Scenario: A file with entries the planner does not know is edited as JSON only
    Given a pending migration "0007" whose queue action also holds the key "comment"
    When I select "0007" on the Migrations screen
    Then "Edit steps" is not offered and the text reads "This file holds entries the planner does not know. Edit it as JSON so nothing is lost."
    And "Edit file" is still offered

  @m9-12 @negative @fake-broker
  Scenario: A file that changed on disk while it was open is not overwritten
    Given the pending migration "0005" is open in the planner and I added a step
    And "0005.json" was changed on disk by someone else
    When I choose "Save 0005"
    Then I am told "0005.json changed on disk since it was opened" and that it was not overwritten
    And "0005.json" holds the other person's content

  @m9-12 @fake-broker
  Scenario: The raw JSON editor stays beside "Edit steps"
    Given a pending migration "0005"
    When I select "0005" on the Migrations screen
    Then I can choose "Edit steps" and "Edit file"

  @m9-12 @fake-broker
  Scenario: Editing a file leaves the draft of a new plan alone
    Given a plan with 3 steps
    And the pending migration "0005" is open in the planner
    When I save "0005" and open the Migrations screen
    Then "Continue plan" still offers the draft with 3 steps

  @m9-12 @fake-broker
  Scenario: Discarding the changes leaves the file as it was
    Given the pending migration "0005" is open in the planner and I removed a step
    When I go back to the Migrations screen
    And I choose "Discard changes"
    Then the planner is closed
    And "0005.json" still has exactly its original bytes
    And no other file was written

  @m9-12 @fake-broker
  Scenario: Copying into a new plan asks first when a draft with steps exists
    Given a plan with 3 steps
    And "0002" is applied to "prod-eu" on this machine and pending for "uat-eu"
    When I select "0002" on the Migrations screen
    And I choose "Copy into a new plan" and am asked "Dismiss the current plan and copy 0002 into a new plan?"
    And I answer "Keep the plan"
    Then "Continue plan" still offers the draft with 3 steps
    And "0002.json" still has exactly its original bytes

  @m9-12 @fake-broker
  Scenario: Confirming the copy replaces the draft with the steps of the file
    Given a plan with 3 steps
    And "0002" is applied to "prod-eu" on this machine and pending for "uat-eu"
    When I select "0002" on the Migrations screen
    And I choose "Copy into a new plan" and am asked "Dismiss the current plan and copy 0002 into a new plan?"
    And I answer "Dismiss and copy"
    Then a new plan holds the steps of "0002"
    And "0002.json" still has exactly its original bytes

  @m9-12 @negative @security @fake-broker
  Scenario: A file with password hashes is not copied into a plan either
    Given a pending migration "0001" that declares the user "svc" with a password hash
    When I select "0001" on the Migrations screen
    Then "Copy into a new plan" is not offered
    And no draft was written

  @m9-12 @negative @fake-broker
  Scenario: A file with entries the planner does not know is not copied into a plan either
    Given a pending migration "0007" whose queue action also holds the key "comment"
    When I select "0007" on the Migrations screen
    Then "Copy into a new plan" is not offered
    And no draft was written

  # ---- M9-12, gap 10 of M9-08: two policy steps of one name (wording decided by the owner on 2026-11-18, spec O20) ----

  @m9-12 @fake-broker
  Scenario: Two policy steps of one name with other settings are refused
    Given a plan with the policy "orders-dlx" whose setting "max-length" is 1000
    And the plan also holds a second policy "orders-dlx" whose setting "max-length" is 2000
    Then the second step carries the problem "The plan already declares the policy orders-dlx with other settings"
    And "Save as migration" is disabled

  @m9-12 @fake-broker
  Scenario: Two identical policy steps are pointed out but do not block
    Given a plan with the policy "orders-dlx" whose setting "max-length" is 1000
    And the plan also holds a second policy "orders-dlx" whose setting "max-length" is 1000
    Then the second step carries the note "The plan declares the policy orders-dlx twice with the same settings"
    And "Save as migration" is enabled

  @m9-12 @fake-broker
  Scenario: A hand-written file with two policies of one name opens with the problem
    Given a pending migration "0008" that declares the policy "orders-dlx" twice with different settings
    When I choose "Edit steps" for "0008"
    Then the second step carries the problem "The plan already declares the policy orders-dlx with other settings"
    And "Save 0008" is disabled
