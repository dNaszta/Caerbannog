package dev.caerbannog.acceptance.clients

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-06a: runs tests/bdd/features/04_publishers_consumers.feature against the fakes (no Docker, no network), virtual time. Left out by
 * tag: `@broker` (the counts scenario runs in `:data-rabbit:brokerTest`, RealBrokerClientsTest and RealBrokerClientsViewModelTest) and
 * `@needs-M3` (the jump scenario: a core unit test asserts `browseFromOffset`, the steps are written when M3 builds the browser).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("04_publishers_consumers.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.clients")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @needs-M3 and not @broker")
class RunClientsAcceptanceTest
