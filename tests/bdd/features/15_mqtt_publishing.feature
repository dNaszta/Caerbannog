@M6 @mqtt @publish
Feature: Publish an MQTT message
  As an engineer
  I want to send an MQTT message with topic, QoS and retain flag
  So that I can verify MQTT client subscriptions and message routing

  Background:
    Given I am connected to "prod-eu" with MQTT port 1883

  @smoke @fake-broker
  Scenario: Publish an MQTT message with QoS 0
    When I publish an MQTT message to "sensors/temperature" with QoS 0 and payload "22.5"
    Then the MQTT broker receives the message on "sensors/temperature" with QoS 0
    And the message is not retained
    And I see an MQTT publish confirmation "Message published to 'sensors/temperature' (QoS 0)"

  @fake-broker
  Scenario: Publish an MQTT message with QoS 1
    When I publish an MQTT message to "sensors/humidity" with QoS 1 and payload "55%"
    Then the MQTT broker receives the message on "sensors/humidity" with QoS 1
    And the message is not retained
    And I see an MQTT publish confirmation "Message published to 'sensors/humidity' (QoS 1)"

  @fake-broker
  Scenario: Publish an MQTT message with QoS 2 and retain flag
    When I publish an MQTT message to "config/device-01" with QoS 2, retain true and payload "active"
    Then the MQTT broker receives the message on "config/device-01" with QoS 2
    And the message is retained
    And I see an MQTT publish confirmation "Message published to 'config/device-01' (QoS 2, retained)"

  @negative @fake-broker
  Scenario: Publish to a blank topic is rejected
    When I publish an MQTT message to "   " with QoS 0 and payload "empty"
    Then the MQTT publish is refused with reason "Topic cannot be blank"

  @negative @fake-broker
  Scenario: Publish to a topic containing wildcards is rejected
    When I publish an MQTT message to "sensors/+/temp" with QoS 1 and payload "bad"
    Then the MQTT publish is refused with reason "Topic cannot contain wildcards '+' or '#'"

  @negative @fake-broker
  Scenario: MQTT broker refuses publication
    Given the MQTT broker will refuse publication with "Not authorized"
    When I publish an MQTT message to "restricted/topic" with QoS 1 and payload "secret"
    Then the MQTT publish is refused with reason "Not authorized"

  # M7-10: the dialog's headers and default message, through the real PublishMqttViewModel. Headers are MQTT 5 user properties.
  @fake-broker @headers
  Scenario: Headers are sent as user properties, in the order given, and keys may repeat
    Given I open the MQTT publish dialog for "sensors/room1"
    And I add these MQTT headers
      | trace-id | abc-123 |
      | tenant   | acme    |
      | trace-id | second  |
    When I publish the dialog message
    Then the MQTT broker receives the message on "sensors/room1" with these headers
      | trace-id | abc-123 |
      | tenant   | acme    |
      | trace-id | second  |

  @fake-broker @headers
  Scenario: Header rows without a key are ignored
    Given I open the MQTT publish dialog for "sensors/room1"
    And I add these MQTT headers
      |          | orphan value |
      | trace-id | abc-123      |
    When I publish the dialog message
    Then the MQTT broker receives the message on "sensors/room1" with these headers
      | trace-id | abc-123 |

  @fake-broker @headers
  Scenario: A message without headers is sent without user properties
    Given I open the MQTT publish dialog for "sensors/room1"
    When I publish the dialog message
    Then the MQTT broker receives the message on "sensors/room1" without headers

  @fake-broker
  Scenario: The dialog opens with an editable sample message
    Given I open the MQTT publish dialog for "sensors/room1"
    Then the dialog payload is the sample message
    When I change the dialog payload to "21.5"
    And I publish the dialog message
    Then the MQTT broker receives the message on "sensors/room1" with payload "21.5"

  # M9-10e (docs/tasks/M9-10e-mqtt-publish-gates-and-dialog-fold.md): the older Publish MQTT entry points obey the write permission like
  # "Publish test message" and "Send a test message" do. Same text everywhere: core.publish.noWritePermission(vhost). Without an MQTT port the
  # entry points stay absent, as before. The MQTT port is checked first, then the permission. An unfinished permission check counts as no permission.
  @m9-10e @negative @fake-broker
  Scenario: Publishing to MQTT is disabled without write permission on the stream screen
    Given the user has no write permission on "/"
    When I open the stream "orders-events" that is fed by the MQTT topic "orders/#"
    Then the "Publish MQTT" action of the stream is disabled
    And the "Publish MQTT" action of the route "orders/#" is disabled
    And each of them says "Test publishing is disabled: no write permission on virtual host /."

  @m9-10e @negative @fake-broker
  Scenario: Publishing to MQTT is disabled without write permission on the topology screen
    Given the user has no write permission on "/"
    When I open the MQTT tab of the topology screen
    Then the "Publish message" action is disabled
    And it says "Test publishing is disabled: no write permission on virtual host /."

  @m9-10e @negative @fake-broker
  Scenario: Publishing to MQTT is disabled without write permission on the pipeline graph
    Given the user has no write permission on "/"
    When I open the stream "orders-events" that is fed by the MQTT topic "orders/#"
    Then the MQTT route node of the pipeline graph is disabled
    And it says "Test publishing is disabled: no write permission on virtual host /."

  @m9-10e @negative @fake-broker
  Scenario Outline: The reason names the virtual host
    Given the user has no write permission on "<vhost>"
    When I open the MQTT tab of the topology screen
    Then the "Publish message" action is disabled
    And it says "Test publishing is disabled: no write permission on virtual host <vhost>."

    Examples:
      | vhost  |
      | /      |
      | orders |

  @m9-10e @negative @fake-broker
  Scenario: An unfinished permission check disables it
    Given the permission check of "prod-eu" has not finished
    When I open the MQTT tab of the topology screen
    Then the "Publish message" action is disabled
    And it says "Test publishing is disabled: no write permission on virtual host /."

  @m9-10e @fake-broker
  Scenario: With write permission and an MQTT port the entry points open the dialog
    Given the user may publish on "/"
    When I open the stream "orders-events" that is fed by the MQTT topic "orders/#"
    Then the "Publish MQTT" action of the stream is enabled
    And the "Publish MQTT" action of the route "orders/#" is enabled
    And the MQTT route node of the pipeline graph is enabled
    When I open the MQTT tab of the topology screen
    Then the "Publish message" action is enabled

  @m9-10e @fake-broker
  Scenario: Without an MQTT port the entry points are absent, whatever the permission
    Given I am connected to "prod-eu" with MQTT port 0
    And the user may publish on "/"
    When I open the stream "orders-events" that is fed by the MQTT topic "orders/#"
    Then there is no "Publish MQTT" action on the stream screen
    When I open the MQTT tab of the topology screen
    Then there is no "Publish message" action

  @m9-10e @negative @fake-broker
  Scenario: A disabled entry point does not open the dialog
    Given the user has no write permission on "/"
    When I open the MQTT tab of the topology screen
    And I try to press "Publish message"
    Then the Publish MQTT dialog is not open
    And nothing was published over MQTT

