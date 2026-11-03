package dev.caerbannog.acceptance.connections

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M1-02: JUnit Platform Suite entry point for tests/bdd/features/01_connections.feature, following
 * the same pattern M1-01's RunCucumberSpikeTest proved for the scratch scenario, now pointed at the
 * canonical feature file instead (see acceptance-tests/build.gradle.kts for how that file gets onto
 * this module's test classpath without being copied into tests/bdd/features/).
 *
 * `not @needs-M2` excludes "Switch between connections" (deliberately deferred to M2, see
 * SwitchBetweenConnectionsSteps.kt) from the default run reported as part of this task's `@smoke`
 * and `@fake-broker` sets. No `@broker` scenario exists in this feature file - the real-broker proof
 * for this task is data-rabbit's own plain JUnit `@Tag("broker")` test, not a Cucumber scenario (see
 * that test's header for why).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("01_connections.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.connections")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "not @needs-M2")
class RunConnectionsAcceptanceTest
