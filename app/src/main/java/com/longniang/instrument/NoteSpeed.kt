package com.longniang.instrument

import android.content.SharedPreferences
import kotlin.math.pow
import kotlin.math.roundToLong

internal data class NoteSpeed(val tenths: Int) {
    init { require(tenths in MIN..MAX) }

    val label get() = "${tenths / 10}.${tenths % 10}"

    // Time to cross from the spawn line to the judgment line. This is visual only.
    val travelUs = if (tenths <= BASELINE) 2_000_000L - (tenths - MIN) * 14_000L
        else (600_000 * .4.pow((tenths - BASELINE).toDouble() / (MAX - BASELINE))).roundToLong()

    companion object {
        const val MIN = 10
        const val MAX = 200
        private const val BASELINE = 110
        val DEFAULT = NoteSpeed(BASELINE)
        val PRESETS = listOf(80, 100, 110, 120, 130, 150, 180, 200).map(::NoteSpeed)
    }
}

internal class NoteSpeedStore(private val preferences: SharedPreferences) {
    fun load(): NoteSpeed {
        val tenths = preferences.getInt(KEY, NoteSpeed.DEFAULT.tenths)
        return if (tenths in NoteSpeed.MIN..NoteSpeed.MAX) NoteSpeed(tenths) else NoteSpeed.DEFAULT
    }

    fun save(speed: NoteSpeed) {
        preferences.edit().putInt(KEY, speed.tenths).apply()
    }

    private companion object { const val KEY = "note_speed_tenths" }
}

