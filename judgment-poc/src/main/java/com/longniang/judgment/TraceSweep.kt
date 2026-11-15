package com.longniang.judgment

/** Reconstruct only short, continuously in-band MOVE segments that bracket a target. */
object TraceSweep {
    fun laneAt(target: Long, from: Long, to: Long, fromLaneX: Float, toLaneX: Float): Float? {
        if (to - from !in 1..12_000 || target !in from..to) return null
        val fraction = (target - from).toFloat() / (to - from)
        return fromLaneX + (toLaneX - fromLaneX) * fraction
    }
}
