package com.longniang.instrument

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import com.longniang.instrument.sus.FlickDirection
import com.longniang.instrument.sus.JudgmentAction
import com.longniang.instrument.sus.LaneSpan
import com.longniang.instrument.sus.LongNoteKind
import java.util.WeakHashMap
import kotlin.math.abs

internal data class MixedFrame(
    val actions: List<MixedAction>, val paths: List<MixedPath>, val settled: Set<String>,
    val startUs: Long, val endUs: Long, val contacts: Set<String>,
    val guides: List<com.longniang.instrument.sus.GuideChartObject> = emptyList()
)

internal fun NoteProjection.hitEdges(span: LaneSpan, y: Float) = xAt(span.left, y) to xAt(span.right, y)

/** Owned by a single drawing thread. Layout values never feed back into judgment. */
internal class TrackDrawing {
    private val polygon = Path()
    private val curves = WeakHashMap<MixedPath, VisualRibbonCurve>()
    private val guideCurves = WeakHashMap<com.longniang.instrument.sus.GuideChartObject, VisualRibbonCurve>()
    private val flickGlyph = Path().apply {
        moveTo(-.60f, .05f)
        lineTo(-.08f, -.49f)
        quadTo(0f, -.57f, .08f, -.49f)
        lineTo(.60f, .05f)
        quadTo(.66f, .12f, .60f, .20f)
        lineTo(.48f, .34f)
        quadTo(.42f, .40f, .36f, .33f)
        lineTo(0f, -.03f)
        lineTo(-.36f, .33f)
        quadTo(-.42f, .40f, -.48f, .34f)
        lineTo(-.60f, .20f)
        quadTo(-.66f, .12f, -.60f, .05f)
        close()
    }
    private val gradientMatrix = Matrix()
    private val stageGradient = gradient(0xEE111B36.toInt(), 0xED343260.toInt())
    private val pressGradient = gradient(0x0056AFF5, 0xAB80EBFF.toInt())
    private val contactGlow = gradient(0x00AFFFE5, 0xB3BCFFF2.toInt())
    private val contactFootGlow = RadialGradient(0f, 0f, 1f, 0xBB6AFFDA.toInt(), 0x006AFFDA,
        Shader.TileMode.CLAMP)
    private val hitFootGlow = RadialGradient(0f, 0f, 1f, 0xC5FFFFFF.toInt(), 0x00FFFFFF,
        Shader.TileMode.CLAMP)
    private val normal = Surface(0xFFE5E5FF.toInt(), 0xFFC5F3FF.toInt(), 0xFF67CFFF.toInt())
    private val gold = Surface(0xFFFFF4BE.toInt(), 0xFFFFDF82.toInt(), 0xFFFFBC45.toInt())
    private val pink = Surface(0xFFFFE2F8.toInt(), 0xFFFFACD5.toInt(), 0xFFF77CC3.toInt())
    private val mint = Surface(0xFFDDFFF8.toInt(), 0xFFA4FBE4.toInt(), 0xFF62EBC9.toInt())
    private val flashBlue = gradient(0x004B65FF, 0xCF89F5FF.toInt())
    private val flashGold = gradient(0x00FF9B32, 0xE8FFF0A5.toInt())
    private val flashPink = gradient(0x00E346FF, 0xE8FFB9F4.toInt())
    private val flashMint = gradient(0x002DE7B4, 0xD9AAFFE8.toInt())
    private val comboGradient = gradient(0xFFFFBEF8.toInt(), 0xFFA4FAFF.toInt())
    private val hudTypeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private class Surface(top: Int, base: Int, val rim: Int) {
        val face = LinearGradient(0f, 0f, 0f, 1f, intArrayOf(top, Color.WHITE, base, rim),
            floatArrayOf(0f, .22f, .73f, 1f), Shader.TileMode.CLAMP)
    }

    fun stage(canvas: Canvas, paint: Paint, board: Playfield, projection: NoteProjection, pressed: Set<Int>,
              mirror: MirrorStage? = null, image: StageImage? = null, settings: StageImageSettings? = null) {
        paint.style = Paint.Style.FILL
        val columns = board.columns.toFloat()
        val stageX = projection::xAt
        val inputX = if (projection.style == TrackStyle.PERSPECTIVE) stageX else board::stageX
        fillGradient(paint, stageGradient, board.spawnY, board.nearY)
        if (settings != null) paint.alpha = (settings.trackOpacity.coerceIn(0f, 1f) * 255 + .5f).toInt()
        band(canvas, paint, stageX, 0f, columns, board.spawnY, board.lineY)
        band(canvas, paint, stageX, 0f, columns, board.lineY, board.nearY)
        paint.shader = null
        if (mirror != null && image != null && settings != null)
            mirror.reflect(canvas, board, projection, image, settings.reflectionStrength)
        paint.color = 0x553D315E
        band(canvas, paint, inputX, 0f, columns, board.lineY, board.visualLineBottom)
        paint.color = Color.WHITE
        val beamTop = board.lineY - (board.lineY - board.spawnY) * .46f
        fillGradient(paint, pressGradient, beamTop, board.lineY)
        for (lane in pressed) {
            band(canvas, paint, stageX, lane.toFloat(), lane + 1f, beamTop, board.lineY)
        }
        paint.shader = null
        paint.color = 0x397FE6FF
        for (lane in pressed)
            band(canvas, paint, inputX, lane.toFloat(), lane + 1f, board.lineY, board.visualLineBottom)

        for (i in 0..board.columns) {
            val lane = i.toFloat()
            val outer = i == 0 || i == board.columns
            paint.color = if (outer) 0xBC889BC9.toInt() else 0x369AACDA
            paint.strokeWidth = if (outer) 2f else 1f
            if (projection.style == TrackStyle.PERSPECTIVE) {
                canvas.drawLine(stageX(lane, board.spawnY), board.spawnY, stageX(lane, board.lineY), board.lineY, paint)
                canvas.drawLine(stageX(lane, board.lineY), board.lineY, stageX(lane, board.nearY), board.nearY, paint)
            } else {
                canvas.drawLine(stageX(lane, board.spawnY), board.spawnY, stageX(lane, board.hitTop), board.hitTop, paint)
                canvas.drawLine(inputX(lane, board.hitTop), board.hitTop, inputX(lane, board.hitBottom), board.hitBottom, paint)
                canvas.drawLine(stageX(lane, board.hitBottom), board.hitBottom, stageX(lane, board.nearY), board.nearY, paint)
            }
        }
        paint.color = 0xDAE79AF4.toInt()
        paint.strokeWidth = (board.height * .0042f).coerceAtLeast(2f)
        canvas.drawLine(board.left, board.lineY, board.right, board.lineY, paint)
        paint.color = 0x8BBB94F3.toInt()
        canvas.drawLine(inputX(0f, board.visualLineBottom), board.visualLineBottom,
            inputX(columns, board.visualLineBottom), board.visualLineBottom, paint)
    }

    fun mixed(canvas: Canvas, paint: Paint, board: Playfield, frame: MixedFrame, projection: NoteProjection) {
        val laneWidth = board.trackWidth / board.columns
        val maxUnit = NoteVisualStyle.unit(laneWidth, projection.scaleAt(board.nearY))
        val maxBelow = NoteVisualStyle.maxBelow(maxUnit, board.height)
        val maxAbove = NoteVisualStyle.maxAbove(maxUnit, board.height)
        val latest = minOf(projection.latestVisibleTimeUs(maxBelow), frame.endUs - 1)
        val upperBound = frame.actions.binarySearch { if (it.at <= latest) -1 else 1 }.inv()
        val save = canvas.save()
        try {
            canvas.clipRect(0f, board.spawnY, board.width, board.nearY)
            paint.style = Paint.Style.FILL
            for (guide in frame.guides) {
                val times = projection.pathTimes(guide.nodes, frame.startUs, frame.endUs)
                if (times.isEmpty()) continue
                val curve = guideCurves.getOrPut(guide) { VisualRibbonCurve(guide.nodes, board.columns) }
                paint.color = 0x3978EFC9
                ribbon(canvas, paint, pathPoints(curve, times, projection), 0f)
            }
            for (path in frame.paths) {
                val times = projection.pathTimes(path, frame.startUs, frame.endUs)
                if (times.isEmpty()) continue
                val curve = curves.getOrPut(path) { VisualRibbonCurve(path, board.columns) }
                val points = pathPoints(curve, times, projection)
                val hold = path.kind == LongNoteKind.HOLD
                paint.color = if (hold) 0x40153C49 else 0x401C3158
                val ribbonFront = NoteVisualStyle.ribbonFront(laneWidth)
                ribbon(canvas, paint, points, 0f, ribbonFront)
                paint.color = if (hold) 0x6879FAD4 else 0x6677CEFF
                ribbon(canvas, paint, points, 0f)
                paint.style = Paint.Style.STROKE
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (hold) 0x399AFFE8 else 0x399AE0FF
                paint.strokeWidth = laneWidth * .045f
                ribbon(canvas, paint, points, .018f)
                paint.color = if (hold) 0x748BFFE2 else 0x748AD9FF
                paint.strokeWidth = (laneWidth * .013f).coerceAtLeast(.7f)
                ribbon(canvas, paint, points, .018f)
                paint.style = Paint.Style.FILL
                if (path.id in frame.contacts) {
                    val until = minOf(times.last(), projection.nowUs + 260_000L)
                    val lit = times.takeWhile { it < until } + until
                    if (lit.size > 1) {
                        val litPoints = pathPoints(curve, lit, projection)
                        paint.color = Color.WHITE
                        val base = board.visualLineBottom
                        fillGradient(paint, contactGlow, litPoints.last().y, base)
                        val span = curve.spanAt(lit.first())
                        val left = projection.xAt(span.left, base)
                        val right = projection.xAt(span.right, base)
                        val foot = litPoints.first()
                        quad(canvas, paint, foot.left, foot.right, foot.y, left, right, base)
                        ribbon(canvas, paint, litPoints, 0f)
                        paint.shader = null
                        val height = laneWidth * .102f
                        softFoot(canvas, paint, contactFootGlow, left, right, base, height, 255)
                        paint.color = 0xEECFFFF4.toInt()
                        roundedCap(canvas, paint, left, right, base - height, base,
                            height, 0xFF83FFDE.toInt())
                    }
                }
                // Local Critical tint preserves the continuous underlying path.
                paint.color = 0xB9F4BE56.toInt()
                for (i in 1 until path.nodes.size) {
                    val node = path.nodes[i]
                    if (node.modifiers.criticalSource == null) continue
                    val start = maxOf(times.first(), path.nodes[i - 1].timeUs)
                    val end = minOf(times.last(), node.timeUs)
                    if (start < end) ribbon(canvas, paint,
                        pathPoints(curve, listOf(start, end), projection), .065f)
                }
            }
            for (i in upperBound - 1 downTo 0) {
                val note = frame.actions[i]
                if (projection.yAt(note.at) - maxBelow > board.nearY) break
                if (!note.visible || note.id in frame.settled) continue
                note(canvas, paint, board, projection, note.span.left, note.span.right, note.at,
                    critical = note.critical, long = note.path != null,
                    node = note.event.action == JudgmentAction.TICK, direction = note.direction,
                    trace = note.event.action == JudgmentAction.TRACE)
            }
        } finally {
            canvas.restoreToCount(save)
        }

        // Draw every directional marker after all ribbons and note bodies. The spawn edge clips
        // the body, while the glyph has room above the stage as soon as that body enters.
        val arrowSave = canvas.save()
        try {
            canvas.clipRect(0f, 0f, board.width, board.nearY)
            for (i in upperBound - 1 downTo 0) {
                val note = frame.actions[i]
                val y = projection.yAt(note.at)
                if (y - maxAbove > board.nearY) break
                val direction = note.direction ?: continue
                if (!note.visible || note.id in frame.settled) continue
                val unit = NoteVisualStyle.unit(laneWidth, projection.scaleAt(y))
                val face = NoteVisualStyle.face(unit, board.height, node = false)
                val front = NoteVisualStyle.front(unit, board.height, node = false, critical = note.critical)
                val halo = NoteVisualStyle.halo(unit)
                if (y + face / 2f + front + halo < board.spawnY) continue
                val size = NoteVisualStyle.arrowSize(unit, note.span.right - note.span.left)
                val centerY = NoteVisualStyle.arrowCenterY(y, face, unit, size)
                drawArrow(canvas, paint, projection.xAt(note.span.center, y), centerY + unit * .10f, size * .91f, direction, note.critical)
            }
        } finally {
            canvas.restoreToCount(arrowSave)
        }
    }

    fun note(canvas: Canvas, paint: Paint, board: Playfield, projection: NoteProjection,
             left: Float, right: Float, timeUs: Long, critical: Boolean = false, long: Boolean = false,
             node: Boolean = false, direction: FlickDirection? = null, trace: Boolean = false) {
        val surface = when {
            critical -> gold
            direction != null -> pink
            long || trace -> mint
            else -> normal
        }
        val y = projection.yAt(timeUs)
        val unit = NoteVisualStyle.unit(board.trackWidth / board.columns, projection.scaleAt(y))
        val depth = NoteVisualStyle.face(unit, board.height, node)
        val extrusion = NoteVisualStyle.front(unit, board.height, node, critical)
        val halo = NoteVisualStyle.halo(unit)
        if (!projection.visible(timeUs, depth / 2f + halo, depth / 2f + extrusion + halo)) return
        if (node) {
            val center = projection.xAt((left + right) * .5f, y)
            val radius = depth * .44f
            polygon.reset()
            polygon.moveTo(center, y - radius)
            polygon.lineTo(center + radius, y)
            polygon.lineTo(center, y + radius)
            polygon.lineTo(center - radius, y)
            polygon.close()
            drawRim(canvas, paint, surface.rim, halo * .55f)
            paint.color = if (critical) 0xFFFFF3BE.toInt() else 0xFFDEFFF5.toInt()
            canvas.drawPath(polygon, paint)
            return
        }

        if (trace) {
            val l = projection.xAt(left + NoteVisualStyle.laneInset, y)
            val r = projection.xAt(right - NoteVisualStyle.laneInset, y)
            val h = depth * .42f
            capsule(l, r, y - h, l, r, y + h, minOf(h, (r - l) * .2f))
            drawRim(canvas, paint, surface.rim, halo)
            paint.color = surface.rim
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (h * .43f).coerceAtLeast(.8f)
            canvas.drawPath(polygon, paint)
            paint.style = Paint.Style.FILL
            val marker = minOf(h * .48f, (r - l) * .12f)
            canvas.drawCircle((l + r) * .5f, y, marker, paint)
            return
        }
        val top = y - depth / 2f
        val bottom = y + depth / 2f
        val leftTop = projection.xAt(left + NoteVisualStyle.laneInset, top)
        val rightTop = projection.xAt(right - NoteVisualStyle.laneInset, top)
        val leftBottom = projection.xAt(left + NoteVisualStyle.laneInset, bottom)
        val rightBottom = projection.xAt(right - NoteVisualStyle.laneInset, bottom)
        val corner = minOf(depth * .46f, (rightTop - leftTop) * .23f)
        capsule(leftTop, rightTop, top, leftBottom, rightBottom, bottom + extrusion, corner)
        drawRim(canvas, paint, surface.rim, halo)
        paint.color = Color.WHITE
        fillGradient(paint, surface.face, top, bottom + extrusion)
        canvas.drawPath(polygon, paint)
        paint.shader = null
        paint.color = 0x65FFFFFF
        paint.strokeWidth = (depth * .065f).coerceAtLeast(.65f)
        paint.style = Paint.Style.STROKE
        canvas.drawPath(polygon, paint)
        paint.style = Paint.Style.FILL
    }

    private fun capsule(lt: Float, rt: Float, top: Float, lb: Float, rb: Float, bottom: Float, corner: Float) {
        polygon.reset()
        polygon.moveTo(lt + corner, top)
        polygon.lineTo(rt - corner, top)
        polygon.quadTo(rt, top, rt, top + corner)
        polygon.lineTo(rb, bottom - corner)
        polygon.quadTo(rb, bottom, rb - corner, bottom)
        polygon.lineTo(lb + corner, bottom)
        polygon.quadTo(lb, bottom, lb, bottom - corner)
        polygon.lineTo(lt, top + corner)
        polygon.quadTo(lt, top, lt + corner, top)
        polygon.close()
    }

    private fun drawRim(canvas: Canvas, paint: Paint, color: Int, halo: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        paint.alpha = 45
        paint.strokeWidth = halo * 2f
        canvas.drawPath(polygon, paint)
        paint.alpha = 105
        paint.strokeWidth = halo
        canvas.drawPath(polygon, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun roundedCap(canvas: Canvas, paint: Paint, left: Float, right: Float, top: Float,
                           bottom: Float, radius: Float, rim: Int) {
        val fill = paint.color
        capsule(left, right, top, left, right, bottom, minOf(radius, (right - left) * .22f))
        drawRim(canvas, paint, rim, radius * .75f)
        paint.color = fill
        canvas.drawPath(polygon, paint)
    }

    fun hits(canvas: Canvas, paint: Paint, board: Playfield, projection: NoteProjection, hits: List<HitFlash>) {
        val unit = board.trackWidth / board.columns
        val line = board.visualLineBottom
        for (index in maxOf(0, hits.size - 16) until hits.size) {
            val hit = hits[index]
            val remaining = hit.remaining(projection.nowUs)
            val progress = 1f - remaining
            val trace = hit.cue == HitCue.TRACE || hit.cue == HitCue.CRITICAL_TRACE
            val minor = hit.cue == HitCue.TICK
            val flick = hit.cue == HitCue.FLICK
            val (left, right) = projection.hitEdges(hit.span, line)
            val rise = unit * when {
                minor -> .28f
                trace -> .43f
                flick -> 1.53f
                hit.cue == HitCue.TAIL -> .72f
                else -> 1.16f
            } * (1f + .46f * progress)
            val drift = when (hit.direction) {
                FlickDirection.UPPER_LEFT -> -rise * .70f
                FlickDirection.UPPER_RIGHT -> rise * .70f
                else -> 0f
            }
            val top = line - rise
            val color = when {
                hit.critical -> 0xFFFFE088.toInt()
                hit.cue == HitCue.FLICK -> 0xFFFFA8F7.toInt()
                trace || hit.cue in LONG_CUES -> 0xFF9AFFDC.toInt()
                else -> 0xFFB1EAFF.toInt()
            }
            val glow = when {
                hit.critical -> flashGold
                hit.cue == HitCue.FLICK -> flashPink
                trace || hit.cue in LONG_CUES -> flashMint
                else -> flashBlue
            }
            paint.color = Color.WHITE
            paint.alpha = (remaining * remaining * if (minor) 135 else 250).toInt()
            fillGradient(paint, glow, top, line)
            quad(canvas, paint, projection.xAt(hit.span.left, top) + drift,
                projection.xAt(hit.span.right, top) + drift, top, left, right, line)
            paint.shader = null
            paint.color = color
            paint.alpha = (remaining * 235).toInt()
            val bandHeight = unit * if (minor) .055f else .09f
            canvas.drawRoundRect(left, line - bandHeight, right, line, bandHeight, bandHeight, paint)
            if (!minor) {
                val spread = unit * progress * .18f
                softFoot(canvas, paint, hitFootGlow, left - spread, right + spread, line, bandHeight,
                    (remaining * remaining * if (hit.critical) 245 else 212).toInt())
                paint.color = Color.WHITE
                paint.alpha = (remaining * 216).toInt()
                canvas.drawRoundRect(left, line - bandHeight * .55f, right, line,
                    bandHeight, bandHeight, paint)
                paint.color = color
                paint.alpha = (remaining * 235).toInt()
                val size = unit * .055f * remaining
                for (i in 1..(if (trace) 2 else 4)) {
                    val x = left + (right - left) * i / 5 + drift * progress
                    val y = line - rise * progress * (.45f + .14f * i)
                    quad(canvas, paint, x - size, x, y - size * 2, x, x + size, y + size)
                }
            }
        }
        paint.alpha = 255
        val primary = hits.lastOrNull { it.cue != HitCue.TICK } ?: return
        val alpha = (primary.remaining(projection.nowUs) * 235).toInt()
        text(canvas, paint, primary.grade.name, board.width * .5f, line - unit * .55f,
            minOf(board.height * .045f, unit * .52f), alpha)
    }

    private fun softFoot(canvas: Canvas, paint: Paint, shader: RadialGradient, left: Float, right: Float,
                         y: Float, height: Float, alpha: Int) {
        val radiusX = (right - left) * .57f
        val radiusY = height * 3.4f
        val x = (left + right) / 2f
        val save = canvas.save()
        canvas.clipRect(x - radiusX, y - radiusY, x + radiusX, y)
        gradientMatrix.setScale(radiusX, radiusY)
        gradientMatrix.postTranslate(x, y)
        shader.setLocalMatrix(gradientMatrix)
        paint.color = Color.WHITE
        paint.alpha = alpha
        paint.shader = shader
        canvas.drawOval(x - radiusX, y - radiusY, x + radiusX, y + radiusY, paint)
        canvas.restoreToCount(save)
        paint.shader = null
        paint.alpha = 255
    }

    fun combo(canvas: Canvas, paint: Paint, board: Playfield, count: Int) {
        val x = board.width * .901f
        val y = board.height * .41f
        val size = minOf(board.height * .10f, board.width * .065f)
        text(canvas, paint, "COMBO", x, y - size * .84f, size * .29f, 255)
        text(canvas, paint, count.toString(), x, y, size, 255)
    }

    private fun text(canvas: Canvas, paint: Paint, value: String, x: Float, y: Float, size: Float, alpha: Int) {
        val previousTypeface = paint.typeface
        paint.typeface = hudTypeface
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF60477F.toInt()
        paint.alpha = alpha / 3
        paint.strokeWidth = size * .16f
        canvas.drawText(value, x, y, paint)
        paint.color = 0xFF202447.toInt()
        paint.alpha = alpha
        paint.strokeWidth = size * .075f
        canvas.drawText(value, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        fillGradient(paint, comboGradient, y - size * .74f, y)
        canvas.drawText(value, x, y, paint)
        paint.shader = null
        paint.alpha = 255
        paint.typeface = previousTypeface
        paint.textAlign = Paint.Align.LEFT
    }

    private companion object {
        val LONG_CUES = setOf(HitCue.HEAD, HitCue.TICK, HitCue.TAIL)
    }

    private fun drawArrow(canvas: Canvas, paint: Paint, x: Float, y: Float, size: Float,
                          direction: FlickDirection, critical: Boolean) {
        val save = canvas.save()
        canvas.translate(x, y)
        canvas.rotate(NoteVisualStyle.arrowAngle(direction))
        canvas.scale(size, size)
        paint.color = if (critical) 0xFFFFCB62.toInt() else 0xFFFCAEF0.toInt()
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeWidth = .19f
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(flickGlyph, paint)
        paint.color = 0xFF161B35.toInt()
        paint.strokeWidth = .105f
        canvas.drawPath(flickGlyph, paint)
        paint.style = Paint.Style.FILL
        paint.color = if (critical) 0xFFFFF8D6.toInt() else 0xFFFFF0FA.toInt()
        canvas.drawPath(flickGlyph, paint)
        canvas.restoreToCount(save)
    }

    private fun band(canvas: Canvas, paint: Paint, x: (Float, Float) -> Float,
                     left: Float, right: Float, top: Float, bottom: Float) {
        quad(canvas, paint, x(left, top), x(right, top), top, x(left, bottom), x(right, bottom), bottom)
    }

    private fun quad(canvas: Canvas, paint: Paint, lt: Float, rt: Float, top: Float,
                     lb: Float, rb: Float, bottom: Float) {
        polygon.reset()
        polygon.moveTo(lt, top)
        polygon.lineTo(rt, top)
        polygon.lineTo(rb, bottom)
        polygon.lineTo(lb, bottom)
        polygon.close()
        canvas.drawPath(polygon, paint)
    }

    private data class RibbonPoint(val left: Float, val right: Float, val y: Float, val scale: Float)

    private fun pathPoints(curve: VisualRibbonCurve, times: List<Long>, projection: NoteProjection): List<RibbonPoint> {
        fun point(at: Long): RibbonPoint {
            val span = curve.spanAt(at)
            val y = projection.yAt(at)
            return RibbonPoint(projection.xAt(span.left, y), projection.xAt(span.right, y), y, projection.scaleAt(y))
        }
        val points = ArrayList<RibbonPoint>()
        fun append(a: Long, start: RibbonPoint, b: Long, end: RibbonPoint, depth: Int) {
            val middle = a + (b - a) / 2
            if (depth == 6 || middle == a) {
                points += end
                return
            }
            val mid = point(middle)
            val height = start.y - end.y
            val fraction = if (height > 0f) (start.y - mid.y) / height else .5f
            val leftError = abs(mid.left - (start.left + fraction * (end.left - start.left)))
            val rightError = abs(mid.right - (start.right + fraction * (end.right - start.right)))
            if (height <= 9f && maxOf(leftError, rightError) <= .35f) {
                points += end
            } else {
                append(a, start, middle, mid, depth + 1)
                append(middle, mid, b, end, depth + 1)
            }
        }
        points += point(times.first())
        for (i in 1 until times.size)
            append(times[i - 1], points.last(), times[i], point(times[i]), 0)
        return points
    }

    private fun ribbon(canvas: Canvas, paint: Paint,
                       points: List<RibbonPoint>, inset: Float, thickness: Float = 0f) {
        polygon.reset()
        for ((i, point) in points.withIndex()) {
            val x = point.left + (point.right - point.left) * inset
            val y = point.y + thickness * point.scale
            if (i == 0) polygon.moveTo(x, y) else polygon.lineTo(x, y)
        }
        for (i in points.indices.reversed()) {
            val point = points[i]
            polygon.lineTo(point.right - (point.right - point.left) * inset, point.y + thickness * point.scale)
        }
        polygon.close()
        canvas.drawPath(polygon, paint)
    }

    private fun gradient(top: Int, bottom: Int) = LinearGradient(0f, 0f, 0f, 1f, top, bottom, Shader.TileMode.CLAMP)

    private fun fillGradient(paint: Paint, gradient: LinearGradient, top: Float, bottom: Float) {
        gradientMatrix.setScale(1f, bottom - top)
        gradientMatrix.postTranslate(0f, top)
        gradient.setLocalMatrix(gradientMatrix)
        paint.shader = gradient
    }
}
