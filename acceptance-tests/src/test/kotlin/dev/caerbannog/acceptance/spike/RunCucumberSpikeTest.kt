package dev.caerbannog.acceptance.spike

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M1-01 skeleton spike: JUnit Platform Suite entry point that makes the JUnit Platform (and
 * therefore the chosen build tool's test task) discover and run spike_sanity.feature via the
 * Cucumber JUnit Platform Engine.
 *
 * Expected to fail to compile right now (see SpikeSanitySteps.kt) until the senior developer adds
 * `io.cucumber:cucumber-junit-platform-engine` and `org.junit.platform:junit-platform-suite`.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.spike")
class RunCucumberSpikeTest
