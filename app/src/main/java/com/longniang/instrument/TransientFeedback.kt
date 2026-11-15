package com.longniang.instrument

// Judgment text expires using uptime. Pressed-lane highlights come from active pointers.
data class VisualPulse(val bornMs: Long, val durationMs: Long, val lane: Int, val result: Result) {
    fun remaining(nowMs: Long): Float = (1f - (nowMs - bornMs).toFloat() / durationMs).coerceIn(0f, 1f)
}
class TransientFeedback {
    private val pulses = mutableListOf<VisualPulse>()
    fun judgement(lane: Int, result: Result, nowMs: Long) {
        pulses.removeAll { it.lane == lane }
        pulses.add(VisualPulse(nowMs, 650, lane, result))
    }
    fun visible(nowMs: Long) = pulses.filter { it.remaining(nowMs) > 0 }.toList()
    fun prune(nowMs: Long) { pulses.removeAll { it.remaining(nowMs) <= 0 } }
    fun clear() { pulses.clear() }
}
