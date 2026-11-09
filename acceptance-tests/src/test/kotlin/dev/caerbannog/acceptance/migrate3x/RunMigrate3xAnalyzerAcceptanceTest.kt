package dev.caerbannog.acceptance.migrate3x

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-03: the `@m8-03` scenarios of tests/bdd/features/16_migrate_from_3x.feature and 18_migrate_access_from_3x.feature against the real
 * `CompatibilityAnalyzer` in `core` (no Docker, no network). Disjoint from the M8-01 suite: no scenario carries both tags.
 * "I analyse "legacy-eu"" runs the analyzer on fake `LiveSourceFacts`; M8-02 rebinds it to the live fetch without editing the scenarios.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@SelectClasspathResource("18_migrate_access_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3x")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-03 and @fake-broker")
class RunMigrate3xAnalyzerAcceptanceTest
