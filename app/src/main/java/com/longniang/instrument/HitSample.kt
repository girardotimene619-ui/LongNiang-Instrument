package com.longniang.instrument

/** A cue may share a recording without sharing its volume or trigger policy. */
internal enum class HitSample { TAP, FLICK, TRACE, LONG }

internal object HitSampleMix {
    const val LONG_EDGE_GAIN = .50f
    const val CRITICAL_GAIN = 1.10f
    const val SUSTAIN_GAIN = .72f
    const val LOOP_START = 7_560
    const val LOOP_END = 84_708
    const val CROSSFADE_FRAMES = 1_920
    const val STOP_FRAMES = 720

    fun sample(cue: HitCue): HitSample? = when (cue) {
        HitCue.TAP, HitCue.CRITICAL, HitCue.HEAD, HitCue.CRITICAL_HEAD, HitCue.TAIL, HitCue.CRITICAL_TAIL -> HitSample.TAP
        HitCue.FLICK, HitCue.CRITICAL_FLICK, HitCue.FLICK_TAIL -> HitSample.FLICK
        HitCue.TRACE, HitCue.CRITICAL_TRACE -> HitSample.TRACE
        HitCue.TICK, HitCue.CRITICAL_TICK -> null
    }

    fun cueGain(cue: HitCue): Float = when (cue) {
        HitCue.HEAD, HitCue.CRITICAL_HEAD, HitCue.TAIL, HitCue.CRITICAL_TAIL -> LONG_EDGE_GAIN
        HitCue.CRITICAL, HitCue.CRITICAL_FLICK, HitCue.CRITICAL_TRACE -> CRITICAL_GAIN
        else -> 1f
    }
}

/** Audio-only observer of current contact. Both adapters already expose the same contact contract. */
internal class SustainFeedback {
    private val sounding = hashSetOf<String>()

    fun sync(playback: MixedPlayback, at: Long, enabled: Boolean, change: (String, Boolean) -> Unit) {
        if (!enabled) {
            clear(change)
            return
        }
        for (index in playback.paths.indices) {
            val path = playback.paths[index]
            val active = at >= path.start && at < path.end && playback.hasContact(path.id)
            if (active) {
                if (sounding.add(path.id)) change(path.id, true)
            } else if (sounding.remove(path.id)) change(path.id, false)
        }
    }

    fun clear(change: (String, Boolean) -> Unit) {
        for (id in sounding) change(id, false)
        sounding.clear()
    }

    fun reset() = sounding.clear()
}
