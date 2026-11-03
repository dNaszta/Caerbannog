package dev.caerbannog.acceptance

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * M1-01 skeleton spike, Acceptance item 4: "One trivial Cucumber-JVM scenario (Kotlin steps) runs
 * and passes outside the canonical `tests/bdd/features/` tree."
 *
 * Step definitions for the throwaway spike_sanity.feature only. Deliberately has nothing to do with
 * Caerbannog's domain - the fixture is a plain counter.
 *
 * Expected to fail to compile right now: `acceptance-tests/build.gradle.kts` declares no Cucumber
 * dependency yet. The senior developer makes this pass by adding `io.cucumber:cucumber-java` and
 * `io.cucumber:cucumber-junit-platform-engine`, and wiring the JUnit Platform to discover the
 * feature under src/test/resources/features.
 */
class SpikeSanitySteps {

    private var counter = -1

    @Given("a counter starting at {int}")
    fun aCounterStartingAt(start: Int) {
        counter = start
    }

    @When("the counter is incremented by {int}")
    fun theCounterIsIncrementedBy(amount: Int) {
        counter += amount
    }

    @Then("the counter should be {int}")
    fun theCounterShouldBe(expected: Int) {
        assertEquals(expected, counter)
    }
}
