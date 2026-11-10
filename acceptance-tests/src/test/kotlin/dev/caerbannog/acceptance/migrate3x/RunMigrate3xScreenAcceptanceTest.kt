package dev.caerbannog.acceptance.migrate3x

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-06a and M8-06b: the `@m8-06a` and `@m8-06b` scenarios of tests/bdd/features/16_migrate_from_3x.feature, driven through `MigrateFromThreeViewModel` with fakes
 * for the file chooser, the loader and the report saver (no Docker, no network, no broker: the view model has no gateway). The Compose
 * tests in `ui` bind the same words to the screen. M8-06b adds the `@m8-06b` scenarios to this suite (review, generate and save, with a fake migrations folder).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3x")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m8-06a or @m8-06b) and @fake-broker")
class RunMigrate3xScreenAcceptanceTest
