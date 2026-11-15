package com.longniang.instrument

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView

class LaunchActivity : Activity() {
    private lateinit var artwork: ImageView
    private lateinit var animation: AnimationDrawable

    private val enterHome = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!isTaskRoot) {
            finish()
            return
        }
        artwork = ImageView(this).apply {
            setBackgroundResource(R.color.launch_background)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.launch_animation)
        }
        animation = artwork.drawable as AnimationDrawable
        setContentView(artwork)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::artwork.isInitialized) return
        // Start after attachment so the drawable can schedule its first frame.
        artwork.post(startAnimation)
    }

    private val startAnimation = Runnable {
        animation.selectDrawable(0)
        animation.start()
        val duration = (0 until animation.numberOfFrames).sumOf(animation::getDuration)
        artwork.postDelayed(enterHome, duration.toLong())
    }

    override fun onPause() {
        if (::artwork.isInitialized) {
            artwork.removeCallbacks(startAnimation)
            artwork.removeCallbacks(enterHome)
            animation.stop()
        }
        super.onPause()
    }
}
