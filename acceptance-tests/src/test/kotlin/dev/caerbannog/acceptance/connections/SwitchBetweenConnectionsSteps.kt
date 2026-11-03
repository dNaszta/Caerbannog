package dev.caerbannog.acceptance.connections

import io.cucumber.java.PendingException
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When

/**
 * Deliberately deferred: docs/tasks/M1-02-connection-profiles.md's Acceptance section explicitly
 * excludes "Switch between connections" from this task ("that half cannot be built or tested until
 * M2's stream list exists"). Real step definitions exist here (so the scenario is never reported as
 * "undefined" and Cucumber can still parse/match every step), but each one throws PendingException
 * rather than faking a stream list that does not exist yet - per the brief's own instruction, "rather
 * than implementing a hollow assertion against a fake list just to turn it green."
 *
 * The scenario is ALSO tagged `@needs-M2` in tests/bdd/features/01_connections.feature and excluded
 * from RunConnectionsAcceptanceTest's default filter (see that class), so the normal `@fake-broker` /
 * `@smoke` / full-suite runs reported to the architect are not blocked by a scenario this task was
 * never meant to turn green. Un-skip by removing the `@needs-M2` tag and replacing these bodies once
 * M2's stream-list task lands (tests/bdd/features/03_stream_list.feature).
 */
class SwitchBetweenConnectionsSteps {

    @Given("I am connected to {string}")
    fun iAmConnectedTo(name: String) {
        throw PendingException(
            "Deferred to M2 (tests/bdd/features/03_stream_list.feature): 'switch between connections' " +
                "needs a real stream list to assert against. See " +
                "docs/tasks/M1-02-connection-profiles.md, Acceptance.",
        )
    }

    @When("I switch to {string}")
    fun iSwitchTo(name: String) {
        throw PendingException("Deferred to M2 - see docs/tasks/M1-02-connection-profiles.md, Acceptance.")
    }

    @Then("the sidebar shows {string} as the current connection")
    fun theSidebarShowsAsTheCurrentConnection(name: String) {
        throw PendingException("Deferred to M2 - see docs/tasks/M1-02-connection-profiles.md, Acceptance.")
    }

    @Then("the stream list shows the streams of {string}")
    fun theStreamListShowsTheStreamsOf(name: String) {
        throw PendingException("Deferred to M2 - see docs/tasks/M1-02-connection-profiles.md, Acceptance.")
    }
}
