package dev.caerbannog.acceptance.migrate3xlive

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-02a: the `@m8-02` scenarios of tests/bdd/features/16_migrate_from_3x.feature against the real `ActiveConnection`, `ConnectionSession` and
 * `LiveDefinitionsSource` of `core`, over `FakeConnectionGateway` and `FakeThreeXReads` (no Docker, no network). Its own glue package: the
 * Givens "a connection ... running RabbitMQ 3.13" and the When "I analyse ..." also exist, with other meaning, in the `@m8-03` glue. The three
 * `@broker-3x` scenarios and the `@m8-02b` one are not selected here (live broker, and a data-rabbit JUnit test).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.migrate3xlive")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-02 and @fake-broker")
class RunMigrate3xLiveSourceAcceptanceTest
