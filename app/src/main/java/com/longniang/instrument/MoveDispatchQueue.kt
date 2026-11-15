package com.longniang.instrument

/** A short lookahead for MOVE only. Pointer edges never wait on the timer. */
internal class MoveDispatchQueue {
    private class Pending(val sampleNs: Long, val dispatch: () -> Unit)
    private val pending = ArrayDeque<Pending>()

    val isEmpty get() = pending.isEmpty()

    fun add(sampleNs: Long, dispatch: () -> Unit) {
        val index = pending.indexOfLast { it.sampleNs <= sampleNs }
        pending.add(index + 1, Pending(sampleNs, dispatch))
    }

    fun dispatchThrough(sampleNs: Long) {
        while (pending.firstOrNull()?.let { it.sampleNs <= sampleNs } == true) {
            pending.removeFirst().dispatch()
        }
    }

    fun clear() = pending.clear()

    companion object {
        const val LOOKAHEAD_MS = 2L
    }
}
