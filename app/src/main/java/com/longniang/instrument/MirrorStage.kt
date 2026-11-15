package com.longniang.instrument

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

internal enum class StageImageMode { GRADIENT, IMAGE, MIRROR }

internal data class StageImageSettings(
    val mode: StageImageMode = StageImageMode.GRADIENT,
    val backgroundBrightness: Float = .56f,
    val reflectionStrength: Float = .68f,
    val trackOpacity: Float = .59f
)

/** Owns only stage images. Settings and prepared images are handed off as immutable snapshots. */
internal class MirrorStage {
    private companion object {
        const val PORTRAIT_PANEL_TOP = .05f
        const val PORTRAIT_MIRROR_AXIS = .56f
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val placement = RectF()
    private val matrix = Matrix()
    private val corners = FloatArray(8)

    fun background(canvas: Canvas, board: Playfield, image: StageImage, brightness: Float) {
        paint.color = Color.WHITE
        val source = image.background
        val width = board.width
        val height = board.height
        val cover = maxOf(width / source.width, height / source.height)
        placement.set((width - source.width * cover) / 2, (height - source.height * cover) / 2,
            (width + source.width * cover) / 2, (height + source.height * cover) / 2)
        paint.alpha = (brightness.coerceIn(0f, 1f) * if (image.isPortrait) 100 else 255).roundToInt()
        canvas.drawBitmap(source, null, placement, paint)
        if (image.isPortrait) {
            val panelHeight = height * (PORTRAIT_MIRROR_AXIS - PORTRAIT_PANEL_TOP)
            val halfWidth = panelHeight * source.width / source.height / 2
            placement.set(width / 2 - halfWidth, height * PORTRAIT_PANEL_TOP,
                width / 2 + halfWidth, height * PORTRAIT_MIRROR_AXIS)
            paint.alpha = (brightness.coerceIn(0f, 1f) * 255).roundToInt()
            canvas.drawBitmap(source, null, placement, paint)
        }
    }

    fun reflect(canvas: Canvas, board: Playfield, projection: NoteProjection, image: StageImage, strength: Float) {
        val top = board.height * if (image.isPortrait) PORTRAIT_MIRROR_AXIS else .49f
        val bottom = board.nearY
        val left = board.columns * .18f
        val right = board.columns - left
        corners[0] = projection.xAt(left, top); corners[1] = top
        corners[2] = projection.xAt(right, top); corners[3] = top
        corners[4] = projection.xAt(right, bottom); corners[5] = bottom
        corners[6] = projection.xAt(left, bottom); corners[7] = bottom
        if (!matrix.setPolyToPoly(image.textureCorners, 0, corners, 0, 4)) return
        paint.color = Color.WHITE
        paint.alpha = (strength.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmap(image.reflection, matrix, paint)
    }
}

internal class StageImage private constructor(
    val background: Bitmap,
    val reflection: Bitmap,
    val isPortrait: Boolean
) {
    val textureCorners = floatArrayOf(0f, 0f, reflection.width.toFloat(), 0f,
        reflection.width.toFloat(), reflection.height.toFloat(), 0f, reflection.height.toFloat())

    companion object {
        /** Prepares a soft, faded mirror texture once, off the rendering thread. */
        fun prepare(source: Bitmap, sampleBottom: Float = 1f): StageImage {
            val height = (source.height * sampleBottom).roundToInt().coerceIn(1, source.height)
            val crop = Bitmap.createBitmap(source, 0, 0, source.width, height)
            val portrait = source.height > source.width
            val texture = Bitmap.createBitmap(256, 160, Bitmap.Config.ARGB_8888)
            val soft = Bitmap.createBitmap(128, 80, Bitmap.Config.ARGB_8888)
            val filter = Paint(Paint.FILTER_BITMAP_FLAG)
            val sampling = if (portrait) Rect(0, 0, crop.width, crop.height) else
                Rect(crop.width / 8, crop.height / 6, crop.width * 7 / 8, crop.height * 5 / 6)
            Canvas(soft).drawBitmap(crop, sampling, Rect(0, 0, soft.width, soft.height), filter)
            val canvas = Canvas(texture)
            canvas.translate(0f, texture.height.toFloat())
            canvas.scale(1f, -1f)
            canvas.drawBitmap(soft, null, Rect(0, 0, texture.width, texture.height), filter)
            soft.recycle()
            val pixels = IntArray(texture.width * texture.height)
            texture.getPixels(pixels, 0, texture.width, 0, 0, texture.width, texture.height)
            for (y in 0 until texture.height) for (x in 0 until texture.width) {
                val depth = y / (texture.height - 1f)
                val fade = smooth(depth / .13f) * (1f - smooth((depth - .24f) / .76f))
                val side = smooth(minOf(x, texture.width - 1 - x) / (texture.width * .18f))
                val i = y * texture.width + x
                val color = pixels[i]
                pixels[i] = Color.argb((195 * fade * side * Color.alpha(color) / 255).roundToInt(),
                    (Color.red(color) * .69f + 12).roundToInt(),
                    (Color.green(color) * .72f + 15).roundToInt(),
                    (Color.blue(color) * .75f + 26).roundToInt())
            }
            texture.setPixels(pixels, 0, texture.width, 0, 0, texture.width, texture.height)
            return StageImage(crop, texture, portrait)
        }

        private fun smooth(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
