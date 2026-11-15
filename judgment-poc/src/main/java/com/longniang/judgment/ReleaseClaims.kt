package com.longniang.judgment

/** One long-note tail association per live touch and one claimant per path. */
class ReleaseClaims {
    private class Claim(val pathId: String, var acquiredAt: Long, var detachedAt: Long? = null)
    private val claims = mutableMapOf<Int, Claim>()

    fun acquire(pointer: Int, pathId: String, at: Long) {
        val current = claims[pointer]
        if (current?.pathId == pathId) {
            if (current.detachedAt != null) current.acquiredAt = at
            current.detachedAt = null
            return
        }
        finish(pathId)
        claims[pointer] = Claim(pathId, at)
    }

    fun lose(pointer: Int, pathId: String, at: Long) {
        val claim = claims[pointer] ?: return
        if (claim.pathId == pathId && claim.detachedAt == null) claim.detachedAt = at
    }

    fun path(pointer: Int): String? = claims[pointer]?.pathId

    fun canRelease(pointer: Int, at: Long): Boolean {
        val claim = claims[pointer] ?: return false
        val detachedAt = claim.detachedAt ?: return true
        return at - detachedAt in 0..MAX_DETACHED_US
    }

    /** Historical gestures may only start during the latest valid ownership of this path. */
    fun flickOrigins(pointer: Int, at: Long, historyUs: Long): LongRange? {
        val claim = claims[pointer] ?: return null
        val detachedAt = claim.detachedAt ?: return null
        if (at - detachedAt !in 0..historyUs) return null
        return maxOf(claim.acquiredAt, at - historyUs)..detachedAt
    }

    fun discard(pointer: Int) { claims.remove(pointer) }
    fun finish(pathId: String) { claims.entries.removeAll { it.value.pathId == pathId } }
    fun clear() { claims.clear() }

    private companion object {
        // Release-specific grace after losing contact. Tick grace is independent.
        const val MAX_DETACHED_US = 125_000L
    }
}
