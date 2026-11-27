package dev.caerbannog.acceptance.accessview

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M10-02: runs the `@m10-02`, `@m10-02b`, `@m10-03` and `@m10-04` scenarios of tests/bdd/features/22_access_who_am_i_and_permissions.feature against the fakes (no Docker, no network),
 * virtual time. The app side is the real core code (`ActiveConnection`, `ConnectionSession`, `AccessViewModel`); only the broker is fake.
 * The `@m10-04` gate scenario is `@broker` and is left out on purpose (it is `RealBrokerAccessMatchTest`; none of the selected scenarios needs a live broker;
 * the gate hook is M10-09).
 * Named `accessview` because the package `access` already holds the M8-12 access migration steps.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("22_access_who_am_i_and_permissions.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.accessview")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m10-02 or @m10-02b or @m10-03 or @m10-04) and @fake-broker and not @broker")
class RunAccessViewAcceptanceTest
