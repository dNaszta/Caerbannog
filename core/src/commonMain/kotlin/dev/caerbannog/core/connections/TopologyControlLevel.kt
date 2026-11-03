package dev.caerbannog.core.connections

/**
 * Per-connection self-constraint ceiling on topology changes (`docs/spec.md`, "Topology migrations
 * and permission guardrails" / Self-constraint level). The effective permission is the stricter of
 * this and whatever the broker itself allows, but combining the two is M1-03/M5 work - this task only
 * stores and edits the field.
 *
 * Declaration order matters: later levels strictly contain the ones before them.
 */
enum class TopologyControlLevel {
    READ_ONLY,
    DECLARE_AND_EDIT,
    FULL_CONTROL,
}
