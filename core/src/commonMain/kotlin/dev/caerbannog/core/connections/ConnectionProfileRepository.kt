package dev.caerbannog.core.connections

/**
 * CRUD for saved connection profiles, keyed by [ConnectionProfile.name] - there is no separate id
 * concept anywhere in this app, so every operation here is by name.
 */
interface ConnectionProfileRepository {
    fun list(): List<ConnectionProfile>

    /** Fails with [SaveConnectionResult.NameAlreadyExists] if [profile]'s name is already saved. */
    fun add(profile: ConnectionProfile): SaveConnectionResult

    /**
     * [profile]'s name must already exist: callers only ever update a profile they first loaded from
     * [list]. Calling this for a name that was never added is a programmer error, not a reachable UI
     * path - implementations may enforce that with a precondition check.
     */
    fun update(profile: ConnectionProfile): SaveConnectionResult

    fun delete(name: String)
}

/** Result of [ConnectionProfileRepository.add] or [ConnectionProfileRepository.update]. */
sealed interface SaveConnectionResult {
    data class Saved(val profile: ConnectionProfile) : SaveConnectionResult
    data class NameAlreadyExists(val name: String) : SaveConnectionResult
}
