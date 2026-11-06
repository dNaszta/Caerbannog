@safety @security @fake-broker
Feature: Model Context Protocol (MCP) server
  As an AI developer assistant or coding agent
  I want to inspect RabbitMQ connection profiles, streams, and topology via MCP tools
  So that I can verify broker state without compromising security or reading private queue messages

  Background:
    Given the app holds a profile "dev-local" for host "localhost" vhost "/" with TLS on
    And the app holds a profile "prod-plain" for host "prod.internal" vhost "/" with TLS off
    And the stream "orders" on vhost "/" has 1500 messages, first offset 0, last offset 1499, retention 7D
    And the queue "orders-dlq" on vhost "/" is a "quorum" queue with 42 messages
    And the exchange "amq.direct" on vhost "/" is a "direct" exchange
    And the binding from "amq.direct" to "orders-dlq" with routing key "failed-order" exists

  Scenario: MCP server initialize handshake and capabilities
    When the client sends the MCP "initialize" request with protocol version "2024-11-05"
    Then the MCP server responds with protocol version "2024-11-05"
    And the server info name is "caerbannog-mcp"
    And the server advertises "tools" capability

  Scenario: MCP server lists available tools and never offers queue message reading
    When the client requests the list of MCP tools
    Then the tools list contains "caerbannog_list_profiles"
    And the tools list contains "caerbannog_check_connection"
    And the tools list contains "caerbannog_list_streams"
    And the tools list contains "caerbannog_get_stream_details"
    And the tools list contains "caerbannog_list_queues"
    And the tools list contains "caerbannog_list_exchanges"
    And the tools list contains "caerbannog_list_bindings"
    And the tools list does not contain any tool that reads or purges queue messages

  Scenario: MCP server lists configured connection profiles without revealing secrets
    When the client calls MCP tool "caerbannog_list_profiles"
    Then the tool result lists profile "dev-local" with TLS on
    And the tool result lists profile "prod-plain" with TLS off
    And the tool result contains no passwords or secret tokens

  Scenario: MCP server checks a connection profile
    When the client calls MCP tool "caerbannog_check_connection" with profile "dev-local"
    Then the tool result reports reachability "reachable"
    And the tool result reports topology level "Read-only"

  Scenario: MCP server lists streams for a profile
    When the client calls MCP tool "caerbannog_list_streams" with profile "dev-local" and vhost "/"
    Then the tool result includes stream "orders" with 1500 messages and retention "7D"

  Scenario: MCP server lists queues and exchanges for a profile
    When the client calls MCP tool "caerbannog_list_queues" with profile "dev-local" and vhost "/"
    Then the tool result includes queue "orders-dlq" of type "quorum" with 42 messages
    When the client calls MCP tool "caerbannog_list_exchanges" with profile "dev-local" and vhost "/"
    Then the tool result includes exchange "amq.direct" of type "direct"

  Scenario: MCP server handles unknown tool or missing profile with a structured error
    When the client calls an unknown MCP tool "caerbannog_delete_everything"
    Then the response is an MCP error with code -32601 and message "Method not found"
    When the client calls MCP tool "caerbannog_check_connection" with profile "nonexistent"
    Then the tool result indicates an error "Profile 'nonexistent' not found"
