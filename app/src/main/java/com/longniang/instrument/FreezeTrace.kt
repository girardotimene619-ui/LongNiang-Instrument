package com.longniang.instrument

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray

internal data class FreezeSample(
    val generation: Long, val rawFrame: Long, val chartUs: Long,
    val updateNs: Long, val drawNs: Long, val nowNs: Long, val running: Boolean
)

/** Independent observation. Nothing here gates playback, drawing, or judgment. */
internal class FreezeDetector(private val thresholdNs: Long = 200_000_000L) {
    private var previous: FreezeSample? = null
    private var chartChangedNs = 0L
    private var established = false
    private var reported = false

    fun observe(sample: FreezeSample): String? {
        val old = previous
        previous = sample
        if (old?.generation != sample.generation) {
            chartChangedNs = sample.nowNs
            established = sample.running
            reported = false
            return null
        }
        established = established || sample.running
        if (sample.chartUs != old.chartUs) chartChangedNs = sample.nowNs
        val issue = when {
            sample.nowNs - sample.updateNs > thresholdNs -> "UPDATE_STALLED"
            sample.nowNs - sample.drawNs > thresholdNs -> "CANVAS_POST_STALLED"
            sample.nowNs - chartChangedNs > thresholdNs -> "CHART_TIME_STALLED"
            else -> null
        }
        if (issue == null) reported = false
        if (!established || reported || issue == null || sample.rawFrame <= old.rawFrame) return null
        reported = true
        return issue
    }

    fun clear() { previous = null }
}

internal class FreezeTrace(private val capacity: Int = 2048) {
    private data class Record(val seq: Long, val ns: Long, val message: String)
    private val sequence = AtomicLong()
    private val ring = AtomicReferenceArray<Record>(capacity)

    fun record(message: String) {
        val seq = sequence.getAndIncrement()
        val entry = Record(seq, System.nanoTime(), message)
        val index = (seq % capacity).toInt()
        while (true) {
            val old = ring.get(index)
            if (old != null && old.seq >= seq) return
            if (ring.compareAndSet(index, old, entry)) return
        }
    }

    fun lines(): List<String> {
        val end = sequence.get()
        return (maxOf(0, end - capacity) until end).mapNotNull { seq ->
            ring.get((seq % capacity).toInt())?.takeIf { it.seq == seq }?.let { "${it.seq} ${it.ns} ${it.message}" }
        }
    }
}
