package dev.caerbannog.acceptance.migrate3xlivescreen

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-06c: the `@m8-06c` scenarios of tests/bdd/features/16_migrate_from_3x.feature (the live source on the Migrate from 3.x screen) at view-model level:
 * the real `ActiveConnection`, `ConnectionSession`, `HeldLiveSourceProvider`, `ConnectionControlModel` and `cardPill`, the real view model on a test scheduler,
 * and the real `LiveDefinitionsSource` over `FakeThreeXReads` (no Docker, no network). Its own glue package on purpose: the `migrate3x` and `migrate3xlive`
 * glue share Given/When texts ("a connection ... running RabbitMQ 3.13", "I analyse ...", "no report is shown") with other meanings. The Compose tests in `ui`
 * draw the same states.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3xlivescreen")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-06c and @fake-broker")
class RunMigrate3xLiveScreenAcceptanceTest
