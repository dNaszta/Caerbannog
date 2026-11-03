package dev.caerbannog.acceptance.connections

import dev.caerbannog.core.connections.Endpoint
import io.cucumber.java.ParameterType

/**
 * Maps the Gherkin wording used in 01_connections.feature's "Check connection names the endpoint
 * that failed" Scenario Outline ("stream endpoint", "management API") onto the real `Endpoint` enum
 * from `core`, so step definitions work with the typed value instead of re-parsing strings.
 */
class CucumberParameterTypes {

    @ParameterType("stream endpoint|management API")
    fun endpoint(text: String): Endpoint = when (text) {
        "stream endpoint" -> Endpoint.STREAM
        "management API" -> Endpoint.MANAGEMENT
        else -> error("Unknown endpoint wording in a scenario: '$text'")
    }
}
