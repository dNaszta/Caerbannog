package dev.caerbannog.acceptance.migrations

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-04: the `@m8-04` scenarios of tests/bdd/features/16_migrate_from_3x.feature ("A migration file cannot contain a shovel action")
 * against the real migration parser; M5-08a adds its one scenario of `16` ("Applying a generated migration reads back every declared queue",
 * tagged `@m5-08a`, steps in M508aExecutorSteps: the real runner and executor over a fake HTTP layer). The glue is the migrations package (TopologyMigrationsSteps holds the two steps), so the step
 * "I import it" is the one of feature 10. No Docker, no network. The @m8-04 scenarios of feature 10 run in RunTopologyMigrationsAcceptanceTest.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrations")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m8-04 or @m5-08a) and @fake-broker")
class RunMigrate3xMigrationModelAcceptanceTest
