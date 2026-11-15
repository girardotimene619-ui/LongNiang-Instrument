package com.longniang.instrument

enum class TouchReference { DEPTH, JUDGMENT_LINE, JUDGMENT_STRIP }

/** Fractions of the inset-safe Surface, not device pixels or the full physical display. */
data class PlayfieldConfig(val widthFraction: Float = .70f, val spawnFraction: Float = .08f,
                           val lineFraction: Float = .82f, val hitAheadFraction: Float = .14f,
                           val hitBehindFraction: Float = .18f, val farWidthScale: Float = 1f,
                           val touchAheadPaddingFraction: Float = 0f,
                           val touchReference: TouchReference = TouchReference.DEPTH) {
    init {
        require(widthFraction > 0f && widthFraction < 1f)
        require(spawnFraction in 0f..<lineFraction && lineFraction < 1f)
        require(hitAheadFraction > 0f && lineFraction - hitAheadFraction >= spawnFraction)
        require(hitBehindFraction > 0f && lineFraction + hitBehindFraction <= 1f)
        require(farWidthScale > 0f && farWidthScale <= 1f)
        require(touchAheadPaddingFraction in 0f..(lineFraction - hitAheadFraction - spawnFraction))
    }
    companion object {
        val STAGE = PlayfieldConfig(widthFraction = .64f, spawnFraction = .025f, lineFraction = .76f,
            farWidthScale = .11f, touchAheadPaddingFraction = 10f / 1080f,
            touchReference = TouchReference.JUDGMENT_STRIP)
    }
}
data class Playfield(val width: Float, val height: Float, val config: PlayfieldConfig = PlayfieldConfig(), val columns: Int = 4,
                     internal val upperTouchComparison: Boolean = false) {
    init { require(columns > 0); require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0) }
    val trackWidth = width * config.widthFraction
    val left = (width - trackWidth) / 2f
    val right = left + trackWidth
    val spawnY = height * config.spawnFraction
    val lineY = height * config.lineFraction
    val hitTop = height * (config.lineFraction - config.hitAheadFraction)
    val hitBottom = height * (config.lineFraction + config.hitBehindFraction)
    val nearY = height
    val centerX = width / 2f
    val visualLineBottom = lineY + height * .065f

    fun stageScaleAt(y: Float): Float {
        if (lineY <= spawnY || y == lineY) return 1f
        return config.farWidthScale + (1f - config.farWidthScale) *
            ((y.coerceIn(spawnY, nearY) - spawnY) / (lineY - spawnY))
    }

    fun stageX(lane: Float, y: Float): Float {
        val lineX = left + trackWidth * lane / columns
        return if (y == lineY) lineX else centerX + (lineX - centerX) * stageScaleAt(y)
    }
    fun edge(index: Int): Float { require(index in 0..columns); return left + trackWidth * index / columns }
    fun center(lane: Int): Float { require(lane in 0 until columns); return (edge(lane) + edge(lane + 1)) / 2f }
    // Half-open lanes [edge(i), edge(i+1)); internal boundaries belong to the right lane.
    fun laneAt(x: Float): Int? {
        if (!x.isFinite() || trackWidth <= 0 || x < left || x >= right) return null
        return (0 until columns).firstOrNull { x < edge(it + 1) }
    }
    fun hitLane(x: Float, y: Float): Int? {
        return practiceLane(x, y, 0f)
    }
    internal val extraUpperTouchReferencePx get() = if (upperTouchComparison) 5 else 0

    internal fun inPracticeBand(y: Float, expansion: Float): Boolean =
        y.isFinite() && y >= practiceTop(expansion) && y < inputBottom(expansion)

    internal fun practiceTop(expansion: Float): Float {
        val top = if (expansion == 0f) hitTop else lineY - (lineY - hitTop) * (1f + expansion)
        val padding = config.touchAheadPaddingFraction + extraUpperTouchReferencePx / 1080f
        return top - height * padding
    }

    private fun inputBottom(expansion: Float) = if (expansion == 0f) hitBottom else
        minOf(height, lineY + (hitBottom - lineY) * (1f + expansion))

    internal fun practiceLane(x: Float, y: Float, expansion: Float): Int? =
        if (inPracticeBand(y, expansion)) inputLane(x, y) else null

    internal fun releaseLane(x: Float, y: Float, expansion: Float): Int? {
        val top = maxOf(spawnY, lineY - 4f * (visualLineBottom - lineY))
        return if (y.isFinite() && y >= top && y < inputBottom(expansion)) inputLane(x, y) else null
    }

    private fun inputLane(x: Float, y: Float): Int? {
        if (!x.isFinite() || trackWidth <= 0) return null
        val plane = touchPlaneY(y)
        if (x < stageX(0f, plane) || x >= stageX(columns.toFloat(), plane)) return null
        return (0 until columns).firstOrNull { x < stageX(it + 1f, plane) }
    }
    // Shift the front of the input grid toward the visible judgment strip. A bounded
    // shift keeps depth information near the upper edge for diagonal gestures.
    internal fun touchPlaneY(y: Float): Float = when (config.touchReference) {
        TouchReference.DEPTH -> y
        TouchReference.JUDGMENT_LINE -> lineY
        TouchReference.JUDGMENT_STRIP -> {
            val stripHeight = visualLineBottom - lineY
            val stripTop = lineY - stripHeight
            maxOf(y, minOf(stripTop, y + stripHeight * .75f))
        }
    }

    internal fun practicePoint(x: Float, y: Float, lane: Int?, releaseLane: Int? = lane): MixedPoint {
        val laneWidth = trackWidth / columns
        // Gesture deltas use local pixels in judgment-line lane widths on both axes.
        val lineX = (x - left) / laneWidth
        val plane = touchPlaneY(y)
        val laneX = (x - stageX(0f, plane)) / (trackWidth * stageScaleAt(plane)) * columns
        return MixedPoint(lineX, y / laneWidth, lane, laneX, releaseLane)
    }
}
