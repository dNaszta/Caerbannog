# M7-10: MQTT headers (MQTT 5 user properties) and an editable default payload

Milestone: M7    Depends on: M6-04, M6-05, M6-06    Size: M    Commits: 85987cc (core, data-rabbit, dialog), 09f1d91 (MCP `headers` argument)

## Goal
The Publish MQTT dialog opens with an editable sample payload and lets the user add headers that are sent as MQTT 5 user properties; the MCP tool `caerbannog_publish_mqtt` takes the same headers.

## Why
Spec "MQTT support (M6)". Lets the owner check that headers reach a stream message.

## Scope (what shipped)
In:
- `MqttHeader(key, value)`; `PublishMqttRequest.headers`; `PublishMqttState.DEFAULT_PAYLOAD` = `{"temperature": 21.5, "unit": "C"}`; `PublishMqttViewModel.addHeader`, `setHeader`, `removeHeader`. On publish, rows with a blank key are dropped and keys are trimmed; keys may repeat; values may be empty.
- `PublishMqttDialog`: payload prefilled (placeholder "Message body"); Headers section with key and value fields and a remove button per row.
- `RabbitMqMqttPublisher` now uses the Eclipse Paho MQTT 5 client (`org.eclipse.paho.mqttv5.client:1.2.5`, replaces the MQTT 3.1.1 client; the v3 client stays a test dependency for the subscriber in `RealBrokerMqttIntegrationTest`). The seam is the internal `MqttWire` interface (connect, publish, disconnectForcibly, close) with `PahoMqttWire` as the real one, so unit tests use a fake wire. Clean start; password as bytes; user properties attached only when headers exist. Mapping: not authorized and bad credentials give Refused; server connect error and timeout give Failed.
- MCP: optional `headers` object (string keys and string values); a malformed value (non-string value, array, blank key) returns "Invalid 'headers'..." with `isError` and publishes nothing; the result reports the header count.
Out: MQTT 5 properties other than user properties; headers on queue or stream publish (AMQP).

## Acceptance
- `core/.../mqtt/PublishMqttViewModelTest`: "payload starts with an editable default message that setPayload replaces"; "headers can be added, edited and removed, and publish sends only rows with a key".
- `data-rabbit/.../RabbitMqMqttPublisherTest` (rewritten against `MqttWire`): options, cleanup, NOT_AUTHORIZED gives Refused, SERVER_CONNECT_ERROR and CLIENT_TIMEOUT give Failed, "headers are sent as MQTT 5 user properties in order, and none are attached when there are no headers".
- `data-rabbit/.../RealBrokerMqttHeadersTest` (`@Tag("broker")`): "MQTT 5 user properties published through amq topic reach the stream message" (also checks a headerless publish carries none).
- `ui/.../publish/PublishMqttDialogUiTest`: "a prefilled default payload can be edited in place"; "header rows render and emit add, edit and remove events".
- `mcp-server/.../McpServerTest`: "publish mqtt tool passes headers as user properties and rejects malformed headers".
Scenarios in `tests/bdd/features/15_mqtt_publishing.feature` were not extended.

## Design notes
The headers need RabbitMQ 4 or later (MQTT 5). Plain or TLS still follows the profile (D9a); the password stays in the publisher object only.

## UX
Dialog from M6-05; no new file in `docs/ux/`.

## MCP
`headers` added to `caerbannog_publish_mqtt` (see above). Safety rules are those of M6-06 (see `M6-06-mqtt-mcp-tools.md`); this task did not change them and I did not re-verify them.

## Non-goals
Subscribing; retained-message deletion.

## Open points
A JSON object cannot repeat a key, so MCP callers cannot send repeated headers (the UI can). Behaviour against RabbitMQ 3.x (no MQTT 5) is not tested; the user would see a Failed or Refused outcome.

## Status
Accepted by the product owner 2026-11-09. Retroactive brief: the work was built outside the team loop; this describes the shipped behaviour as read from the code and tests.
