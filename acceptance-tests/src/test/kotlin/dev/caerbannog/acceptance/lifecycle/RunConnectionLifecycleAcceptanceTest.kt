package dev.caerbannog.acceptance.lifecycle

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-03 part 1: runs tests/bdd/features/13_connection_lifecycle.feature against FakeConnectionGateway.
 * M2-05b: the lost-connection scenario and the three app bar scenarios run now (their `@needs-M2` is gone). Left out by tag:
 * `@needs-M2` (none now, kept in the filter), `@needs-decision` (none) and `@broker` (one scenario, M2-16 "A refresh that meets a connection the
 * broker just closed is not a lost connection": it has no steps and is bound by name to `RealBrokerClientsTest` and
 * `ManagementHttpClosedConnectionTest` in `data-rabbit`).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("13_connection_lifecycle.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.lifecycle")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "not @needs-M2 and not @needs-decision and not @broker and not @m10-10",
)
class RunConnectionLifecycleAcceptanceTest
