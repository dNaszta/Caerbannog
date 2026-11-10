package dev.caerbannog.acceptance.migrate3x

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-05: the `@m8-05` scenarios of tests/bdd/features/16_migrate_from_3x.feature against the real `ConversionReview` in `core` (no Docker, no
 * network). The glue is this package (the conversion steps share [Migrate3xWorld] with the analyzer steps) plus the migrations package: the
 * two apply scenarios reuse the M5-08 steps and fakes and add their own in `M805GeneratedApplySteps`.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3x,dev.caerbannog.acceptance.migrations")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-05 and @fake-broker")
class RunMigrate3xConversionAcceptanceTest
