package dev.caerbannog.acceptance.permissions

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M1-03: runs tests/bdd/features/02_permission_check.feature. `not @needs-M2 and not @needs-M1-07 and not @needs-M1-06`
 * leaves out the scenarios deferred by tag (sidebar/declared UI needs M1-07, profile persistence of declarations
 * needs M1-06, stream browsing needs M2);
 * the `@broker` scenario "The broker has the final say" has no steps (needs queue declare, M5) and is
 * excluded as well, because it is not `@fake-broker`.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("02_permission_check.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.permissions")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "not @needs-M2 and not @needs-M1-07 and not @needs-M1-06 and not @broker",
)
class RunPermissionCheckAcceptanceTest
