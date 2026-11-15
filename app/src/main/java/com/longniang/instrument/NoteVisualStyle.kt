package com.longniang.instrument

import com.longniang.instrument.sus.FlickDirection

/** Drawing dimensions only. Units here never enter Playfield or judgment. */
internal object NoteVisualStyle {
    const val thicknessScale = 1.16f
    const val laneInset = .045f
    const val minArrowSize = 14f
    const val arrowRadius = .90f // Includes the stroke and both diagonal rotations.

    fun unit(laneWidth: Float, scale: Float) = (laneWidth * scale).coerceAtLeast(8f)

    fun face(unit: Float, height: Float, node: Boolean) =
        (unit * if (node) .20f else .34f).coerceIn(if (node) 4.5f else 7f, height * .067f + 7f) * thicknessScale

    fun front(unit: Float, height: Float, node: Boolean, critical: Boolean) =
        (unit * when { node -> .09f; critical -> .185f; else -> .145f })
            .coerceIn(if (node) 2f else 3.5f, height * .034f + 4f) * thicknessScale

    fun ribbonFront(laneWidth: Float) = laneWidth * .095f * thicknessScale
    fun halo(unit: Float) = (unit * .065f).coerceAtLeast(1.5f)
    fun arrowSize(unit: Float, lanes: Float) =
        (unit * (.70f + .045f * (lanes - 1f).coerceIn(0f, 6f))).coerceAtLeast(minArrowSize)
    fun arrowGap(unit: Float) = (unit * .05f).coerceAtLeast(1.5f)

    fun arrowAngle(direction: FlickDirection) = when (direction) {
        FlickDirection.UP -> 0f // Neutral display for the existing omni gesture.
        FlickDirection.UPPER_LEFT -> -34f
        FlickDirection.UPPER_RIGHT -> 34f
    }

    fun arrowCenterY(y: Float, face: Float, unit: Float, size: Float) =
        (y - face / 2f - arrowGap(unit) - size * arrowRadius).coerceAtLeast(size * arrowRadius)

    fun maxAbove(unit: Float, height: Float) =
        face(unit, height, false) / 2f + arrowGap(unit) + 2f * arrowRadius * arrowSize(unit, 12f) + 1f
    fun maxBelow(unit: Float, height: Float) =
        face(unit, height, false) / 2f + front(unit, height, false, true) + halo(unit)
}
