package dev.caerbannog.acceptance.publish

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M5-01: runs publish test message scenarios from tests/bdd/features/08_publish_test_message.feature
 * against fakes (no Docker, no network). Broker scenarios (@broker) are excluded.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("08_publish_test_message.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.publish")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker")
class RunPublishTestMessageAcceptanceTest
