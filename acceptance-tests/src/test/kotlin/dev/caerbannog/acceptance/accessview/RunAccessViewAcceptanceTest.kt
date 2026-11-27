package dev.caerbannog.acceptance.accessview

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M10-02: runs the `@m10-02` scenarios of tests/bdd/features/22_access_who_am_i_and_permissions.feature against the fakes (no Docker, no network),
 * virtual time. The app side is the real core code (`ActiveConnection`, `ConnectionSession`, `AccessViewModel`); only the broker is fake.
 * The Permissions (`@m10-03`) and Match preview (`@m10-04`) scenarios of the same file are left out by tag until their slices; `@broker` is left out
 * on purpose (none of the `@m10-02` scenarios needs a live broker; the live check is M10-09).
 * Named `accessview` because the package `access` already holds the M8-12 access migration steps.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("22_access_who_am_i_and_permissions.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.accessview")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@m10-02 and @fake-broker and not @broker")
class RunAccessViewAcceptanceTest
