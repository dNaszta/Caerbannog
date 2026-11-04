package dev.caerbannog.acceptance.lifecycle

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-03 part 1: runs tests/bdd/features/13_connection_lifecycle.feature against FakeConnectionGateway.
 * Left out by tag: `@needs-M2` (the three app bar scenarios, M2-05), `@needs-decision` (lost connection, D2
 * proposed), `@broker` (none yet) and `@needs-M2-03b` ("without the management tag": needs the catalog's
 * Capabilities, part 2).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("13_connection_lifecycle.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.lifecycle")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "not @needs-M2 and not @needs-decision and not @broker and not @needs-M2-03b",
)
class RunConnectionLifecycleAcceptanceTest
