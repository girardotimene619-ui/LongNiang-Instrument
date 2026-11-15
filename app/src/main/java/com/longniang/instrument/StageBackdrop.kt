package com.longniang.instrument

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/** Background-only lighting in normalized viewport coordinates, behind the original stage. */
internal class StageBackdrop {
    private val paint = Paint()
    private val transform = Matrix()
    private val base = LinearGradient(0f, 0f, .12f, 1f,
        intArrayOf(0xFF141B37.toInt(), 0xFF202850.toInt(), 0xFF242444.toInt()),
        floatArrayOf(0f, .56f, 1f), Shader.TileMode.CLAMP)
    private val distance = haze(0x496487DC, 0x265C7DD0, 0x095E75C9)
    private val coolWash = haze(0x725BBAE5, 0x4A3C93CD, 0x142D83C5)
    private val warmWash = haze(0x67D276C9, 0x44984BBD, 0x12694FB4)
    private val farViolet = haze(0x425F59B9, 0x265951AD, 0x0B4E469C)
    private val nearMist = haze(0x3F65B5C9, 0x244FA1C9, 0x0A418EC2)

    fun draw(canvas: Canvas, width: Float, height: Float) {
        val saved = canvas.save()
        canvas.scale(width, height)
        layer(canvas, base)
        light(canvas, farViolet, .80f, .13f, .43f, .42f)
        light(canvas, distance, .48f, .31f, .62f, .27f)
        light(canvas, coolWash, .07f, .84f, .44f, .55f)
        light(canvas, warmWash, .94f, .80f, .42f, .52f)
        light(canvas, nearMist, .27f, 1.12f, .56f, .36f)
        canvas.restoreToCount(saved)
    }

    private fun layer(canvas: Canvas, shader: Shader) {
        paint.shader = shader
        canvas.drawRect(0f, 0f, 1f, 1f, paint)
    }

    private fun light(canvas: Canvas, shader: RadialGradient, x: Float, y: Float, rx: Float, ry: Float) {
        transform.setScale(rx, ry)
        transform.postTranslate(x, y)
        shader.setLocalMatrix(transform)
        layer(canvas, shader)
    }

    private fun haze(core: Int, mid: Int, edge: Int) = RadialGradient(0f, 0f, 1f,
        intArrayOf(core, mid, edge, edge and 0xFFFFFF),
        floatArrayOf(0f, .35f, .72f, 1f), Shader.TileMode.CLAMP)
}
