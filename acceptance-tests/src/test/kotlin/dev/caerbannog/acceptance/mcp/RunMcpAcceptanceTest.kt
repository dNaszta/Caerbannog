package dev.caerbannog.acceptance.mcp

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("14_mcp_server.feature")
@SelectClasspathResource("16_migrate_from_3x.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.mcp")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@fake-broker and (@m8-10 or not @migrate-3x) and not @needs-M9-08b")
class RunMcpAcceptanceTest
