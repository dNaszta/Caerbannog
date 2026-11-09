package dev.caerbannog.acceptance.migrate3x

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-01: the `@m8-01` scenarios of tests/bdd/features/16_migrate_from_3x.feature against the real parser in `core` (no Docker, no network).
 * "I analyse the file" is bound here to `DefinitionsParser.parse`; M8-03 re-binds it to the full analysis without editing the scenario.
 * Later M8 tasks add their own suite and their own `@m8-NN` tag.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3x")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-01 and @fake-broker")
class RunMigrate3xDefinitionsAcceptanceTest
