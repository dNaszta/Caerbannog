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
