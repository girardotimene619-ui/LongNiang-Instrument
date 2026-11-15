package com.longniang.instrument

import kotlin.math.ceil

internal enum class TrackStyle { FLAT, PERSPECTIVE }

/** Screen-space projection only. Playfield still owns touch mapping. */
internal class NoteProjection(
    private val board: Playfield, val nowUs: Long, speed: NoteSpeed,
    val style: TrackStyle = TrackStyle.FLAT
) {
    private val travelUs = speed.travelUs
    private val span = (board.lineY - board.spawnY).toDouble()
    // Tune motion independently of the stage silhouette. Tangents meet at both ends.
    private val convergence = 2.0
    private val nearSlope = 1 + convergence
    private val farSlope = 1 / nearSlope

    val spawnTimeUs get() = nowUs + travelUs

    fun yAt(targetUs: Long): Float {
        val depth = (targetUs - nowUs).toDouble() / travelUs
        if (style == TrackStyle.FLAT) return (board.lineY - span * depth).toFloat()
        if (targetUs == spawnTimeUs) return board.spawnY
        val distance = when {
            depth < 0 -> nearSlope * depth
            depth > 1 -> 1 + farSlope * (depth - 1)
            else -> nearSlope * depth / (1 + convergence * depth)
        }
        return (board.lineY - span * distance).toFloat()
    }

    fun scaleAt(y: Float): Float {
        if (style == TrackStyle.FLAT || span <= 0) return 1f
        val depth = (y.coerceIn(board.spawnY, board.nearY) - board.lineY) / span.toFloat()
        // Keep the hit row registered to the input grid. The silhouette tapers independently.
        return if (depth <= 0f) 1f + .935f * depth else 1f + .78f * depth
    }

    fun xAt(lane: Float, y: Float): Float = if (style == TrackStyle.FLAT)
        board.left + board.trackWidth * lane / board.columns else
        board.centerX + (lane / board.columns - .5f) * board.trackWidth * scaleAt(y)

    fun laneStrip(lane: Int) = LaneStrip(
        xAt(lane.toFloat(), board.spawnY), xAt(lane + 1f, board.spawnY), board.spawnY,
        xAt(lane.toFloat(), board.nearY), xAt(lane + 1f, board.nearY), board.nearY
    )

    fun visible(targetUs: Long, abovePx: Float, belowPx: Float): Boolean {
        val center = yAt(targetUs)
        return center + belowPx >= board.spawnY && center - abovePx <= board.height
    }

    /** The lower edge of a mark can appear before its center reaches spawn. */
    fun latestVisibleTimeUs(belowPx: Float): Long {
        if (span <= 0) return spawnTimeUs
        val slope = if (style == TrackStyle.FLAT) 1.0 else farSlope
        return spawnTimeUs + ceil(belowPx / (span * slope) * travelUs).toLong()
    }

    /** Retain segments crossing the visible interval, including offscreen heads and tails. */
    fun pathTimes(path: MixedPath, segmentStartUs: Long, segmentEndUs: Long): List<Long> {
        return pathTimes(path.nodes, segmentStartUs, segmentEndUs)
    }

    fun pathTimes(nodes: List<com.longniang.instrument.sus.PathNode>, segmentStartUs: Long, segmentEndUs: Long): List<Long> {
        val start = maxOf(nowUs, segmentStartUs, nodes.first().timeUs)
        val end = minOf(spawnTimeUs, segmentEndUs, nodes.last().timeUs)
        if (start >= end) return emptyList()
        return buildList {
            add(start)
            for (node in nodes) if (node.timeUs > start && node.timeUs < end) add(node.timeUs)
            add(end)
        }
    }
}

internal data class LaneStrip(
    val leftFar: Float, val rightFar: Float, val farY: Float,
    val leftNear: Float, val rightNear: Float, val nearY: Float
)
