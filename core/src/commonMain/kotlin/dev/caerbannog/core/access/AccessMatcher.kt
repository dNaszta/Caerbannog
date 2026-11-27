package dev.caerbannog.core.access

/**
 * M10-04 contract (COMPILE-ONLY STUB, step 3 builds it). Pure: patterns in, answer out; no gateway, no request, no `java.*`.
 * Check order (brief item 2): (a) permissions not available; (b) the vhost row; (c) per needed cell `hidden` FIRST; (d) the empty pattern is "no permission"
 * (NoMatch, nothing allowed); (e) length limits, engine compile, [KnownDivergences]; (f) unanchored case-sensitive search. WouldMatch only when every needed
 * cell matched; an unreadable cell anywhere wins over a NoMatch of another cell.
 */
class AccessMatcher(
    private val engine: PatternEngine = defaultPatternEngine(),
    private val divergences: KnownDivergences = KnownDivergences.Measured,
) {
    fun check(source: PermissionsSource, question: AccessQuestion): AccessAnswer =
        throw NotImplementedError("M10-04 step 3: AccessMatcher is not built yet ($engine, $divergences, $source, $question)")

    fun check(permissions: UserPermissions, question: AccessQuestion): AccessAnswer = check(PermissionsSource.Available(permissions), question)
}
