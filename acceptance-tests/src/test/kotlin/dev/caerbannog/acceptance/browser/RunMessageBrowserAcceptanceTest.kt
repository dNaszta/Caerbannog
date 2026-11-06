package dev.caerbannog.acceptance.browser

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M3-02: runs tests/bdd/features/05_message_browser_paging.feature against fakes (no Docker, no network).
 * Real broker scenarios (@broker) run in :data-rabbit:brokerTest (RealBrokerStreamMessageReaderTest).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("05_message_browser_paging.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.browser")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker")
class RunMessageBrowserAcceptanceTest
