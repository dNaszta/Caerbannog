package dev.caerbannog.acceptance.planner

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M9-01 to M9-03: the Topology planner core (forms, plain words, validation, naming) and its view model (the wizard flow, the draft,
 * Save as migration, Dismiss) from tests/bdd/features/19, 20 and 21. The steps talk to [PlannerDriver], which drives `PlannerViewModel` over
 * fakes (draft store, migrations folder, settings); no feature line was edited for that. M9-03 adds the screen (the real planner and Topology screens through the Compose UI test API, see PlannerScreens) and the outline of 09 that says the Topology screen is read-only; M9-04 adds the review graph (PlannerGraphSteps); M9-05 adds the check (PlannerCheckSteps: the real PlanCheck and ManagementDeclareCheck over the honest FakeManagementBroker, the extended safety step of @m9-02); M9-06 puts it on the screen (PlannerCheckScreenSteps: the real view model and planner screen over the scripted FakeDeclareCheck, a virtual clock); M9-07 adds the Stream kind and its response stream (PlannerStreamSteps; two stand-ins for M9-12's "Edit steps" are marked there); later M9 tasks widen the tag filter (@m9-12, ...).
 * No Docker, no network.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("09_topology_queues.feature")
@SelectClasspathResource("19_topology_planner.feature")
@SelectClasspathResource("20_topology_planner_check.feature")
@SelectClasspathResource("21_topology_planner_recipes.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.planner")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m9-01 or @m9-02 or @m9-03 or @m9-04 or @m9-05 or @m9-06 or @m9-07) and @fake-broker and not @needs-decision and not @needs-spike")
class RunTopologyPlannerAcceptanceTest
