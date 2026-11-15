package com.longniang.instrument

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal class Frontend(private val context: Context) {
    private val world by lazy { bitmap("world.jpg") }
    private val dragon by lazy { bitmap("dragon.png") }
    private val covers = object : android.util.LruCache<String, Bitmap>(32) {}
    private fun bitmap(name: String) = context.assets.open("theme/$name").use { BitmapFactory.decodeStream(it) }
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun label(value: String, size: Float = 16f) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
    }
    fun glass(active: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (active) intArrayOf(0xE1669DBF.toInt(), 0xD645749D.toInt(), 0xDD344E78.toInt())
        else intArrayOf(0xCF5385A5.toInt(), 0xBD3D6589.toInt(), 0xD3324A70.toInt())).apply {
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), 0x76BCE8FF)
    }
    fun button(title: String, prominent: Boolean = false, action: () -> Unit) = label(title, if (prominent) 28f else 16f).apply {
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(5), dp(16), dp(5))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = RippleDrawable(ColorStateList.valueOf(0x55D9F8FF), glass(prominent), null)
        elevation = dp(1).toFloat()
        isFocusable = true
        setOnClickListener { action() }
    }
    fun pauseButton(action: () -> Unit): View = object : View(context) {
        private val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1FAFF.toInt() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val x = width / 2f
            val y = height / 2f
            val radius = dp(1).toFloat()
            canvas.drawRoundRect(x - dp(7), y - dp(9), x - dp(2), y + dp(9), radius, radius, bars)
            canvas.drawRoundRect(x + dp(2), y - dp(9), x + dp(7), y + dp(9), radius, radius, bars)
        }
    }.apply {
        contentDescription = "暂停"
        background = RippleDrawable(ColorStateList.valueOf(0x55D9F8FF), glass(), null)
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun homeCard(title: String, rank: Int, action: () -> Unit) =
        button(title, action = action).apply {
            textSize = listOf(28f, 23f, 16f)[rank]
            val surface = GradientDrawable(GradientDrawable.Orientation.TL_BR, when (rank) {
                0 -> intArrayOf(0x985B9AB7.toInt(), 0xAB355C7E.toInt(), 0xCB283E62.toInt())
                1 -> intArrayOf(0x805989AB.toInt(), 0xA7335579.toInt(), 0xB8294164.toInt())
                else -> intArrayOf(0x695382A2, 0x91325477.toInt(), 0xAD293F60.toInt())
            }).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x77D1F1FF)
            }
            background = RippleDrawable(ColorStateList.valueOf(0x39DDF7FF), surface, null)
            elevation = dp(if (rank == 0) 2 else 1).toFloat()
            setPadding(dp(if (rank == 2) 12 else 24), 0, dp(12), 0)
            gravity = if (rank == 2) Gravity.CENTER else Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelative(HomeIcon(rank).apply { setBounds(0, 0, dp(19), dp(19)) }, null, null, null)
        }

    private class HomeIcon(private val rank: Int) : Drawable() {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF0F9FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val shape = Path().apply {
            when (rank) {
                0 -> { moveTo(6f, 3f); lineTo(19f, 10f); lineTo(6f, 17f); close() }
                1 -> { moveTo(2f, 15f); lineTo(7f, 10f); lineTo(11f, 13f); lineTo(18f, 5f); moveTo(13f, 5f); lineTo(18f, 5f); lineTo(18f, 10f) }
                else -> { moveTo(2f, 5f); lineTo(18f, 5f); moveTo(2f, 10f); lineTo(18f, 10f); moveTo(2f, 15f); lineTo(18f, 15f) }
            }
        }
        override fun draw(canvas: Canvas) {
            val state = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 20f, bounds.height() / 20f)
            canvas.drawPath(shape, ink)
            if (rank == 2) for (i in 0..2) {
                val x = if (i == 1) 12f else 6f
                val y = 5f + i * 5f
                canvas.drawLine(x, y - 1.7f, x, y + 1.7f, ink)
            }
            canvas.restoreToCount(state)
        }
        override fun setAlpha(alpha: Int) { ink.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { ink.colorFilter = filter }
        @Suppress("DEPRECATION")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    fun styleDialog(dialog: android.app.AlertDialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            decorView.background = glass(active = true)
            decorView.clipToOutline = true
            setDimAmount(.28f)
        }
    }

    /** Scoped to settings: leave the existing game and practice dialogs untouched. */
    fun styleSettingsDialog(dialog: android.app.AlertDialog) {
        styleDialog(dialog)
        for (which in intArrayOf(-1, -2, -3)) {
            dialog.getButton(which)?.apply {
                setTextColor(0xFFEAF8FF.toInt())
                minHeight = dp(48)
                isAllCaps = false
                background = RippleDrawable(ColorStateList.valueOf(0x38D9F8FF),
                    glass(which == -1), null)
            }
        }
    }

    fun showSettingsDialog(builder: android.app.AlertDialog.Builder): android.app.AlertDialog =
        builder.show().also(::styleSettingsDialog)
    fun page(body: View): FrameLayout = FrameLayout(context).apply {
        addView(ImageView(context).apply {
            setImageBitmap(world)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (android.os.Build.VERSION.SDK_INT >= 31)
                setRenderEffect(android.graphics.RenderEffect.createBlurEffect(3f, 3f, android.graphics.Shader.TileMode.CLAMP))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x55333C72, 0x5A28465D, 0x94313D6E.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        addView(body, FrameLayout.LayoutParams(-1, -1))
        body.setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                val cutout = insets.displayCutout
                body.setPadding(dp(18) + (cutout?.safeInsetLeft ?: 0), dp(10) + (cutout?.safeInsetTop ?: 0),
                    dp(18) + (cutout?.safeInsetRight ?: 0), dp(10) + (cutout?.safeInsetBottom ?: 0))
            } else {
                body.setPadding(dp(18), dp(10), dp(18), dp(10))
            }
            insets
        }
    }
    fun home(play: () -> Unit, practice: () -> Unit, settings: () -> Unit): View = page(column().apply {
        addView(label("龙娘の乐器", 27f).apply { typeface = Typeface.DEFAULT_BOLD })
        addView(row().apply {
            addView(ImageView(context).apply {
                setImageBitmap(dragon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "龙娘"
            }, LinearLayout.LayoutParams(0, -1, .47f))
            addView(column().apply {
                addView(homeCard("游玩", 0, play),
                    LinearLayout.LayoutParams(-1, 0, .57f))
                addView(row().apply {
                    addView(homeCard("练习", 1, practice),
                        LinearLayout.LayoutParams(0, -1, .68f))
                    addView(homeCard("设置", 2, settings),
                        LinearLayout.LayoutParams(0, -1, .32f).apply { leftMargin = dp(10) })
                }, LinearLayout.LayoutParams(-1, 0, .43f).apply { topMargin = dp(10) })
            }, LinearLayout.LayoutParams(0, -1, .53f))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
    })

    fun cover(file: java.io.File?): Bitmap {
        if (file == null) return world
        return covers.get(file.path) ?: run {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 384) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { covers.put(file.path, it) } ?: world
        }
    }
}

internal object LibraryFilter {
    val categories = listOf("Expert", "Master", "Hard", "Special")
    fun category(label: String): String {
        val name = label.substringBefore(" Lv.").trim()
        return categories.take(3).firstOrNull { it.equals(name, true) } ?: "Special"
    }
    fun matches(item: SongDifficulty, category: String, query: String) =
        category(item.label) == category && (listOf(item.title, item.label, item.chart.nameWithoutExtension) + item.searchNames)
            .any { it.contains(query.trim(), ignoreCase = true) }
}
