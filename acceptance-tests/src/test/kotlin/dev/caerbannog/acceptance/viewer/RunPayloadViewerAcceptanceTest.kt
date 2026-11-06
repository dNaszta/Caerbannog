package dev.caerbannog.acceptance.viewer

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M4-01: runs payload viewer scenarios from tests/bdd/features/07_payload_viewer_filter.feature
 * against fakes (no Docker, no network). Filter scenarios (@needs-M4-02) and broker scenarios (@broker)
 * are excluded.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("07_payload_viewer_filter.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.viewer")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker")
class RunPayloadViewerAcceptanceTest
