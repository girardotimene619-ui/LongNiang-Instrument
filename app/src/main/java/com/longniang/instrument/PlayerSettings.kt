package com.longniang.instrument

import android.content.Context

internal const val MAX_HIT_VOLUME_PERCENT = 130

/** Uses the original private preference keys so existing installs retain their settings. */
internal class PlayerSettings(context: Context) {
    val preferences = context.getSharedPreferences("practice_settings", Context.MODE_PRIVATE)
    val noteSpeed = NoteSpeedStore(preferences)
    val musicGain get() = preferences.getInt("music_volume", 100).coerceIn(0, 100) / 100f
    val hitGain get() = if (preferences.getBoolean("hits_muted", false)) 0f else
        preferences.getInt("hits_volume", 65).coerceIn(0, MAX_HIT_VOLUME_PERCENT) / 100f
}
