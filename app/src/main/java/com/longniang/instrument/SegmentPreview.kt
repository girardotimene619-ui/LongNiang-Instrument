package com.longniang.instrument

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class SegmentPreview(context: Context, private val chart: MixedChart,
                              private val durationUs: Long, private val speed: NoteSpeed,
                              private val style: TrackStyle, private val settings: StageImageSettings,
                              private val image: StageImage?) : View(context) {
    private val drawing = TrackDrawing()
    private val backdrop = StageBackdrop()
    private val mirror = MirrorStage()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = MixedFrame(chart.actions, chart.paths, emptySet(), 0, durationUs, emptySet(), chart.guides)
    var at = 0L
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val board = Playfield(width.toFloat(), height.toFloat(), PlayfieldConfig.STAGE, chart.columns)
        val projection = NoteProjection(board, at, speed, style)
        backdrop.draw(canvas, board.width, board.height)
        if (image != null && settings.mode != StageImageMode.GRADIENT)
            mirror.background(canvas, board, image, settings.backgroundBrightness)
        if (image != null && settings.mode == StageImageMode.MIRROR)
            drawing.stage(canvas, paint, board, projection, emptySet(), mirror, image, settings)
        else drawing.stage(canvas, paint, board, projection, emptySet())
        drawing.mixed(canvas, paint, board, frame, projection)
    }
}
