package com.longniang.instrument

import java.io.File

/** Bounded CPU-side measurements. Posting a buffer does not prove it was presented. */
internal class RenderPerformanceCapture {
    private val capacity = 36_000
    private val stride = 16
    private val sessions = mutableListOf<String>()
    private val values = LongArray(capacity * stride)
    private var count = 0L
    private var stopped = false
    @Volatile var uiFrameNs = -1L
    @Volatile var uiDeadlineNs = -1L
    @Volatile var uiDroppedCallbacks = 0L
    @Volatile var gcCount = -1L
    @Volatile var gcTimeMs = -1L

    @Synchronized fun session(metadata: String): Int {
        sessions.add(metadata)
        return sessions.size
    }

    @Synchronized
    fun frame(start: Long, acquired: Long, drawn: Long, posted: Long, chartUs: Long,
              snapshotNs: Long, stageNs: Long, feedbackNs: Long, updateNs: Long,
              generation: Long, revision: Long, sessionId: Int) {
        if (stopped) return
        val i = (count++ % capacity).toInt() * stride
        values[i] = start
        values[i + 1] = acquired - start
        values[i + 2] = drawn - acquired
        values[i + 3] = posted - drawn
        values[i + 4] = chartUs
        values[i + 5] = snapshotNs
        values[i + 6] = stageNs
        values[i + 7] = feedbackNs
        values[i + 8] = updateNs
        values[i + 9] = generation
        values[i + 10] = revision
        values[i + 11] = uiFrameNs
        values[i + 12] = uiDeadlineNs
        values[i + 13] = gcCount
        values[i + 14] = gcTimeMs
        values[i + 15] = sessionId.toLong()
    }

    @Synchronized fun stop() { stopped = true }

    // Called by the export worker after stop. The ring is immutable at this point.
    fun export(file: File) {
        val first = maxOf(0, count - capacity)
        file.bufferedWriter().use { out ->
            out.appendLine("# frame_rows_overwritten=$first; ui_metrics_callbacks_dropped=$uiDroppedCallbacks")
            sessions.forEachIndexed { index, metadata -> out.appendLine("# session_id=${index + 1}; $metadata") }
            out.appendLine("seq,start_ns,start_interval_ns,acquire_ns,render_ns,post_ns,chart_us,snapshot_with_lock_ns,stage_ns,feedback_ns,last_update_ns,generation,revision,last_window_frame_ns,last_window_input_animation_layout_ns,gc_count,gc_time_ms,session_id")
            var previous = 0L
            val intervals = LongArray((count - first).toInt())
            var n = 0
            var drawTotal = 0L
            for (seq in first until count) {
                val base = (seq % capacity).toInt() * stride
                val start = values[base]
                val interval = if (previous == 0L || seqGeneration(seq - 1) != values[base + 9] ||
                    values[((seq - 1) % capacity).toInt() * stride + 10] != values[base + 10] ||
                    values[((seq - 1) % capacity).toInt() * stride + 15] != values[base + 15]) 0L else start - previous
                if (interval > 0) intervals[n++] = interval
                previous = start
                drawTotal += values[base + 2]
                out.append("$seq,$start,$interval")
                for (j in 1 until stride) out.append(',').append(values[base + j].toString())
                out.newLine()
            }
            if (n > 0) {
                val sorted = intervals.copyOf(n).apply { sort() }
                val mean = sorted.average()
                fun percentile(ratio: Double) = sorted[(kotlin.math.ceil(n * ratio).toInt() - 1).coerceAtLeast(0)] / 1e6
                fun budget(ns: Long): String {
                    val slow = sorted.count { it > ns }
                    return "$slow/$n (${100.0 * slow / n}%)"
                }
                out.appendLine("# producer_fps=${1e9 / mean}; mean_ms=${mean / 1e6}; p50_ms=${percentile(.50)}; p95_ms=${percentile(.95)}; p99_ms=${percentile(.99)}; over_8.33ms=${budget(8_333_333)}; over_16.67ms=${budget(16_666_667)}; over_33.33ms=${budget(33_333_333)}; mean_render_ms=${drawTotal / (count - first) / 1e6}")
            }
        }
    }

    private fun seqGeneration(seq: Long) = values[(seq % capacity).toInt() * stride + 9]
}
