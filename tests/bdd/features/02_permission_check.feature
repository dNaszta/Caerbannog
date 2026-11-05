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

  # M2-14: the check reads the permissions over HTTPS with TLS on and over plain HTTP with TLS off; the topology rules (configure, write)
  # are the same, and the check asks the broker in the mode of the profile.
  @fake-broker
  Scenario Outline: The permission check gives the same answer over TLS and over plain
    Given the connection "prod-eu" has TLS <tls>
    And the user has these permissions on "/": configure "<configure>", write "<write>", read ".*"
    When the permission check runs
    Then the permission check asked the broker <mode>
    And topology changes are <topology>
    And test publishing is <publishing>

    Examples:
      | tls | mode        | configure | write | topology | publishing |
      | on  | with TLS    | .*        | .*    | unlocked | enabled    |
      | on  | with TLS    |           | .*    | locked   | enabled    |
      | on  | with TLS    | .*        |       | unlocked | disabled   |
      | on  | with TLS    |           |       | locked   | disabled   |
      | off | without TLS | .*        | .*    | unlocked | enabled    |
      | off | without TLS |           | .*    | locked   | enabled    |
      | off | without TLS | .*        |       | unlocked | disabled   |
      | off | without TLS |           |       | locked   | disabled   |

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

  @fake-broker
  Scenario: A locked connection says so in the app bar
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then the app bar shows "Topology locked" next to "prod-eu"

  # Built by task M2-08c (core half: the banner reasons of the view model; the drawing is in the Compose tests of 08c).
  @fake-broker
  Scenario: The topology screen explains why it is locked
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then the topology screen explains that the user has no configure permission on virtual host "/"

  @fake-broker
  Scenario: A verified connection says so in the app bar and in the edit panel
    Given the user has these permissions on "/": configure ".*", write ".*", read ".*"
    When I connect to "prod-eu"
    Then the app bar shows "Can change topology" next to "prod-eu"
    And the edit panel says the broker confirmed that "svc-explorer" can change topology on virtual host "/"

  @fake-broker
  # M2-01: the session keeps the result of the latest started check, not the latest finished one.
  Scenario: The latest check wins when two checks of one connection overlap
    Given the first check of "prod-eu" is still running and would say the user can change topology
    And the connection "prod-eu" is checked again and that check says the user has no configure permission
    When the first check finishes
    Then the app bar shows "Topology locked" next to "prod-eu"

  @fake-broker
  # M2-01: each connection keeps its own result; the app bar follows the connection that is current.
  # M2-05: switching means Connect to the other connection, which disconnects the held one (see 13).
  Scenario: Switching connection shows the permission state of the new current connection
    Given saved connections "uat-eu" and "prod-eu"
    And the user has configure permission on "uat-eu" and no configure permission on "prod-eu"
    And I am connected to "uat-eu"
    When I switch to "prod-eu"
    Then the app bar shows "prod-eu" as the current connection
    And the app bar shows "Topology locked" next to "prod-eu"

  # Split by M2-08 (2026-11-05): the list half runs since M2-08b; the browse half needs the M3 browser.
  @fake-broker
  Scenario: Listing streams does not need the configure permission
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then I can list streams

  @fake-broker @needs-M3
  Scenario: Browsing streams does not need the configure permission
    Given the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then I can browse the messages of a stream

  @fake-broker
  # Spike result: only administrators can read permissions through the management API, so this is the
  # normal case for a stream user. Safest mode is the default when nothing is declared on the profile.
  Scenario: Permissions cannot be read for this user
    Given the management API refuses to return the user's permissions
    When the permission check runs
    Then the app tells me that permissions could not be read
    And topology changes are locked
    And test publishing is disabled
    And streams can still be listed and browsed

  @fake-broker
  Scenario Outline: Declared permissions are used when permissions cannot be read
    Given the profile "prod-eu" declares configure "<configure>" and write "<write>"
    And the management API refuses to return the user's permissions
    When the permission check runs
    Then the app tells me that permissions could not be read
    And the app says these permissions are declared by me, not confirmed by the broker
    And topology changes are <topology>
    And test publishing is <publishing>
    And streams can still be listed and browsed

    Examples:
      | configure | write | topology | publishing |
      | yes       | yes   | unlocked | enabled    |
      | no        | yes   | locked   | enabled    |
      | yes       | no    | unlocked | disabled   |
      | no        | no    | locked   | disabled   |

  @fake-broker
  Scenario: What the broker says wins over declared permissions
    Given the profile "prod-eu" declares configure "yes" and write "yes"
    And the user has these permissions on "/": configure "", write "", read ".*"
    When the permission check runs
    Then topology changes are locked
    And test publishing is disabled
    And the app says these permissions were read from the broker

  @fake-broker
  Scenario: Declared permissions do not help when the password is missing
    Given the profile "prod-eu" declares configure "yes" and write "yes"
    And no password is stored for "prod-eu"
    When the permission check runs
    Then the app tells me that permissions could not be read
    And topology changes are locked
    And test publishing is disabled

  @fake-broker
  Scenario: Declared permissions are saved with the profile
    Given I edit the profile "prod-eu"
    When I declare that the user can change topology and can publish
    And I save the profile
    And I reopen the saved profile "prod-eu"
    Then the profile declares configure "yes" and write "yes"

  @fake-broker
  Scenario: A profile saved before declarations existed has none
    Given a saved profile file from before declared permissions existed
    When the profiles are loaded
    Then "prod-eu" declares nothing
    And the permission check, if it cannot read permissions, uses the safest mode

  @fake-broker
  Scenario: Declared permissions are shown as the user's own claim
    Given the profile "prod-eu" declares configure "yes" and write "yes"
    And the management API refuses to return the user's permissions
    When I connect to "prod-eu"
    Then the app bar shows "Can change topology (declared)" next to "prod-eu"
    And the edit panel says the broker could not confirm the permissions and still enforces its own

  @fake-broker
  Scenario: A declaration is not used when the broker answers
    Given the profile "prod-eu" declares configure "yes" and write "yes"
    And the user has no configure permission on "/"
    When I connect to "prod-eu"
    Then the app bar shows "Topology locked" next to "prod-eu"
    And the edit panel says "Your declaration is not used, because the broker answered."

  @fake-broker
  Scenario: Permissions that could not be read are shown as locked with the reason
    Given the management API refuses to return the user's permissions
    When I connect to "prod-eu"
    Then the app bar shows "Topology locked" next to "prod-eu"
    And the edit panel tells me that permissions could not be read

  @fake-broker
  Scenario: The topology screen says declared permissions are not confirmed
    Given the profile "prod-eu" declares configure "yes" and write "yes"
    And the management API refuses to return the user's permissions
    When I connect to "prod-eu"
    Then the topology screen says the broker could not confirm the permissions and still enforces its own

  @broker
  Scenario: The broker has the final say
    Given the permission check said topology is unlocked
    And the broker then refuses to declare a queue with an access error
    When I try to declare the queue "orders"
    Then the app shows the broker's refusal
    And the topology screen is not left in a half-updated state
