package com.longniang.instrument

import com.longniang.instrument.sus.LaneSpan
import com.longniang.instrument.sus.PathRole
import kotlin.math.abs

/** Renderer-only spline. It never supplies a span to the judgment engines. */
internal class VisualRibbonCurve(private val nodes: List<com.longniang.instrument.sus.PathNode>, private val columns: Int) {
    constructor(path: MixedPath, columns: Int) : this(path.nodes, columns)
    private val times = nodes.map { it.timeUs }.toLongArray()
    private val centers = nodes.map { it.interval.span().center }.toFloatArray()
    private val widths = nodes.map { it.interval.width.toFloat() }.toFloatArray()
    private val centerSlopes = slopes(centers)
    private val widthSlopes = slopes(widths)
    private val corners = BooleanArray(nodes.size) { i ->
        // A rapid, constant-width reversal at a visible waypoint is an authored-looking turn.
        // Width accents and invisible anchors still use the smooth envelope.
        i > 0 && i < nodes.lastIndex && nodes[i].role == PathRole.RELAY &&
            widths[i - 1] == widths[i] && widths[i] == widths[i + 1] &&
            times[i + 1] - times[i - 1] <= 125_000 &&
            abs(centers[i] - centers[i - 1]) >= .5f &&
            abs(centers[i + 1] - centers[i]) >= .5f &&
            (centers[i] - centers[i - 1]) * (centers[i + 1] - centers[i]) < 0f
    }

    fun spanAt(time: Long): LaneSpan {
        val found = times.binarySearch(time)
        if (found >= 0) return nodes[found].interval.span()
        val right = -found - 1
        if (right == 0) return nodes.first().interval.span()
        if (right == nodes.size) return nodes.last().interval.span()
        val left = right - 1
        val duration = (times[right] - times[left]).toFloat()
        val t = (time - times[left]) / duration
        val u = 1f - t
        val progress = t * t * (3f - 2f * t)
        val startTangent = t * u * u
        val endTangent = -t * t * u
        val delta = centers[right] - centers[left]
        val center = centers[left] + progress * delta +
            startTangent * (if (corners[left]) delta else duration * centerSlopes[left]) +
            endTangent * (if (corners[right]) delta else duration * centerSlopes[right])
        val width = widths[left] + progress * (widths[right] - widths[left]) +
            duration * (startTangent * widthSlopes[left] + endTangent * widthSlopes[right])
        val half = width.coerceIn(1f, columns.toFloat()) * .5f
        val bounded = center.coerceIn(half, columns - half)
        return LaneSpan(bounded - half, bounded + half)
    }

    private fun slopes(values: FloatArray): FloatArray {
        val secants = FloatArray(values.size - 1) { i ->
            val dt = times[i + 1] - times[i]
            if (dt == 0L) 0f else (values[i + 1] - values[i]) / dt
        }
        return FloatArray(values.size) { i ->
            if (i == 0) secants.first()
            else if (i == values.lastIndex) secants.last()
            else {
                val a = secants[i - 1]
                val b = secants[i]
                if (a * b <= 0f) 0f else {
                    val before = (times[i] - times[i - 1]).toFloat()
                    val after = (times[i + 1] - times[i]).toFloat()
                    val w1 = 2f * after + before
                    val w2 = after + 2f * before
                    (w1 + w2) / (w1 / a + w2 / b)
                }
            }
        }
    }
}
