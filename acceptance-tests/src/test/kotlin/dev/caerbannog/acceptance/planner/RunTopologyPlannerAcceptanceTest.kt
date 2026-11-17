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
 * fakes (draft store, migrations folder, settings); no feature line was edited for that. M9-03 adds the screen (the real planner and Topology screens through the Compose UI test API, see PlannerScreens) and the outline of 09 that says the Topology screen is read-only; M9-04 adds the review graph (PlannerGraphSteps); M9-05 adds the check (PlannerCheckSteps: the real PlanCheck and ManagementDeclareCheck over the honest FakeManagementBroker, the extended safety step of @m9-02); M9-06 puts it on the screen (PlannerCheckScreenSteps: the real view model and planner screen over the scripted FakeDeclareCheck, a virtual clock); M9-07 adds the Stream kind and its response stream (PlannerStreamSteps; two stand-ins for M9-12's "Edit steps" are marked there); M9-07b adds the optional MQTT ticks of the stream pair (PlannerStreamSteps: the ticks and filters on the real planner screen, the summary and graph notes; one more stand-in for M9-12); M9-10 adds the MQTT route step (PlannerMqttSteps: the form on the real planner screen, the words of a route in the list and the review graph; three stand-ins for M9-12 share `PendingStandIn`); M9-10b adds the test message of a route (PlannerTestMessageSteps: the real Migrations screen over a built state and the real Publish MQTT dialog over the fake publisher); M9-01b adds the broker's name rules (PlannerSteps.fillForm: the name, a run of letters, the Stream kind, the response stream); M9-08 adds the Policy kind, the retention block of the stream form and the policy notes (PlannerPolicySteps: the real view model over fakes; the check notes run the real PlanCheck and ManagementBrokerPolicyReader over the honest FakeManagementBroker); M9-11 adds the headers rows of the binding form (PlannerHeadersSteps: the real view model and planner screen, the file stand-ins of M9-10); later M9 tasks widen the tag filter (@m9-12, ...).
 * No Docker, no network.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("09_topology_queues.feature")
@SelectClasspathResource("19_topology_planner.feature")
@SelectClasspathResource("20_topology_planner_check.feature")
@SelectClasspathResource("21_topology_planner_recipes.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.planner")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "(@m9-01 or @m9-01b or @m9-02 or @m9-03 or @m9-04 or @m9-05 or @m9-06 or @m9-07 or @m9-07b or @m9-08 or @m9-10 or @m9-10b or @m9-11) and @fake-broker and not @needs-decision and not @needs-spike")
class RunTopologyPlannerAcceptanceTest
