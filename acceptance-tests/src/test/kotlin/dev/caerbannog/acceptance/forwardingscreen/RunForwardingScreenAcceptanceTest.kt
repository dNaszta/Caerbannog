package dev.caerbannog.acceptance.forwardingscreen

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M8-09: the `@m8-09` scenarios of tests/bdd/features/17_forward_messages_with_shovel.feature, driven through the real `ForwardingViewModel` and
 * `ForwardingService` over `FakeShovelGateway` (view-model level; the Compose tests in `ui` bind the same words to the screen).
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("17_forward_messages_with_shovel.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.forwardingscreen")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m8-09 and @fake-broker")
class RunForwardingScreenAcceptanceTest
