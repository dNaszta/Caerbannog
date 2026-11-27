package dev.caerbannog.acceptance.accessview

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M10-02: runs the `@m10-02` and `@m10-03` scenarios of tests/bdd/features/22_access_who_am_i_and_permissions.feature against the fakes (no Docker, no network),
 * virtual time. The app side is the real core code (`ActiveConnection`, `ConnectionSession`, `AccessViewModel`); only the broker is fake.
 * The Match preview (`@m10-04`) scenarios of the same file are left out by tag until their slice; `@needs-decision` (one `@m10-03` scenario that contradicts
 * decision PD-1, see the brief and the test report) is left out until the architect rules; `@broker` is left out on purpose (none of these scenarios
 * needs a live broker; the live check is M10-09).
 * Named `accessview` because the package `access` already holds the M8-12 access migration steps.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("22_access_who_am_i_and_permissions.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.accessview")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m10-02 or @m10-02b or @m10-03) and @fake-broker and not @broker and not @needs-decision")
class RunAccessViewAcceptanceTest
