@M10 @access-view @oauth
Feature: Access view, part 3: how the broker signs users in (OAuth 2 and Keycloak awareness)
  As an engineer whose broker is secured with Keycloak (the rabbitmq_auth_backend_oauth2 plugin)
  I want the app to say that tokens are in use and show what RabbitMQ resolved for the user
  So that I understand the authorization design, without the app pretending to know the Keycloak setup

  Honesty rule (spec "Access view (M10)"): the management API shows only what RabbitMQ resolved.
  It does not show the Keycloak client, its roles or its mappers, and the view never claims it does.
  The app never calls the identity provider. A JWT, a client secret and the held password never
  appear on screen, in logs, in error text, in copied text or in MCP output.
  What RabbitMQ exposes for a token user was measured by the spike M10-00 (S-A to S-C, S-J, 2026-11-25):
  outcome B, no resolved permissions; the backend cannot be detected, a token user can.

  Background:
    Given the connection "prod-eu" is the held connection

  # Spike M10-00 (S-C, S-A): the OAuth 2 backend itself cannot be detected from the management API.
  # A token user is recognised by whoami "is_internal_user" = false; the plugin being loaded is visible
  # to an administrator or monitoring user in /api/nodes. Wording is approved by the owner (2026-11-25, as
  # drawn in docs/ux/Access.dc.html); exact strings are asserted only for the short, stable ones (token statement,
  # refusal, expiry); the long explanatory sentences stay meaning-only on purpose, because they are likely to be edited.

  @smoke @fake-broker @m10-05
  Scenario: A token user is told that the connection signs in with a token
    Given whoami reports the connection user as not an internal user
    When I open the Sign-in tab of the Access view
    Then I see "This connection signs in with a token from an identity provider."

  @fake-broker @m10-05
  Scenario: The view says what it can and cannot show
    Given whoami reports the connection user as not an internal user
    When I open the Sign-in tab of the Access view
    Then I see that the view shows what RabbitMQ resolved for the user
    And I see that the identity provider's own setup, such as clients, roles and mappers, is not shown here

  @fake-broker @m10-05
  Scenario: An internal user on a broker with the OAuth 2 plugin loaded is told what the view cannot tell
    Given whoami reports the connection user as an internal user
    And the node list shows the OAuth 2 plugin as loaded
    When I open the Sign-in tab of the Access view
    Then I see that the OAuth 2 plugin is loaded
    And I see that this view cannot tell whether the broker uses it
    And I do not see the statement that tokens are in use

  @fake-broker @m10-05
  Scenario: An internal user on a broker without the OAuth 2 plugin does not claim the opposite
    Given whoami reports the connection user as an internal user
    And the node list does not show the OAuth 2 plugin
    When I open the Sign-in tab of the Access view
    Then I do not see the statement that tokens are in use
    And I do not see a statement that the broker uses only its own users

  @negative @fake-broker @m10-05
  Scenario: The plugin list cannot be read for this user
    Given the connection user may not read the node list
    When I open the Sign-in tab of the Access view
    Then I see that it could not be told whether the OAuth 2 plugin is loaded
    And I see no guess
    And a token user is still told that the connection signs in with a token

  @fake-broker @m10-05
  Scenario: For a token user, what RabbitMQ gives is shown and labelled
    Given whoami reports the token user "jdoe" with the tag "monitoring"
    And the broker lists for this user the virtual hosts "/orders" only
    When I open the Sign-in tab of the Access view
    Then I see the user, the tag and the virtual hosts
    And they are labelled as resolved by RabbitMQ from the token

  @fake-broker @m10-05
  Scenario: The view says that the permissions of a token user cannot be listed
    Given whoami reports the connection user as not an internal user
    And the broker answers 404 for the permissions of that user
    When I open the Sign-in tab of the Access view
    Then I see that the permissions come from the token and are not stored on the broker
    And I see that this view cannot list them
    And the Permissions tab does not show a pattern table for the token user

  @security @fake-broker @m10-05
  Scenario: The token never appears
    Given the held password is a JWT
    And the broker reports that the OAuth 2 backend is in use
    When I open the Sign-in tab of the Access view
    Then the token, and any part of it, is shown nowhere on the screen
    And it is absent from copied text, logs and error messages

  @security @fake-broker @m10-05
  Scenario: The app makes no request to the identity provider
    Given the broker reports that the OAuth 2 backend is in use
    When I open the Sign-in tab of the Access view
    Then the only requests made went to the profile's own broker

  # Measured (S-L): a fresh connect with an expired, badly signed or unknown token gets the same
  # 401 Not_Authorized as a wrong password, so "expired" is never certain here. The words are approved by the owner.
  @negative @fake-broker @m10-05
  Scenario: A token the broker refuses is reported without echoing it
    Given the held password is a JWT that the broker refuses at connect
    When I open the Access view
    Then I see "Sign-in refused." and "The token may have expired or be wrong."
    And the message does not contain the token

  @negative @fake-broker @m10-05
  Scenario: A live connection closed because its token expired says so
    Given the Access view showed data for a token user
    When the broker closes the connection with "credential expired"
    Then I see "The broker closed the connection because the token expired."
    And the chip says "Token expired"
    And the last data stays on screen marked as stale

  # Owner decision AQ3 (2026-11-25): no local token decoding in v1 (revisit after spike S-B).
  @security @fake-broker @m10-05
  Scenario: Scopes in the user's own token are not read or shown
    Given the held password is a JWT with the scopes "rabbitmq.read:orders/queue.*"
    When I open the Sign-in tab of the Access view
    Then no scope read from the token is shown
    And the token itself is not shown

  @gate-M10 @broker @oauth @m10-09
  Scenario: A real broker with the OAuth 2 backend is recognised and no token leaks
    Given a real RabbitMQ 4.x broker with the OAuth 2 backend and a connection signed in with a token
    When the sign-in method is read
    Then the broker is reported as using the OAuth 2 backend
    And no value read by the app contains the token
