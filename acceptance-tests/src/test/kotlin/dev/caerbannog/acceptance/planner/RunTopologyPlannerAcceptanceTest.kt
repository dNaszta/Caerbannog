package dev.caerbannog.acceptance.planner

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M9-01 and M9-02: the Topology planner core (forms, plain words, validation, naming) and its view model (the wizard flow, the draft,
 * Save as migration, Dismiss) from tests/bdd/features/19, 20 and 21. The steps talk to [PlannerDriver], which drives `PlannerViewModel` over
 * fakes (draft store, migrations folder, settings); no feature line was edited for that. Later M9 tasks widen the tag filter (@m9-03, ...).
 * No Docker, no network.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("19_topology_planner.feature")
@SelectClasspathResource("20_topology_planner_check.feature")
@SelectClasspathResource("21_topology_planner_recipes.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.planner")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m9-01 or @m9-02) and @fake-broker and not @needs-decision and not @needs-spike")
class RunTopologyPlannerAcceptanceTest
