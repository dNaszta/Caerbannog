package dev.caerbannog.acceptance.streams

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-07: runs tests/bdd/features/06_super_streams.feature against the fakes (no Docker, no network), virtual time. Left out by tag:
 * `@needs-M3` ("Browse one partition", "Global search finds partitions under their super stream") and `@broker` ("A super stream on a
 * real broker is grouped" runs as `RealBrokerSuperStreamTest` in `:data-rabbit:brokerTest`). The steps are in `StreamListSteps` (this
 * module has no dependency injection, and "Filter by kind" of `03` shares the super stream Given).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("06_super_streams.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.streams")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @needs-M3 and not @broker")
class RunSuperStreamsAcceptanceTest
