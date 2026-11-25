@M10 @access-view @oauth
Feature: Access view, part 3: how the broker signs users in (OAuth 2 and Keycloak awareness)
  As an engineer whose broker is secured with Keycloak (the rabbitmq_auth_backend_oauth2 plugin)
  I want the app to say that tokens are in use and show what RabbitMQ resolved for the user
  So that I understand the authorization design, without the app pretending to know the Keycloak setup

  Honesty rule (spec "Access view (M10)"): the management API shows only what RabbitMQ resolved.
  It does not show the Keycloak client, its roles or its mappers, and the view never claims it does.
  The app never calls the identity provider. A JWT, a client secret and the held password never
  appear on screen, in logs, in error text, in copied text or in MCP output.
  What RabbitMQ exposes for a token user is measured by the spike M10-00 (S-B, S-C, S-J);
  scenarios tagged @needs-spike are written for the likely outcome and are checked against the result.

  Background:
    Given the connection "prod-eu" is the held connection

  @smoke @fake-broker @m10-05
  Scenario: A broker with the OAuth 2 backend says so
    Given the broker reports that the OAuth 2 backend is in use
    When I open the Sign-in tab of the Access view
    Then I see that this broker signs users in with tokens from an identity provider

  @fake-broker @m10-05
  Scenario: The view says what it can and cannot show
    Given the broker reports that the OAuth 2 backend is in use
    When I open the Sign-in tab of the Access view
    Then I see that the view shows what RabbitMQ resolved for the user
    And I see that the identity provider's own setup, such as clients, roles and mappers, is not shown here

  @fake-broker @m10-05
  Scenario: A broker without the OAuth 2 backend does not claim the opposite
    Given the broker reports no OAuth 2 backend
    When I open the Sign-in tab of the Access view
    Then I do not see the statement that tokens are in use
    And I do not see a statement that the broker uses only its own users

  @negative @fake-broker @m10-05
  Scenario: The sign-in method cannot be read for this user
    Given the connection user may not read the broker's plugin list
    When I open the Sign-in tab of the Access view
    Then I see that the sign-in method could not be determined
    And I see no guess

  @needs-spike @fake-broker @m10-05
  Scenario: For a token user, what RabbitMQ resolved is shown and labelled
    Given the broker reports that the OAuth 2 backend is in use
    And RabbitMQ resolved for the connection user the tag "monitoring" and read access on "/orders" for "^queue\\..*"
    When I open the Sign-in tab of the Access view
    Then I see the tag and the read access
    And they are labelled as resolved by RabbitMQ from the token

  @needs-spike @fake-broker @m10-05
  Scenario: When RabbitMQ does not expose the resolved permissions, the view says so
    Given the broker reports that the OAuth 2 backend is in use
    And no management API call returns resolved permissions for a token user
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

  @needs-spike @negative @fake-broker @m10-05
  Scenario: A token the broker refuses is reported without echoing it
    Given the held password is a JWT that the broker refuses
    When I open the Access view
    Then I see that the sign-in was refused and that the token may have expired
    And the message does not contain the token

  @needs-decision @fake-broker @m10-05
  Scenario: Scopes read locally from the user's own token are labelled as not confirmed
    Given the held password is a JWT with the scopes "rabbitmq.read:orders/queue.*"
    When I open the Sign-in tab of the Access view
    Then I see the scopes labelled as read from my token and not confirmed by the broker
    And the token itself is not shown

  @gate-M10 @broker @oauth @m10-09
  Scenario: A real broker with the OAuth 2 backend is recognised and no token leaks
    Given a real RabbitMQ 4.x broker with the OAuth 2 backend and a connection signed in with a token
    When the sign-in method is read
    Then the broker is reported as using the OAuth 2 backend
    And no value read by the app contains the token
