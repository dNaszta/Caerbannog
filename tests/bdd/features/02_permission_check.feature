@M1 @permissions
Feature: Permission check before the first connection
  As an engineer
  I want the app to find out what my broker user may do
  So that it never offers changes the broker would refuse, and I can trust what is unlocked

  The check reads the user's permissions on the connection's virtual host.
  Reading streams and statistics is always allowed. What changes is topology editing
  (needs the configure permission) and test publishing (needs the write permission).

  Background:
    Given a saved connection "prod-eu" with user "svc-explorer" on virtual host "/"

  @smoke @fake-broker
  Scenario Outline: What the app unlocks depends on the user's permissions
    Given the user has these permissions on "/": configure "<configure>", write "<write>", read "<read>"
    When the permission check runs
    Then topology changes are <topology>
    And test publishing is <publishing>

    Examples:
      | configure | write | read | topology | publishing |
      | .*        | .*    | .*   | unlocked | enabled    |
      |           | .*    | .*   | locked   | enabled    |
      | .*        |       | .*   | unlocked | disabled   |
      |           |       | .*   | locked   | disabled   |

  @fake-broker
  Scenario: The check runs before the first connection is used
    When I connect to "prod-eu" for the first time
    Then the permission check runs before any stream or topology screen opens

  @fake-broker
  Scenario: The check runs again when the profile is edited
    Given I am connected to "prod-eu" and the user has configure permission
    When the user's configure permission is removed on the broker
    And I save a change to the profile "prod-eu"
    Then the permission check runs again
    And topology changes are locked

  @fake-broker @needs-M1-07
  Scenario: A locked connection says so in the sidebar and on topology screens
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then the sidebar shows "Topology locked" next to "prod-eu"
    And the topology screen explains that the user has no configure permission on virtual host "/"

  @fake-broker @needs-M2
  Scenario: Browsing streams does not need the configure permission
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then I can list streams and browse their messages

  @needs-spike @fake-broker
  # Open question: can a non-administrator read their own permissions through the management API?
  # Expected behavior until confirmed: fall back to the safest mode.
  Scenario: Permissions cannot be read for this user
    Given the management API refuses to return the user's permissions
    When the permission check runs
    Then the app tells me that permissions could not be read
    And topology changes are locked
    And test publishing is disabled
    And streams can still be listed and browsed

  @broker
  Scenario: The broker has the final say
    Given the permission check said topology is unlocked
    And the broker then refuses to declare a queue with an access error
    When I try to declare the queue "orders"
    Then the app shows the broker's refusal
    And the topology screen is not left in a half-updated state
