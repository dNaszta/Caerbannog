@M10 @access-view
Feature: Access view, part 1 and 2: who am I, and permissions per virtual host
  As an engineer who runs or uses a RabbitMQ broker
  I want to see which user the connection signs in as, what that user may do on each virtual host,
  and whether it may publish to or read from a given name
  So that I can understand the authorization design without opening the Management UI

  The view is read-only (spec "Access view (M10)", non-goals): nothing here changes a user, tag,
  permission or virtual host; changes go through the Topology planner and migrations.
  Management API field names for whoami, vhosts, permissions and topic permissions are measured by
  the spike M10-00 (S-A, S-B); scenarios tagged @needs-spike depend on it. Wording of every
  visible text is an owner decision (spec "Access view (M10)", questions); scenarios assert meaning,
  not exact sentences, unless a sentence is quoted.

  Background:
    Given the connection "prod-eu" is the held connection
    And the connection user is "svc-orders" with the tag "monitoring"
    And the broker has the virtual hosts "/", "/orders" and "/billing"
    And the user "svc-orders" has permissions on "/orders" only

  # ---------------------------------------------------------------- M10-02 Who am I

  @smoke @fake-broker @m10-02
  Scenario: Who am I shows the user, the tags and the visible virtual hosts
    When I open the Access view
    Then I see the connection user "svc-orders"
    And I see the tag "monitoring"
    And I see the virtual hosts the broker lists for this user
    And I see which virtual host the connection itself uses

  @fake-broker @m10-02
  Scenario: A user without a management tag gets a message and no data
    Given the connection user has no management tag
    When I open the Access view
    Then I see a message that the management tag is needed
    And no virtual host, permission or client is listed

  @security @safety @fake-broker @m10-02
  Scenario: The Access view has no control that changes anything
    When I open the Access view
    Then there is no control to edit or delete a user, a tag, a permission or a virtual host
    And there is no control to close a connection or a channel
    And there is no control that gets, consumes or purges queue messages

  @security @fake-broker @m10-02
  Scenario: Nothing is read before the user has chosen the connection
    Given no connection is held
    When I open the Access view
    Then I see that no connection is held
    And no request was sent to any broker

  @security @fake-broker @m10-02
  Scenario: A profile without TLS shows Not encrypted on the Access view
    Given the held connection "prod-plain" has "Use TLS" switched off
    When I open the Access view
    Then I see "Not encrypted" on the view
    And every request used the profile's own management port and scheme

  @fake-broker @m10-02
  Scenario: A lost connection keeps the last data and says so
    Given the Access view showed the virtual hosts
    When the connection to the broker is lost
    Then the last data stays on screen marked as stale
    And I see the "Connection lost" banner

  @fake-broker @m10-02
  Scenario: The virtual host list cannot be read
    Given the broker answers the virtual host list with an error
    When I open the Access view
    Then I still see the connection user and the tags
    And I see that the virtual host list could not be read

  # Owner decision AQ6 (2026-11-25): not offered on a 3.x connection. The drawer has no Access item;
  # if the held connection becomes 3.x while Access is open, the app returns to Connections with a
  # one-line notice. "Needs a 4.x broker" appears only in that notice.
  @negative @fake-broker @m10-02
  Scenario: The Access item is not offered for a RabbitMQ 3.x connection
    Given the connection "legacy-3x" is a RabbitMQ 3.x broker and is the held connection
    Then the drawer has no Access item
    And no request was sent to the 3.x broker

  @negative @fake-broker @m10-02
  Scenario: A connection that becomes 3.x while Access is open returns to Connections with a notice
    Given the Access view is open on the connection "prod-eu"
    When the held connection becomes the RabbitMQ 3.x connection "legacy-3x"
    Then I am on the Connections screen
    And I see a one-line notice that the Access view needs a 4.x broker
    And no request was sent to the 3.x broker by the Access view

  # ---------------------------------------------------------------- M10-03 Permissions table

  @smoke @fake-broker @m10-03
  Scenario: An administrator sees configure, write and read patterns per virtual host
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" configure "^queue\\..*", write "^ex\\..*" and read ".*"
    When I choose the user "svc-orders" on the Permissions tab
    Then I see for "/orders" the three patterns exactly as the broker returned them
    And the patterns are labelled as read from the broker

  @fake-broker @m10-03
  Scenario Outline: An empty pattern reads as no permission
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" configure "<configure>", write "<write>" and read "<read>"
    When I choose the user "svc-orders" on the Permissions tab
    Then the "<kind>" permission on "/orders" reads as "<reading>"

    Examples:
      | configure | write | read | kind      | reading       |
      |           | .*    | .*   | configure | no permission |
      | ^$        | .*    | .*   | configure | has a pattern |
      | .*        |       | .*   | write     | no permission |
      | .*        | .*    |      | read      | no permission |

  @fake-broker @m10-03
  Scenario: Topic permissions are listed per exchange
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write "^sensors\\..*" and read ".*"
    When I choose the user "svc-orders" on the Permissions tab
    Then I see the exchange "amq.topic" with its write pattern and read pattern for "/orders"

  @negative @fake-broker @m10-03
  Scenario: A user who is not an administrator is told that permissions cannot be read
    When I open the Permissions tab
    Then I see that only an administrator can read permissions
    And no permission pattern is shown
    And nothing is guessed from other calls

  @security @fake-broker @m10-03
  Scenario: A declared permission is never shown like a broker answer
    Given the profile "prod-eu" declares configure "no" and write "yes"
    And the broker could not be asked for permissions
    When I open the Permissions tab
    Then I see the declaration labelled as declared by me and not confirmed by the broker
    And it does not use the wording or style of a broker-read result

  @fake-broker @m10-03
  Scenario: An administrator may choose any user on the broker
    Given the connection user is "ops-admin" with the tag "administrator"
    And the broker has the users "svc-orders" and "svc-billing"
    When I open the Permissions tab
    Then I can choose either user
    And the list of users shows no password and no password hash

  @fake-broker @m10-03
  Scenario: A user with no permission on a virtual host is shown as having none
    Given the connection user is "ops-admin" with the tag "administrator"
    When I choose the user "svc-orders" on the Permissions tab
    Then "/billing" is shown with no permission for "svc-orders"

  # ---------------------------------------------------------------- M10-04 Match preview

  @smoke @fake-broker @m10-04
  Scenario: Publishing to an exchange is checked against the write pattern
    Given "svc-orders" has on "/orders" write "^ex\\..*"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "would match" and names the write pattern "^ex\\..*"
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders"
    Then the answer is "no match" and names the write pattern "^ex\\..*"

  @fake-broker @m10-04
  Scenario: Reading from a queue is checked against the read pattern
    Given "svc-orders" has on "/orders" read "^queue\\..*"
    When I ask whether "svc-orders" can read from the queue "queue.orders" on "/orders"
    Then the answer is "would match" and names the read pattern "^queue\\..*"

  @fake-broker @m10-04
  Scenario Outline: A pattern is applied as RabbitMQ applies it
    Given "svc-orders" has on "/orders" write "<pattern>"
    When I ask whether "svc-orders" can publish to the exchange "<name>" on "/orders"
    Then the answer is "<answer>"

    # rows are placeholders until the spike M10-00 (S-I) measures anchoring and the empty pattern
    Examples:
      | pattern | name      | answer      |
      | ex      | ex.orders | would match |
      | ex      | my-ex     | no match    |
      | ^ex$    | ex.orders | no match    |
      |         | ex.orders | no match    |
      | .*      |           | would match |

  @needs-spike @fake-broker @m10-04
  Scenario: The default exchange is asked about by the name the broker uses for it
    Given "svc-orders" has on "/orders" write "^amq\\.default$"
    When I ask whether "svc-orders" can publish to the default exchange on "/orders"
    Then the answer follows what the spike M10-00 measured for the default exchange

  @fake-broker @m10-04
  Scenario: A topic permission is checked against the routing key
    Given "svc-orders" has on "/orders" write ".*"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write "^sensors\\..*"
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" with the routing key "sensors.temp"
    Then the answer is "would match" and names the topic permission
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" with the routing key "alarms.fire"
    Then the answer is "no match" and names the topic permission

  @negative @fake-broker @m10-04
  Scenario: A pattern that cannot be read is reported, not guessed
    Given "svc-orders" has on "/orders" write "([unclosed"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "pattern could not be read"
    And the app does not fail

  @safety @fake-broker @m10-04
  Scenario: The preview sends nothing to the broker
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then no message was published and no queue was read
    And the answer is labelled as a prediction and says the broker decides

  @fake-broker @m10-04
  Scenario: The preview says where the permission came from
    Given the permissions were read from the broker
    When I ask whether "svc-orders" can read from the queue "queue.orders" on "/orders"
    Then the answer says the patterns were read from the broker

  @gate-M10 @broker @m10-04
  Scenario: The preview agrees with a real broker for a table of cases
    Given a real broker with the user "preview-user" and permissions and topic permissions from the case table
    When every case is asked of the preview and then tried on the broker
    Then for every case the preview and the broker give the same answer
