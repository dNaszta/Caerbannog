package dev.caerbannog.acceptance.migrations

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M5-04: runs topology migrations scenarios from tests/bdd/features/10_topology_migrations.feature
 * against fakes (no Docker, no network). Broker scenarios (@broker) are excluded.
 * `not @m5-08b and not @m5-08c and not @m8-02`: M5-08a's scenarios (@m5-08a, glue in M508aExecutorSteps: the REAL runner and executor over a
 * Ktor mock engine in front of FakeManagementBroker) run here. The other sub-tags of M5-08 were written for later tasks whose glue does not
 * exist yet (b persistence and the folder, c desktop wiring and screen states; @m8-02 needs the 3.x detection); each removes its exclusion when
 * it builds the glue. The scenarios are not deleted or edited.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("10_topology_migrations.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrations")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker and not @m5-08b and not @m5-08c and not @m8-02")
class RunTopologyMigrationsAcceptanceTest
