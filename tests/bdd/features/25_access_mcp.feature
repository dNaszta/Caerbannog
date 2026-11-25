@M10 @access-view @mcp @security @fake-broker
Feature: Model Context Protocol tools for the Access view
  As an AI developer assistant
  I want read-only tools that tell me who the connection user is, what it may do and who is connected
  So that I can explain an access problem without ever being able to change access or see a secret

  Decision (architect, 2026-11-25, owner leaning confirmed in the brief M10-08): three read-only tools,
  no tool that changes access. Same redaction as the screen, from the same core code. The tools use the
  saved profile's own TLS setting; there is no fallback.

  Background:
    Given the app holds a profile "prod-eu" for host "broker.internal" vhost "/" with TLS on
    And the broker reports the connection user "svc-orders" with the tag "monitoring"
    And the profile's password is a JWT

  @smoke @m10-08
  Scenario: The tools list has the three Access tools and no tool that changes access
    When the client requests the list of MCP tools
    Then the tools list contains "caerbannog_get_access_summary"
    And the tools list contains "caerbannog_check_access"
    And the tools list contains "caerbannog_list_clients"
    And the tools list contains no tool that creates, edits or deletes a user, tag, permission, topic permission or virtual host
    And the tools list contains no tool that closes a connection
    And the tools list contains no tool that gets, consumes, acknowledges or purges queue messages
    And the tools list contains no tool that creates or removes a Shovel

  @m10-08
  Scenario: The summary names the user, tags, virtual hosts and sign-in method
    When the client calls "caerbannog_get_access_summary" for "prod-eu"
    Then the result has the user "svc-orders", the tag "monitoring", the virtual hosts and the sign-in method
    And the result says that it shows what RabbitMQ resolved and not the identity provider's setup

  @m10-08
  Scenario: Permissions are returned only when the user may read them
    When the client calls "caerbannog_get_access_summary" for "prod-eu" with permissions
    Then the result says that only an administrator can read permissions
    And no pattern is returned
    And the tool did not try another credential

  @m10-08
  Scenario: Permissions of an administrator are returned with their source
    Given the broker reports the connection user "ops-admin" with the tag "administrator"
    And "svc-orders" has on "/orders" write "^ex\\..*"
    When the client calls "caerbannog_get_access_summary" for "prod-eu" with permissions for the user "svc-orders"
    Then the result has the write pattern "^ex\\..*" for "/orders" labelled as read from the broker

  @m10-08
  Scenario: The access check answers as a prediction
    Given "svc-orders" has on "/orders" write "^ex\\..*"
    When the client calls "caerbannog_check_access" for "prod-eu" with the user "svc-orders", the action "publish", the virtual host "/orders" and the exchange "ex.orders"
    Then the result is "would match" with the pattern
    And the result says that it is a prediction and the broker decides
    And no message was published

  @negative @m10-08
  Scenario: The access check rejects an unknown action
    When the client calls "caerbannog_check_access" for "prod-eu" with the action "purge"
    Then the result is an error that lists the allowed actions
    And nothing was sent to the broker

  @m10-08
  Scenario: The client list is grouped by user and bounded
    Given the broker has 2300 connections
    When the client calls "caerbannog_list_clients" for "prod-eu"
    Then the result is grouped by user with counts
    And the result holds at most the documented number of rows and says how many were left out

  @needs-spike @m10-08
  Scenario: The client list for one queue or stream shows publishers and consumers
    When the client calls "caerbannog_list_clients" for "prod-eu" with the queue "orders"
    Then the result lists the consumers with connection, channel and user
    And the publishers are listed as far as the broker tells

  @security @m10-08
  Scenario Outline: No tool output contains a token, a password or a secret-looking value
    Given a connection whose "connection_name" is a JWT and another whose client property holds "amqps://svc:hunter2@broker.internal"
    When the client calls "<tool>" for "prod-eu"
    Then the output contains no JWT, no part of the profile's password, no URI with credentials and no "hunter2"

    Examples:
      | tool                          |
      | caerbannog_get_access_summary |
      | caerbannog_list_clients       |
      | caerbannog_check_access       |

  @security @m10-08
  Scenario: A profile without TLS is used as saved and reported as not encrypted
    Given the app holds a profile "prod-plain" for host "prod.internal" vhost "/" with TLS off
    When the client calls "caerbannog_get_access_summary" for "prod-plain"
    Then the request used the plain management port of the profile
    And the result says that the connection is not encrypted

  @security @m10-08
  Scenario: A broker error text is not passed on if it carries a secret
    Given the broker answers with an error body that contains the profile's password
    When the client calls "caerbannog_get_access_summary" for "prod-eu"
    Then the result is a generic error without the body
