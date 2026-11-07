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
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("10_topology_migrations.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrations")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and not @broker")
class RunTopologyMigrationsAcceptanceTest
