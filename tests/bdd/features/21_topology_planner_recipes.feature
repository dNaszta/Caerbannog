@M9 @planner @recipes
Feature: Streams, policies, dead-lettering and MQTT routes in the planner
  As an engineer
  I want the wizard to build the usual patterns for me
  So that a stream with its response stream, a policy, dead-lettering for a queue or an MQTT route is a few fields, not a set of JSON actions

  Every kind below only adds ordinary migration actions to the plan (declareStream, declarePolicy,
  declareExchange, declareQueue, declareBinding). What a pattern does on the broker is measured by the spikes
  M9-00a, M9-00b and M9-00c before its form is built; scenarios tagged @needs-spike state the expected
  behaviour and are corrected to the measured one. The owner answered the open questions Q1 to Q16 on
  2026-11-15 (docs/spec.md, "Topology planner (M9)", "Owner answers to the questions"). The one tag
  @needs-decision that remains is on the scenarios of Q3 revised (@m9-07b, the owner's IoT flow, to be confirmed
  at the review of the mockup) and on the one scenario of Q17 (the key of the correlation-id header).

  Sub-tags: @m9-07 streams and the optional response stream (DLN), @m9-07b the optional MQTT routes of a
  stream pair, @m9-08 policies, @m9-09 dead-lettering for a queue, @m9-10 MQTT routes and their test
  message, @m9-11 bindings on a headers exchange, @m9-13 the gate.

  Background:
    Given I am connected to "uat-eu" with the virtual host "/"
    And the user has configure permission on "/"
    And the connection "uat-eu" has the topology control level "Declare and edit"
    And the naming style is "kebab-case"
    And a new plan

  # ---- M9-07: streams and the response stream (owner decisions O9, O10, O11) ----

  @m9-07 @smoke @fake-broker
  Scenario: A stream is one step
    When I add a stream "telemetry" with the wizard
    Then the plan lists "Declare stream telemetry" and nothing else

  @m9-07 @fake-broker
  Scenario: The response stream is optional and off until I tick it
    When I open the stream form
    Then "Also create its response stream (DLN)" is not ticked

  @m9-07 @smoke @fake-broker
  Scenario: A stream can get its response stream in the same wizard step
    When I add a stream "telemetry" with the wizard and tick "Also create its response stream (DLN)"
    Then the summary says "This adds 2 steps"
    And the plan lists these steps in this order
      | Declare stream telemetry     |
      | Declare stream telemetry-dln |
    And the step "Declare stream telemetry-dln" is shown as the response stream (DLN) of "telemetry"

  @m9-07 @fake-broker
  Scenario Outline: The response stream name is suggested with a suffix in the naming style
    Given the naming style is "<style>"
    When I enter the stream name "orders" and tick "Also create its response stream (DLN)"
    Then the response stream name is suggested as "<suggested>"

    Examples:
      | style         | suggested  |
      | kebab-case    | orders-dln |
      | snake_case    | orders_dln |
      | dot.separated | orders.dln |
      | camelCase     | ordersDln  |
      | No convention | orders-dln |

  @m9-07 @fake-broker
  Scenario: The suggested name follows the stream name until I change it
    When I enter the stream name "orders" and tick "Also create its response stream (DLN)"
    And I change the stream name to "invoices"
    Then the response stream name is suggested as "invoices-dln"
    When I change the response stream name to "invoices-replies"
    And I change the stream name to "bills"
    Then the response stream name is still "invoices-replies"

  @m9-07 @fake-broker
  Scenario: A response stream with a name of my own gets a hint and is still added
    When I add a stream "orders" with the response stream named "Orders_Replies"
    Then the response stream name field showed the hint "This name does not follow kebab-case, for example orders-replies"
    And the plan lists "Declare stream orders" and "Declare stream Orders_Replies"

  @m9-07 @fake-broker
  Scenario: A stream pair is two streams and no routing unless an MQTT tick is set
    When I add a stream "telemetry" with the wizard and tick "Also create its response stream (DLN)"
    Then the plan holds no exchange, no binding and no policy

  @m9-07 @needs-spike @fake-broker
  Scenario: The wizard says what a response stream is
    When I tick "Also create its response stream (DLN)" in the stream form
    Then the form says "A response stream is an ordinary stream. RabbitMQ does not move messages into it by itself: your applications write to it."

  @m9-07 @fake-broker
  Scenario: A saved pair is shown as a pair again when its name follows the rule
    Given a pending migration "0005" that declares the streams "telemetry" and "telemetry-dln"
    When I choose "Edit steps" for "0005"
    Then the step "Declare stream telemetry-dln" is shown as the response stream (DLN) of "telemetry"

  @m9-07 @fake-broker
  Scenario: Two streams whose names do not follow the rule are two streams
    Given a pending migration "0005" that declares the streams "telemetry" and "telemetry-replies"
    When I choose "Edit steps" for "0005"
    Then neither step is shown as a response stream

  @m9-07 @fake-broker
  Scenario: The review graph links a stream with its response stream
    When I add a stream "telemetry" with the wizard and tick "Also create its response stream (DLN)"
    Then the review graph shows "telemetry" and "telemetry-dln" marked "new" and linked as "response (DLN)"

  @m9-07 @fake-broker
  Scenario: Removing the stream does not remove its response stream silently
    Given a plan with the stream "telemetry" and its response stream "telemetry-dln"
    When I remove the step "Declare stream telemetry"
    Then the plan still lists "Declare stream telemetry-dln"
    And it is no longer shown as a response stream

  # ---- M9-07b: the optional MQTT routes of a stream pair (owner decision O13, Q3 revised) ----
  # The owner's flow: a request is a message in the request stream with the destination device id in a header;
  # the owner's own service turns it into an MQTT message for the device; the device answers on a fixed topic
  # with its id in it (devices/<id>/answer); the answer lands in the response stream and is processed from there;
  # request and answer are matched by a correlation id in the message, which the planner never touches.
  # The planner plans the two streams and the routes. It plans no bridge from a stream to an MQTT message.
  # DECIDED (revised) by the architect from the owner's description; the owner confirms it at the mockup review.

  @m9-07b @needs-decision @fake-broker
  Scenario: The MQTT ticks are offered only with the response stream and are off until I tick them
    When I open the stream form
    Then "Answers arrive over MQTT" and "Requests are also recorded from an MQTT topic" are not offered
    When I tick "Also create its response stream (DLN)"
    Then both are offered and neither is ticked

  @m9-07b @smoke @needs-decision @needs-spike @fake-broker
  Scenario: Answers arrive over MQTT adds one route into the response stream
    When I add a stream "device-commands" with its response stream and tick "Answers arrive over MQTT" with the topic filter "devices/+/answer"
    Then the summary says "This adds 3 steps"
    And the plan lists these steps in this order
      | Declare stream device-commands                            |
      | Declare stream device-commands-dln                        |
      | MQTT route devices/+/answer to stream device-commands-dln |
    And the route is the action "declareBinding" from "amq.topic" to "device-commands-dln" with routing key "devices.*.answer"

  @m9-07b @needs-decision @needs-spike @fake-broker
  Scenario: Requests are also recorded from an MQTT topic adds one route into the request stream
    When I add a stream "device-commands" with its response stream and tick "Requests are also recorded from an MQTT topic" with the topic filter "commands/#"
    Then the plan lists these steps in this order
      | Declare stream device-commands                  |
      | Declare stream device-commands-dln              |
      | MQTT route commands/# to stream device-commands |

  @m9-07b @needs-decision @needs-spike @fake-broker
  Scenario: Both ticks add two routes, the answers route first
    When I add a stream "device-commands" with its response stream, tick "Answers arrive over MQTT" with the topic filter "devices/+/answer" and tick "Requests are also recorded from an MQTT topic" with the topic filter "commands/#"
    Then the summary says "This adds 4 steps"
    And the plan lists these steps in this order
      | Declare stream device-commands                            |
      | Declare stream device-commands-dln                        |
      | MQTT route devices/+/answer to stream device-commands-dln |
      | MQTT route commands/# to stream device-commands           |

  @m9-07b @needs-decision @fake-broker
  Scenario: The wizard says what it does not plan
    When I add a stream "device-commands" with its response stream and tick "Answers arrive over MQTT" with the topic filter "devices/+/answer"
    Then the summary says "Your own service turns a request into an MQTT message; the planner does not plan that."

  @m9-07b @needs-decision @needs-spike @fake-broker
  Scenario: The routes of a pair are ordinary steps
    Given a plan with the stream pair "device-commands" and the answers route "devices/+/answer" added by the wizard
    When I remove the step "MQTT route devices/+/answer to stream device-commands-dln"
    Then the plan lists "Declare stream device-commands" and "Declare stream device-commands-dln"
    And the plan has no problem

  @m9-07b @negative @needs-decision @fake-broker
  Scenario Outline: The topic filter of a tick is checked like an MQTT route
    When I tick "<tick>" in the stream form and enter the topic filter "<filter>"
    Then the form says "<message>"
    And "Next" is disabled

    Examples:
      | tick                                          | filter           | message                        |
      | Answers arrive over MQTT                      |                  | Enter a topic filter           |
      | Answers arrive over MQTT                      | devices/#/answer | # must be the last level       |
      | Requests are also recorded from an MQTT topic | sens+/x          | + must stand for a whole level |

  @m9-07b @needs-decision @fake-broker
  Scenario: Without the response stream the MQTT ticks go away
    Given the stream form with the response stream and "Answers arrive over MQTT" ticked
    When I untick "Also create its response stream (DLN)"
    Then the MQTT ticks are not offered any more
    And the summary holds the stream only

  @m9-07b @needs-decision @fake-broker
  Scenario: The review graph shows a note where the owner's service sits
    When I add a stream "device-commands" with its response stream and tick "Answers arrive over MQTT" with the topic filter "devices/+/answer"
    Then the review graph shows an edge from "amq.topic" to "device-commands-dln" labelled "devices/+/answer" and "MQTT route"
    And it shows the note "Your own service turns a request into an MQTT message; the planner does not plan that." between "device-commands" and "amq.topic"
    And the note is not a step and cannot be changed

  @m9-07b @needs-decision @fake-broker
  Scenario: No note without an MQTT route into a response stream
    Given a plan that declares the stream "telemetry"
    When I add a stream "device-commands" with its response stream
    Then the review graph shows no note about a service
    When I add an MQTT route from "sensors/#" to the stream "telemetry" with the wizard
    Then the review graph still shows no note about a service

  @m9-07b @needs-decision @needs-spike @fake-broker
  Scenario: A saved pair with its answers route is recognised again
    Given a pending migration "0005" that declares the streams "device-commands" and "device-commands-dln" and binds "amq.topic" to "device-commands-dln" with routing key "devices.*.answer"
    When I choose "Edit steps" for "0005"
    Then the step "Declare stream device-commands-dln" is shown as the response stream (DLN) of "device-commands"
    And the planner lists "MQTT route devices/+/answer to stream device-commands-dln"
    And the review graph shows the note about the owner's service

  @m9-07b @broker @needs-decision @needs-spike
  Scenario: A device answer published over MQTT lands in the response stream, with its correlation id where the processing can read it
    Given a real RabbitMQ 4 broker with the MQTT plugin
    And a plan with the stream "device-commands", its response stream and the answers route "devices/+/answer"
    When the plan is saved and applied by the migration runner
    And a device "d42" publishes an MQTT message with the correlation id "c-1" to "devices/d42/answer"
    Then the stream "device-commands-dln" holds one message more and the stream "device-commands" none
    And the message carries the correlation id where the measured rule says it appears

  # ---- M9-08: policies (owner decision O7) ----

  @m9-08 @smoke @fake-broker
  Scenario: A policy is built from a form
    When I add a policy with the wizard
      | name       | orders-limits |
      | pattern    | ^orders       |
      | applies to | Quorum queues |
      | priority   | 1             |
    And I give it the settings
      | Maximum number of messages | 1000        |
      | When the queue is full     | refuse new  |
    Then the plan lists "Declare policy orders-limits" with the detail "apply-to=quorum_queues, priority=1; max-length=1000, overflow=reject-publish"

  @m9-08 @fake-broker
  Scenario: Policy values keep their type in the saved file
    Given a plan with the policy "orders-limits" whose setting "max-length" is 1000
    When I choose "Save as migration"
    Then the saved file holds "max-length" as the number 1000, not as text

  @m9-08 @needs-spike @fake-broker
  Scenario Outline: The form offers only the settings measured for what the policy applies to
    When I open the policy form and choose that it applies to "<target>"
    Then the settings offered include "<offered>"
    And the settings offered do not include "<not offered>"

    Examples:
      | target        | offered                    | not offered                |
      | Quorum queues | Maximum number of messages | Keep messages for          |
      | Quorum queues | Delivery limit             | Keep messages for          |
      | Streams       | Keep messages for          | Delivery limit             |
      | Streams       | Keep at most (bytes)       | Dead-letter exchange       |

  @m9-08 @negative @fake-broker
  Scenario Outline: A policy form that cannot be added says why
    When I fill the policy form with <input>
    Then the form says "<message>"
    And "Next" is disabled

    Examples:
      | input                                             | message                         |
      | an empty name                                     | Give the policy a name          |
      | an empty pattern                                  | Enter a pattern                 |
      | the value "many" for "Maximum number of messages" | Enter a whole number            |
      | no setting                                        | Add at least one setting        |
      | the priority "high"                               | Enter a whole number            |

  @m9-08 @fake-broker
  Scenario: The form shows which names of the plan and of the broker the pattern matches
    Given the broker has the quorum queue "orders-archive"
    And a plan that declares the quorum queues "orders" and "invoices"
    When I enter the pattern "^orders" in the policy form
    Then the form says it matches "orders" in this plan and "orders-archive" on "uat-eu"

  @m9-08 @negative @fake-broker
  Scenario: A pattern the app cannot evaluate is said so and can still be added
    When I enter the pattern "(?=orders)" in the policy form
    Then the form says "The app cannot tell which names this pattern matches"
    And "Next" is enabled

  @m9-08 @needs-spike @fake-broker
  Scenario: Two policies of a plan that match the same queue are pointed out
    Given a plan that declares the quorum queue "orders" and the policy "orders-dlx" on "^orders$"
    When I add the policy "orders-limits" on "^orders" with the wizard
    Then both policy steps show the note "The policies orders-dlx and orders-limits both match orders. RabbitMQ applies only one policy to a queue: put the settings into one policy."
    And "Save as migration" is still enabled

  @m9-08 @needs-spike @fake-broker
  Scenario: A policy that would take the place of a policy on the broker is pointed out by the check
    Given the broker has the quorum queue "orders" with the policy "limits" applied
    And a plan that declares the policy "orders-dlx" on "^orders$" with priority 10
    When I check the plan against "uat-eu"
    Then the step "Declare policy orders-dlx" shows "Will be created"
    And it shows the note "The broker applies the policy limits to orders. Only one policy applies to a queue: this one would take its place."

  @m9-08 @needs-spike @fake-broker
  Scenario: Retention of a stream is saved as a policy
    When I add a stream "telemetry" with the wizard and set "Keep messages for" to "7D"
    Then the plan lists these steps in this order
      | Declare stream telemetry           |
      | Declare policy telemetry-retention |
    And the policy applies to streams, matches exactly "telemetry" and holds "max-age" "7D"

  @m9-08 @needs-spike @fake-broker
  Scenario: The response stream gets the same retention unless I untick it
    When I add a stream "telemetry" with its response stream and set "Keep messages for" to "7D"
    Then the policy "telemetry-retention" matches "telemetry" and "telemetry-dln" and nothing else
    When I untick "Same retention for the response stream" before adding
    Then the policy "telemetry-retention" matches "telemetry" only

  @m9-08 @negative @fake-broker
  Scenario: Operator policies are not built by the wizard
    When I open the wizard
    Then there is no way to add an operator policy

  # ---- M9-09: dead-lettering for a queue ("DLX"; architect decision A4: by policy) ----

  @m9-09 @smoke @needs-spike @fake-broker
  Scenario: Dead-lettering for a queue adds four steps, the policy last
    Given the broker has the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard
    Then the summary says "This adds 4 steps"
    And the plan lists these steps in this order
      | Declare fanout exchange orders-dlx |
      | Declare quorum queue orders-dlq    |
      | Bind orders-dlx to orders-dlq      |
      | Declare policy orders-dlx          |
    And the policy applies to quorum queues and holds "dead-letter-exchange" "orders-dlx"

  @m9-09 @fake-broker
  Scenario: The queue itself is not declared or changed
    Given the broker has the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard
    Then the plan holds no step that declares "orders"
    And the summary says "The queue orders itself is not changed."

  @m9-09 @fake-broker
  Scenario Outline: The policy matches exactly the chosen queue
    When I add dead-lettering for the queue "<queue>" with the wizard
    Then the policy pattern is "<pattern>"
    And the pattern matches "<queue>" and does not match "<other>"

    Examples:
      | queue      | pattern        | other        |
      | orders     | ^orders$       | orders-eu    |
      | orders.eu  | ^orders\.eu$   | ordersXeu    |
      | jobs+retry | ^jobs\+retry$  | jobssretry   |

  @m9-09 @fake-broker
  Scenario Outline: The dead-letter names are suggested with suffixes in the naming style
    Given the naming style is "<style>"
    When I choose the queue "orders" in the dead-lettering form
    Then the dead-letter exchange is suggested as "<exchange>" and the dead-letter queue as "<queue>"

    Examples:
      | style         | exchange   | queue      |
      | kebab-case    | orders-dlx | orders-dlq |
      | snake_case    | orders_dlx | orders_dlq |
      | dot.separated | orders.dlx | orders.dlq |
      | camelCase     | ordersDlx  | ordersDlq  |

  @m9-09 @fake-broker
  Scenario: Dead-lettering for a queue of the same plan comes after the queue
    Given a plan that declares the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard
    Then the four dead-lettering steps come after "Declare quorum queue orders"
    And the plan has no problem

  @m9-09 @needs-spike @fake-broker
  Scenario: Dead letters can go to a stream
    Given the broker has the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard and choose a stream as the target
    Then the plan lists these steps in this order
      | Declare fanout exchange orders-dlx |
      | Declare stream orders-dlq          |
      | Bind orders-dlx to orders-dlq      |
      | Declare policy orders-dlx          |

  @m9-09 @needs-spike @fake-broker
  Scenario: At least once needs my tick and says what it changes
    Given the broker has the quorum queue "orders"
    When I choose "At least once" in the dead-lettering form for "orders"
    Then "Next" is disabled until I tick "Publishers are refused when the queue is full"
    When I tick it and finish the wizard
    Then the policy holds "dead-letter-strategy" "at-least-once" and "overflow" "reject-publish"

  @m9-09 @needs-spike @fake-broker
  Scenario: At most once is the default and adds no strategy setting
    Given the broker has the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard
    Then the policy holds "dead-letter-exchange" and no "dead-letter-strategy" and no "overflow"

  @m9-09 @negative @needs-spike @fake-broker
  Scenario: At least once is not offered for a classic queue
    Given the broker has the classic queue "legacy"
    When I choose the queue "legacy" in the dead-lettering form
    Then "At least once" is not offered

  @m9-09 @needs-spike @fake-broker
  Scenario: A queue that already has a policy is pointed out before the recipe is added
    Given the broker has the quorum queue "orders" with the policy "limits" applied
    When I choose the queue "orders" in the dead-lettering form
    Then the form says "The broker applies the policy limits to orders. Only one policy applies to a queue: this one would take its place."

  @m9-09 @fake-broker
  Scenario: The review graph shows the dead-letter path
    Given the broker has the quorum queue "orders"
    When I add dead-lettering for the queue "orders" with the wizard
    Then the review graph shows a dashed "dead letters" edge from "orders" to "orders-dlx" and an edge from "orders-dlx" to "orders-dlq"
    And "orders" is drawn as already on the broker

  @m9-09 @fake-broker
  Scenario: The four steps are ordinary steps
    Given a plan with dead-lettering for the queue "orders"
    When I remove the step "Bind orders-dlx to orders-dlq"
    Then the other three steps stay
    And the plan has no problem

  # ---- M9-10: MQTT routes (owner decision O2; architect decision A10: one binding on amq.topic) ----

  @m9-10 @smoke @needs-spike @fake-broker
  Scenario Outline: An MQTT route is saved as a binding on amq.topic
    Given a plan that declares the stream "telemetry"
    When I add an MQTT route from "<filter>" to the stream "telemetry" with the wizard
    Then the plan lists "MQTT route <filter> to stream telemetry"
    And the step is the action "declareBinding" from "amq.topic" to "telemetry" with routing key "<key>"

    Examples:
      | filter            | key               |
      | sensors/+/temp    | sensors.*.temp    |
      | sensors/#         | sensors.#         |
      | devices/d1/status | devices.d1.status |
      | devices/+/answer  | devices.*.answer  |
      | #                 | #                 |

  @m9-10 @fake-broker
  Scenario: An MQTT route can lead to a queue or to a stream
    Given a plan that declares the quorum queue "alarms" and the stream "telemetry"
    When I add an MQTT route from "alarms/#" to the queue "alarms" with the wizard
    And I add an MQTT route from "sensors/#" to the stream "telemetry" with the wizard
    Then the plan lists "MQTT route alarms/# to queue alarms" and "MQTT route sensors/# to stream telemetry"

  @m9-10 @negative @fake-broker
  Scenario Outline: A topic filter that MQTT does not allow is refused in the form
    When I enter the topic filter "<filter>" in the MQTT route form
    Then the form says "<message>"
    And "Next" is disabled

    Examples:
      | filter         | message                         |
      |                | Enter a topic filter            |
      | sensors/#/temp | # must be the last level        |
      | sensors/te#    | # must be the last level        |
      | sens+/temp     | + must stand for a whole level  |

  @m9-10 @negative @needs-spike @fake-broker
  Scenario Outline: A topic filter that cannot be translated safely is refused
    When I enter the topic filter "<filter>" in the MQTT route form
    Then the form says "This filter cannot be translated safely"
    And "Next" is disabled

    Examples:
      | filter            |
      | sensors/v1.2/temp |
      | sensors/*/temp    |
      | sensors//temp     |

  @m9-10 @fake-broker
  Scenario: The form shows the binding it will write and a topic that matches
    When I enter the topic filter "sensors/+/temp" in the MQTT route form
    Then the form says it is saved as a binding on amq.topic with the key "sensors.*.temp"
    And it gives "sensors/item/temp" as a topic that matches
    And it says "Uses the broker's default MQTT exchange amq.topic."

  @m9-10 @fake-broker
  Scenario: A saved MQTT route is shown as an MQTT route again
    Given a pending migration "0005" that declares the stream "telemetry" and binds "amq.topic" to it with routing key "sensors.*.temp"
    When I choose "Edit steps" for "0005"
    Then the planner lists "Declare stream telemetry" and "MQTT route sensors/+/temp to stream telemetry"

  @m9-10 @fake-broker
  Scenario: An MQTT route to something the planner does not know is named without its kind
    Given I am not connected
    And a pending migration "0005" that binds "amq.topic" to "telemetry" with routing key "sensors.#"
    When I choose "Edit steps" for "0005"
    Then the planner lists "MQTT route sensors/# to telemetry"

  @m9-10 @fake-broker
  Scenario Outline: A binding on amq.topic that is not a plain MQTT route stays a binding
    Given a pending migration "0005" with a binding from "amq.topic" <binding>
    When I choose "Edit steps" for "0005"
    Then the step is shown as a binding, not as an MQTT route
    And saving writes the binding back unchanged

    Examples:
      | binding                                                                      |
      | to the exchange "fan-out" with routing key "sensors.#"                       |
      | to the queue "alarms" with routing key "sensors.#" and the argument "x-note" |
      | to the queue "alarms" with routing key "sensors..temp"                       |

  @m9-10 @fake-broker
  Scenario: The review graph labels an MQTT route with its topic filter
    Given a plan that declares the stream "telemetry"
    When I add an MQTT route from "sensors/+/temp" to the stream "telemetry" with the wizard
    Then the review graph shows an edge from "amq.topic" to "telemetry" labelled "sensors/+/temp" and "MQTT route"

  # A15 (architect addition, the owner sees it at the mockup review): the test message asks for a sample value
  # for every wildcard of the filter, then opens the existing Publish MQTT dialog, which accepts no wildcards.
  @m9-10 @smoke @fake-broker
  Scenario: After applying, a test message asks for a sample value and opens the dialog with a concrete topic
    Given I am connected to "uat-eu" with MQTT port 1883 and the user may publish
    And the migration "0005" with the MQTT route "devices/+/answer" to the stream "device-commands-dln" is applied to "uat-eu"
    When I choose "Send a test message" for that route on the Migrations screen
    Then I am asked for a value for the "+" in level 2, which starts as "item"
    When I enter "d42"
    Then the Publish MQTT dialog opens with the topic "devices/d42/answer"
    And nothing is published until I press Publish

  @m9-10 @fake-broker
  Scenario: A route without wildcards opens the dialog at once
    Given I am connected to "uat-eu" with MQTT port 1883 and the user may publish
    And the migration "0005" with the MQTT route "devices/d1/status" to the stream "telemetry" is applied to "uat-eu"
    When I choose "Send a test message" for that route on the Migrations screen
    Then no value is asked for
    And the Publish MQTT dialog opens with the topic "devices/d1/status"

  @m9-10 @fake-broker
  Scenario: A hash asks for the rest of the topic
    Given I am connected to "uat-eu" with MQTT port 1883 and the user may publish
    And the migration "0005" with the MQTT route "sensors/#" to the stream "telemetry" is applied to "uat-eu"
    When I choose "Send a test message" for that route on the Migrations screen
    Then I am asked for a value for the rest of the topic, which starts as "test"
    When I enter "room1/temp"
    Then the Publish MQTT dialog opens with the topic "sensors/room1/temp"

  @m9-10 @negative @fake-broker
  Scenario Outline: A sample value that would not make a valid topic is refused
    Given the test message of the route "devices/+/answer" asks for the value of the "+"
    When I enter "<value>"
    Then it says "<message>"
    And the dialog is not opened

    Examples:
      | value | message                  |
      |       | Enter a value            |
      | d/42  | One level only: no /     |
      | d+42  | No + or # in a value     |
      | d#42  | No + or # in a value     |

  @m9-10 @needs-decision @needs-spike @fake-broker
  Scenario: The dialog opens with a correlation-id header that is generated and can be changed
    Given I am connected to "uat-eu" with MQTT port 1883 and the user may publish
    And the migration "0005" with the MQTT route "devices/+/answer" to the stream "device-commands-dln" is applied to "uat-eu"
    When I choose "Send a test message" for that route and enter "d42"
    Then the dialog holds one header "correlation-id" with a generated value
    And it is an MQTT 5 user property like every header of the dialog
    When I open the test message again and enter "d42"
    Then the generated value is a different one
    And nothing was published

  @m9-10 @negative @fake-broker
  Scenario Outline: The test message is not offered where publishing is not possible
    Given <situation>
    And the migration "0005" with the MQTT route "sensors/+/temp" to the stream "telemetry" is applied to "uat-eu"
    When I select "0005" on the Migrations screen
    Then "Send a test message" is disabled and says "<reason>"

    Examples:
      | situation                        | reason                                                           |
      | the connection has no MQTT port  | This connection has no MQTT port                                 |
      | the user has no write permission | Test publishing is disabled: no write permission on virtual host /. |

  @m9-10 @negative @fake-broker
  Scenario: A route of a migration that is not applied here offers no test message
    Given the pending migration "0005" with the MQTT route "sensors/+/temp" to the stream "telemetry"
    When I select "0005" on the Migrations screen
    Then "Send a test message" is not offered

  @m9-10 @broker @needs-spike
  Scenario: A message published over MQTT to a planned route reaches a stream and a queue
    Given a real RabbitMQ 4 broker with the MQTT plugin
    And a plan with the stream "telemetry", the quorum queue "alarms" and the MQTT routes "sensors/+/temp" to "telemetry" and "alarms/#" to "alarms"
    When the plan is saved and applied by the migration runner
    And one MQTT message is published to "sensors/room1/temp" and one to "alarms/fire/floor2"
    Then the stream "telemetry" holds one message more and the queue "alarms" holds one message
    And a message published to "sensors/room1/humidity" reaches neither

  # ---- M9-11: bindings on a headers exchange ----

  @m9-11 @smoke @needs-spike @fake-broker
  Scenario: A binding on a headers exchange carries its headers and the match rule
    Given a plan that declares the headers exchange "documents" and the quorum queue "pdf-reports"
    When I add a binding from "documents" to the queue "pdf-reports" that matches all of the headers
      | format | pdf    |
      | type   | report |
    Then the plan lists "Bind documents to pdf-reports" with the detail "format=pdf, type=report, x-match=all"
    And the binding form showed no routing key field

  @m9-11 @needs-spike @fake-broker
  Scenario: Header values keep their type in the saved file
    Given a plan with a headers binding whose header "pages" is the number 10 and whose header "signed" is true
    When I choose "Save as migration"
    Then the saved file holds "pages" as the number 10 and "signed" as the boolean true

  @m9-11 @fake-broker
  Scenario: Two bindings that differ only in their headers are two steps
    Given a plan that declares the headers exchange "documents" and the quorum queue "reports"
    When I add a binding from "documents" to "reports" that matches the header "format" "pdf"
    And I add a binding from "documents" to "reports" that matches the header "format" "csv"
    Then the plan has both bindings and no problem

  @m9-11 @negative @needs-spike @fake-broker
  Scenario: A headers binding without a header says what it would match
    Given a plan that declares the headers exchange "documents" and the quorum queue "reports"
    When I open the binding form for "documents" and add no header
    Then the form says what such a binding matches, as measured on a real broker

  @m9-11 @fake-broker
  Scenario: A fanout binding has no routing key field
    Given a plan that declares the fanout exchange "broadcast" and the quorum queue "audit"
    When I open the binding form and choose the exchange "broadcast"
    Then the form shows no routing key field

  # ---- M9-13: the gate ----

  @m9-13 @broker @gate-M9
  Scenario: A plan built in the planner does on a real broker what its steps say
    Given a real RabbitMQ 4 broker with the MQTT plugin and a quorum queue "orders" that already exists
    And a plan built through the planner with a direct exchange and a binding to "orders", dead-lettering for "orders", a stream "telemetry" with its response stream, a retention policy and an MQTT route "sensors/+/temp" to "telemetry"
    When the plan is checked
    Then every new step shows "Will be created" and the check sent GET requests only
    When the plan is saved as a migration and applied by the existing migration runner
    Then the migration is recorded as applied and the check shows "Already there" for every step
    And a message published over MQTT to "sensors/room1/temp" arrives in the stream "telemetry"
    And a message that expires in "orders" arrives in the dead-letter queue
    And the stream "telemetry-dln" exists and is empty
    And nothing was deleted and no queue message was read by the app
