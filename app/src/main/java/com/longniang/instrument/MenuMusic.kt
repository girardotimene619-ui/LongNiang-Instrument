package com.longniang.instrument

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

internal class MenuMusic(private val context: Context) {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val audio = context.getSystemService(AudioManager::class.java)
    private var desired = false
    private var focused = false
    private var ready = false
    private var gain = 1f
    private var player: MediaPlayer? = null
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            focused = change == AudioManager.AUDIOFOCUS_GAIN
            update()
        }.setWillPauseWhenDucked(true).build()

    fun show(wanted: Boolean) {
        if (wanted == desired) return
        desired = wanted
        if (!wanted) {
            update()
            audio.abandonAudioFocusRequest(focus)
            focused = false
            return
        }
        focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (player == null) {
            val next = MediaPlayer()
            try {
                next.setAudioAttributes(attributes)
                context.assets.openFd("theme/menu.mp4").use { next.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                next.setOnPreparedListener {
                    if (player === it) {
                        ready = true
                        it.isLooping = true
                        android.util.Log.i("LongNiangMenu", "prepared loop=true durationMs=${it.duration}")
                        update()
                    }
                }
                next.setOnErrorListener { failed, what, extra ->
                    android.util.Log.w("LongNiangMenu", "Menu BGM error $what/$extra")
                    if (player === failed) { ready = false; player = null; failed.release() }
                    true
                }
                player = next
                next.prepareAsync()
            } catch (failure: Exception) {
                player = null
                next.release()
                android.util.Log.w("LongNiangMenu", "Menu BGM unavailable", failure)
            }
        }
        update()
    }

    fun volume(value: Float) { gain = value.coerceIn(0f, 1f); update() }
    private fun update() {
        val output = player?.takeIf { ready } ?: return
        output.setVolume(gain * .40f, gain * .40f)
        if (desired && focused) {
            if (!output.isPlaying) {
                output.start()
                android.util.Log.i("LongNiangMenu", "playing positionMs=${output.currentPosition}")
            }
        } else if (output.isPlaying) {
            output.pause()
            android.util.Log.i("LongNiangMenu", "paused positionMs=${output.currentPosition}")
        }
    }

    fun close() {
        show(false)
        val output = player
        player = null
        ready = false
        output?.release()
    }
}
