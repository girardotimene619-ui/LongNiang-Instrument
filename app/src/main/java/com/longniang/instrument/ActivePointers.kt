package com.longniang.instrument

internal data class PointerState(
    val id: Int, val x: Float, val y: Float, val lane: Int?,
    val downNs: Long, val token: ClockToken, val lastSampleNs: Long = downNs,
    val admitted: Boolean = true
)

/** Membership tracks the physical stream. Admission belongs to one judgment epoch. */
internal class ActivePointers {
    private val active = mutableMapOf<Int, PointerState>()
    val isEmpty get() = active.isEmpty()
    operator fun get(id: Int) = active[id]
    fun down(state: PointerState) = active.putIfAbsent(state.id, state) == null
    fun move(id: Int, x: Float, y: Float, lane: Int?, sampleNs: Long) {
        val state = active[id] ?: return
        if (sampleNs >= state.lastSampleNs)
            active[id] = state.copy(x = x, y = y, lane = lane, lastSampleNs = sampleNs)
    }
    fun up(id: Int, sampleNs: Long): Boolean {
        val state = active[id] ?: return false
        if (sampleNs < state.lastSampleNs) return false
        active.remove(id)
        return true
    }
    fun pressedLanes(): Set<Int> = active.values.mapNotNullTo(hashSetOf()) { if (it.admitted) it.lane else null }
    fun isolate() { active.replaceAll { _, state -> if (state.admitted) state.copy(admitted = false) else state } }
    fun clear() = active.clear()
}
