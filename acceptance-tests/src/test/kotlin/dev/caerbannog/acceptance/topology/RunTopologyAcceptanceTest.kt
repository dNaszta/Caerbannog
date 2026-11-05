package dev.caerbannog.acceptance.topology

import io.cucumber.junit.platform.engine.Constants.FILTER_TAGS_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite

/**
 * M2-08b: runs the read-only scenarios of tests/bdd/features/09_topology_queues.feature against the fakes (no Docker, no network), virtual time.
 * The app side is the real core code (`ActiveConnection`, `ConnectionSession`, `TopologyViewModel`); only the broker is fake.
 * Left out by tag: `@M5` (they carry no `@M2` tag), `@needs-08d` (the bindings graph),
 * `@broker`. "Queue messages cannot be read" has no `@fake-broker` tag: it is a Compose check of 08c.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("09_topology_queues.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "dev.caerbannog.acceptance.topology")
@ConfigurationParameter(key = FILTER_TAGS_PROPERTY_NAME, value = "@M2 and @fake-broker and not @needs-08d and not @broker")
class RunTopologyAcceptanceTest
