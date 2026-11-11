package dev.caerbannog.acceptance.forwarding

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-07: the `@m8-07` scenarios of tests/bdd/features/17_forward_messages_with_shovel.feature, driven through the real `ForwardingService` and
 * `ForwardingPlanner` over `FakeShovelGateway` (no Docker, no network). `@m8-08` and `@m8-09` scenarios belong to the later tasks and are not bound here.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("17_forward_messages_with_shovel.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.forwarding")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-07 and @fake-broker")
class RunForwardingCoreAcceptanceTest
