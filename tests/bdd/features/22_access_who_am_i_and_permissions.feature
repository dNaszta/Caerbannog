@M10 @access-view
Feature: Access view, part 1 and 2: who am I, and permissions per virtual host
  As an engineer who runs or uses a RabbitMQ broker
  I want to see which user the connection signs in as, what that user may do on each virtual host,
  and whether it may publish to or read from a given name
  So that I can understand the authorization design without opening the Management UI

  The view is read-only (spec "Access view (M10)", non-goals): nothing here changes a user, tag,
  permission or virtual host; changes go through the Topology planner and migrations.
  Management API field names for whoami, vhosts, permissions and topic permissions were measured by
  the spike M10-00 (S-A, S-B, 2026-11-25; spec "Measured facts of M10-00"); no @needs-spike scenario is left here. Wording of every
  visible text is approved by the owner (2026-11-25, as drawn in docs/ux/Access.dc.html); scenarios assert meaning,
  not exact sentences, unless a sentence is quoted (the 3.x notice and the preview note are quoted).

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

  # M10-02b (architect review of M10-02, finding 1): stale data says why (D2).
  @fake-broker @m10-02b
  Scenario: An unreadable user while data is shown keeps the data and says so
    Given the Access view showed the virtual hosts
    When the broker answers the user with an error
    And 5 seconds pass
    Then the last data stays on screen marked as stale
    And I see that the user could not be read, with the broker's reason, and a Retry
    And the connection is not reported as lost
    When the broker answers the user again and I choose Retry
    Then I see the connection user "svc-orders"
    And the data is no longer marked as stale
    And I no longer see that the user could not be read

  @fake-broker @m10-02
  Scenario: The virtual host list cannot be read
    Given the broker answers the virtual host list with an error
    When I open the Access view
    Then I still see the connection user and the tags
    And I see that the virtual host list could not be read

  @fake-broker @m10-02
  Scenario: Who am I shows loading rows while the first read runs
    Given the broker has not answered yet
    When I open the Access view
    Then I see the loading text and loading rows, not a blank page
    And I see the note that this screen only reads
    When the broker answers
    Then I see the connection user "svc-orders"

  @fake-broker @m10-02
  Scenario: A broker that lists no virtual host for this user says so
    Given the broker lists no virtual host for this user
    When I open the Access view
    Then I see that the broker lists no virtual host for this user
    And I still see the connection user and the tags

  @fake-broker @m10-02
  Scenario: A token user is shown as the broker reports it
    Given the connection user is the token user "jdoe" with the tag "monitoring"
    When I open the Access view
    Then I see the connection user "jdoe"
    And I see that the name is as the broker reports it
    And I see the tag "monitoring"

  @security @fake-broker @m10-02
  Scenario: A refused sign-in is reported without echoing anything
    Given the broker refuses the sign-in
    When I open the Access view
    Then I see that the broker refused the sign-in
    And no password and no token text is shown
    And the connection is not reported as lost

  # Decision D3: a screen refreshes only while it is shown, with the app-wide interval.
  @fake-broker @m10-02
  Scenario: The Access view reads only while it is shown
    Given the Access view is not open
    When 60 seconds pass
    Then no request was sent to any broker
    When I open the Access view
    Then the broker was asked for the user and for the virtual hosts
    When I leave the Access view
    And the number of requests is noted
    And 60 seconds pass
    Then no further request was sent

  # Decision D2: the screen never reconnects by itself.
  @fake-broker @m10-02
  Scenario: A lost connection is not reconnected by itself, Retry connects again
    Given the Access view showed the virtual hosts
    When the connection to the broker is lost
    And 10 minutes pass
    Then the app did not connect again by itself
    When the broker is back and I choose Retry
    Then the connection is held again
    And I see the connection user "svc-orders"
    And the data is no longer marked as stale

  @fake-broker @m10-02
  Scenario: Switching the connection clears the data of the previous one
    Given the Access view showed the virtual hosts
    And the connection "prod-plain" has the connection user "svc-billing" with the tag "management"
    When I choose the connection "prod-plain"
    Then I do not see the connection user "svc-orders"
    And I see the connection user "svc-billing"

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
    And I see the one-line notice "The Access view needs a 4.x broker."
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

  @fake-broker @m10-03
  Scenario: A user with no permission on any virtual host gets the empty message
    Given the connection user is "ops-admin" with the tag "administrator"
    And the broker has the user "svc-idle" with no permission on any virtual host
    When I choose the user "svc-idle" on the Permissions tab
    Then I see that "svc-idle" has no permissions on any virtual host
    And I do not see an error

  @fake-broker @m10-03
  Scenario: A token administrator lands on its own name with the token note
    Given whoami reports the connection user as not an internal user with the tag "administrator"
    And the broker answers 404 for the permissions of that user
    When I open the Permissions tab
    Then the chosen user is the connection user
    And I see that the permissions of this user come from the token and are not stored on the broker
    And the list of users offers the connection user besides the internal users
    And no permission pattern is shown
    And I do not see an error

  @fake-broker @m10-03
  Scenario: A token administrator may choose an internal user
    # PD-1 decided (owner, 2026-11-27, option a): the broker lets a token administrator read internal users' permissions (spike S-B).
    Given whoami reports the connection user as not an internal user with the tag "administrator"
    And the broker has the user "svc-orders" with permissions on "/orders"
    When I choose the user "svc-orders" on the Permissions tab
    Then I see for "/orders" the three patterns exactly as the broker returned them

  @security @fake-broker @m10-03
  Scenario: A user whose name looks like a token cannot be chosen
    Given the connection user is "ops-admin" with the tag "administrator"
    And the broker has a user whose name is a token
    When I open the Permissions tab
    Then the list of users shows that name as hidden and I cannot choose it
    And the token text appears nowhere on the screen

  @negative @fake-broker @m10-03
  Scenario: An unreadable permission list keeps the last data and says so
    Given the connection user is "ops-admin" with the tag "administrator"
    And I have chosen the user "svc-orders" on the Permissions tab
    When the next read of the permissions answers HTTP 500
    Then the last patterns stay on screen, marked as stale
    And I see that the permissions could not be read with a Retry button

  @security @safety @fake-broker @m10-03
  Scenario: The Permissions tab sends only GET requests
    Given the connection user is "ops-admin" with the tag "administrator"
    When I choose the user "svc-orders" on the Permissions tab
    Then every request sent to the broker was a GET
    And no request carried a password hash

  # ---------------------------------------------------------------- M10-04 Match preview

  @smoke @fake-broker @m10-04
  Scenario: Publishing to an exchange is checked against the write pattern
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^ex\\..*"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "would match" and names the write pattern "^ex\\..*"
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders"
    Then the answer is "no match" and names the write pattern "^ex\\..*"

  @fake-broker @m10-04
  Scenario: Reading from a queue is checked against the read pattern
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" read "^queue\\..*"
    When I ask whether "svc-orders" can read from the queue "queue.orders" on "/orders"
    Then the answer is "would match" and names the read pattern "^queue\\..*"

  @fake-broker @m10-04
  Scenario Outline: A pattern is applied as RabbitMQ applies it
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "<pattern>"
    When I ask whether "svc-orders" can publish to the exchange "<name>" on "/orders"
    Then the answer is "<answer>"

    # measured by the spike M10-00 (S-I, 137 rows in fixtures/m10-00/s-i-pattern-rows.tsv):
    # a pattern is an unanchored, case-sensitive regular-expression search; the empty pattern allows nothing
    Examples:
      | pattern | name      | answer      |
      | ex      | ex.orders | would match |
      | ex      | my-ex     | would match |
      | ^ex$    | ex.orders | no match    |
      | ^ex$    | ex        | would match |
      | ex      | EX        | no match    |
      | (?i)ex  | EX        | would match |
      | ^$      | ex.orders | no match    |
      |         | ex.orders | no match    |
      | .*      | ex.orders | would match |
      | ^[[:alpha:]]+$ | ex | would match |
      | ^\\d+$ | ex      | no match    |
      | (?<n>ex)       | ex.orders | would match |

  # measured (S-I): the default exchange is checked as "amq.default", never as the target queue
  @fake-broker @m10-04
  Scenario: The default exchange is asked about by the name the broker uses for it
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^amq\\.default$"
    When I ask whether "svc-orders" can publish to the default exchange on "/orders"
    Then the answer is "would match" and names the write pattern "^amq\\.default$"
    And the answer does not use the name of the target queue
    And the exchange box shows "amq.default"

  @fake-broker @m10-04
  Scenario: A pattern that fits the queue name does not allow the default exchange
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^queue$"
    When I ask whether "svc-orders" can publish to the default exchange on "/orders"
    Then the answer is "no match"

  # matcher level only (owner, PD-1, 2026-11-27): the form offers publish, read and configure; bind and the stream forms are asked of the matcher directly
  @fake-broker @m10-04
  Scenario: Binding a queue needs write on the queue and read on the exchange
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^queue\\..*" and read "^ex\\..*"
    When I ask whether "svc-orders" can bind the queue "queue.orders" to the exchange "ex.orders" on "/orders"
    Then the answer is "would match" and names the write pattern and the read pattern

  # matcher level only (PD-1); measured by part A: queue.bind checks the topic READ pattern on the binding key, basic.consume does not
  @fake-broker @m10-04
  Scenario: Binding a queue to an exchange with a topic permission checks the binding key against the topic read pattern
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*" and read ".*"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write ".*" and read "^sensors\\..*"
    When I ask whether "svc-orders" can bind the queue "queue.orders" to the exchange "amq.topic" on "/orders" with the routing key "alarms.fire"
    Then the answer is "no match" and names the topic read pattern
    When I ask whether "svc-orders" can bind the queue "queue.orders" to the exchange "amq.topic" on "/orders" with the routing key "sensors.temp"
    Then the answer is "would match" and names the write pattern, the read pattern and the topic read pattern

  @fake-broker @m10-04
  Scenario: A topic permission is checked against the routing key
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write "^sensors\\..*"
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" with the routing key "sensors.temp"
    Then the answer is "would match" and names the topic permission
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" with the routing key "alarms.fire"
    Then the answer is "no match" and names the topic permission

  @negative @fake-broker @m10-04
  Scenario: A pattern that cannot be read is reported, not guessed
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "([unclosed"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "pattern could not be read"
    And the app does not fail

  @safety @fake-broker @m10-04
  Scenario: The preview sends nothing to the broker
    Given the connection user is "ops-admin" with the tag "administrator"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then no message was published and no queue was read
    And the answer is labelled as a prediction and says "Prediction from the patterns above; the broker decides. Check sends nothing to the broker."

  @fake-broker @m10-04
  Scenario: Configuring is checked against the configure pattern
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" configure "^queue\\..*"
    When I ask whether "svc-orders" can configure the queue "queue.orders" on "/orders"
    Then the answer is "would match" and names the configure pattern "^queue\\..*"
    When I ask whether "svc-orders" can configure the queue "other" on "/orders"
    Then the answer is "no match" and names the configure pattern "^queue\\..*"

  @fake-broker @m10-04
  Scenario: A virtual host without a permission does not match
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/billing"
    Then the answer is "no match"
    And the answer says "svc-orders" has no permission on "/billing"

  # M10-04e (owner 2026-11-27): the empty pattern is a sentence, not a pattern named "no permission"
  @fake-broker @m10-04
  Scenario Outline: An empty pattern says the user has no such permission and nothing is allowed
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" configure ".*", write ".*" and read ".*"
    And "svc-orders" has on "/orders" <kind> ""
    When I ask whether "svc-orders" can <ask> on "/orders"
    Then the answer is "no match"
    And the answer says "svc-orders has no <kind> permission on /orders, so nothing is allowed."

    Examples:
      | kind      | ask                                           |
      | write     | publish to the exchange "ex.orders"           |
      | read      | read from the queue "queue.orders"            |
      | configure | configure the queue "queue.orders"            |

  @fake-broker @m10-04
  Scenario: An empty topic pattern says the user has no topic permission on the exchange
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write ""
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" with the routing key "orders.created"
    Then the answer is "no match"
    And the answer says "svc-orders has no topic write permission on amq.topic, so nothing is allowed."

  @fake-broker @m10-04
  Scenario: A pattern that is spelled "no permission" is shown as a pattern
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "no permission"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "no match"
    And the answer says "The write pattern no permission does not match ex.orders on /orders."

  # measured (M10-03): the guard replaces a secret-looking pattern by the word "hidden", which is itself a valid regular expression
  @security @fake-broker @m10-04
  Scenario: A hidden pattern is never applied
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" a write pattern that the guard hid
    When I ask whether "svc-orders" can publish to the exchange "hidden-exchange" on "/orders"
    Then the answer is "pattern could not be read"
    And the answer does not show the hidden pattern

  # the engine (RE2/J) rejects what it does not read; measured by RealBrokerAccessMatchTest on 4.4.1 (rows NEW-013 to NEW-021, NEW-034, NEW-051): lookaround, backreference,
  # possessive quantifier, the (?x) flag and \Z are rejected. Accepted and agreeing with the broker (so NOT here): [[:alpha:]], \d, named groups, \p{L}, \x41, (a+)+$
  @negative @fake-broker @m10-04
  Scenario Outline: A construct the matcher cannot be sure of is reported as unreadable
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "<pattern>"
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "pattern could not be read"

    Examples:
      | pattern        |
      | (?=ex)ex       |
      | ex(?!x)        |
      | (?<=x)ex       |
      | (e)x.*\\1      |
      | ex.*++         |
      | (?x) ex        |
      | ex\\Z          |

  # measured (NEW-065, KnownDivergences): with a non-ASCII name RE2/J folds Latin-1 letters under (?i) (byte C3 to E3) and the broker does not, so the answer would differ.
  # The other divergence, "$" with a name that ends in a line feed (NEW-047), cannot be typed in a Gherkin step. "." and "[" no longer differ: the byte adapter reads bytes as Erlang does
  @negative @fake-broker @m10-04
  Scenario: A name that the engine would read differently from the broker is reported as unreadable
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "(?i)Á"
    When I ask whether "svc-orders" can publish to the exchange "あ" on "/orders"
    Then the answer is "pattern could not be read"

  # measured (NEW-052, architect ruling 1 after step 3 part A): Erlang re counts bytes, so "é" (two bytes) is not one "."; the matcher reads it the same way and says so
  @fake-broker @m10-04
  Scenario: A non-ASCII name is counted in bytes as the broker counts it
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^.$"
    When I ask whether "svc-orders" can publish to the exchange "é" on "/orders"
    Then the answer is "no match"

  @fake-broker @m10-04
  Scenario Outline: The preview of a user whose patterns cannot be read says so
    Given <situation>
    When I ask whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    Then the answer is "permissions not readable"
    And no request was sent to the broker
    And Check is disabled

    Examples:
      | situation                                                      |
      | the connection user is "ops-reader" with the tag "monitoring"  |
      | "svc-orders" is a token user                                   |

  @security @fake-broker @m10-04
  Scenario: A typed name that is a secret is not echoed
    Given the connection user is "ops-admin" with the tag "administrator"
    And the connection password is "s3cret-token-value"
    And "svc-orders" has on "/orders" write ".*"
    When I ask whether "svc-orders" can publish to the exchange "s3cret-token-value" on "/orders"
    Then the answer does not contain "s3cret-token-value"

  @fake-broker @m10-04
  Scenario: The verdict is cleared when the shown permissions change
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And the broker has the user "svc-billing" with no permission on any virtual host
    And I asked whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    When I choose the user "svc-billing" on the Permissions tab
    Then no answer is shown

  @fake-broker @m10-04
  Scenario: The verdict is cleared when the patterns of the same user change
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And I asked whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    When the broker now returns for "svc-orders" on "/orders" the write pattern "^queue\\..*"
    And I read the permissions again
    Then no answer is shown

  # architect ruling R3 (2026-11-27): the sentence names the name and the host, so an edit of the question clears it
  @fake-broker @m10-04
  Scenario: The answer is cleared when the question is edited
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And I asked whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    When I change the name to "ex.other"
    Then no answer is shown

  @fake-broker @m10-04
  Scenario: The verdict is cleared when the connection state changes
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And I asked whether "svc-orders" can publish to the exchange "ex.orders" on "/orders"
    When the connection to the broker is lost
    Then no answer is shown

  # PD-3 (owner, 2026-11-27): the label of the name box follows the action; the routing key is for publish only
  @fake-broker @m10-04
  Scenario Outline: The name box is labelled by the action
    Given the connection user is "ops-admin" with the tag "administrator"
    When I open the Permissions tab
    And I choose the action "<action>" in the preview form
    Then the name box is labelled "<label>"
    And the routing key box is <routing key box>

    Examples:
      | action    | label    | routing key box |
      | publish   | Exchange | shown           |
      | read      | Queue    | not shown       |
      | configure | Name     | not shown       |

  # measured (NEW rows of M10-04 part A): an empty routing key against "^a\\..*" is refused by the broker, and ".*" allows it
  @fake-broker @m10-04
  Scenario: Asking about an exchange with a topic permission but no routing key uses an empty key
    Given the connection user is "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write ".*"
    And "svc-orders" has the topic permission on "/orders" for the exchange "amq.topic" with write "^sensors\\..*"
    When I ask whether "svc-orders" can publish to the exchange "amq.topic" on "/orders" without a routing key
    Then the answer is "no match" and names the topic permission

  @fake-broker @m10-04
  Scenario: The preview says where the permission came from
    Given the connection user is "ops-admin" with the tag "administrator"
    And the permissions were read from the broker
    When I ask whether "svc-orders" can read from the queue "queue.orders" on "/orders"
    Then the answer says the patterns were read from the broker

  @gate-M10 @broker @m10-04
  Scenario: The preview agrees with a real broker for a table of cases
    Given a real broker with the user "preview-user" and permissions and topic permissions from the case table
    When every case is asked of the preview and then tried on the broker
    Then for every case the preview and the broker give the same answer
