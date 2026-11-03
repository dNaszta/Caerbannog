# M1-01 skeleton spike throwaway scenario (Acceptance item 4).
#
# This is NOT a canonical Caerbannog scenario: it proves Cucumber-JVM with Kotlin step definitions
# can discover and run a .feature file through the chosen build tool. It lives here, outside
# tests/bdd/features/, per the task brief's Design notes, and must be deleted once M1-02 lands real
# step definitions for 01_connections.feature.
Feature: Cucumber-JVM tooling spike

  Scenario: a trivial scenario proves the runner works
    Given a counter starting at 0
    When the counter is incremented by 2
    Then the counter should be 2
