package dev.caerbannog.acceptance.streams

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-04: runs tests/bdd/features/03_stream_list.feature against the fakes (no Docker, no network). Left out by tag:
 * `@needs-M2` ("Filter by kind", needs the super streams of M2-07) and `@broker` (the M2-09 gate scenario).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("03_stream_list.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.streams")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @needs-M2 and not @broker")
class RunStreamListAcceptanceTest
