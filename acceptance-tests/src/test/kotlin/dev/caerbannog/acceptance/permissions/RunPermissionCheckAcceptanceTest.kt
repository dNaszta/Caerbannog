package dev.caerbannog.acceptance.permissions

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M1-03: runs tests/bdd/features/02_permission_check.feature. `not @needs-M2 and not @broker` leaves out the
 * scenarios deferred by tag (stream browsing and the topology-screen texts need M2).
 * M1-07 is done: the sidebar and edit panel scenarios now run (asserted in core; the @needs-M1-07 tag is gone).
 * M1-06 is done: its two persistence scenarios now run (the @needs-M1-06 tag is no longer used);
 * the `@broker` scenario "The broker has the final say" has no steps (needs queue declare, M5) and is
 * excluded as well, because it is not `@fake-broker`.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("02_permission_check.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.permissions")
@ConfigurationParameter(
    key = FILTER_TAGS_PROPERTY_NAME,
    value = "not @needs-M2 and not @broker",
)
class RunPermissionCheckAcceptanceTest
