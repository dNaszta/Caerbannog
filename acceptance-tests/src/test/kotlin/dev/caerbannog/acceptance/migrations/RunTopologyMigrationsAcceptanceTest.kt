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
 * `not @m5-08`: the @m5-08 @fake-broker scenarios were written for the separate task M5-08 (real migration executor); their glue does not exist
 * yet. M5-08 removes this exclusion when it builds the glue. The scenarios are not deleted or edited.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("10_topology_migrations.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrations")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker and not @m5-08")
class RunTopologyMigrationsAcceptanceTest
