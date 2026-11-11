package dev.caerbannog.acceptance.access

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-12a: the `@m8-12a` and `@m8-12b` scenarios of tests/bdd/features/18_migrate_access_from_3x.feature against the real migration runner, parser and redaction over
 * the fake executor (an honest 4.x broker for vhosts, users and permissions). The access migration is written by hand here: its generation is M8-12b.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("18_migrate_access_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.access")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m8-12a or @m8-12b) and @fake-broker")
class RunAccessMigrationAcceptanceTest
