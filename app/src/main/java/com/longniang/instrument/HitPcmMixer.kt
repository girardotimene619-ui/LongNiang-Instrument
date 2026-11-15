package com.longniang.instrument

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal enum class HitSoundGroup(val label: String) {
    ALL("全部"), TAP("仅 Tap"), FLICK("仅 Flick（含方向尾）"), HEAD("仅长音 Head"),
    TICK("仅长音持续"), RELEASE("仅 Release"), TRACE("仅 Trace")
    ;

    fun includes(cue: HitCue): Boolean = this == ALL || this == when (cue) {
        HitCue.TAP, HitCue.CRITICAL -> TAP
        HitCue.TRACE, HitCue.CRITICAL_TRACE -> TRACE
        HitCue.FLICK, HitCue.CRITICAL_FLICK, HitCue.FLICK_TAIL -> FLICK
        HitCue.HEAD, HitCue.CRITICAL_HEAD -> HEAD
        HitCue.TICK, HitCue.CRITICAL_TICK -> TICK
        HitCue.TAIL, HitCue.CRITICAL_TAIL -> RELEASE
    }
}

/** Separate pools keep a burst of hits from stealing held sounds. Worker-owned. */
internal class HitPcmMixer(
    private val clips: Array<ShortArray>, capacity: Int = 12,
    private val clipGains: FloatArray = FloatArray(clips.size) { 1f },
    private val sustain: ShortArray? = null, private val sustainGain: Float = 1f
) {
    private class Voice {
        var clip: ShortArray? = null
        var position = 0f
        var rate = 1f
        var gain = 0f
        var priority = 0
        var order = 0L
    }
    private val voices = Array(capacity) { Voice() }
    private class LoopVoice {
        var id: String? = null
        var frame = 0
        var level = 0
        var held = false
    }
    private val loops = Array(48) { LoopVoice() }
    var sustainVolume = 0f
    private var order = 0L

    init {
        require(sustain == null || sustain.size >= HitSampleMix.LOOP_END)
    }

    fun setSustain(id: String, held: Boolean): Boolean {
        if (sustain == null) return false
        val existing = loops.firstOrNull { it.id == id }
        if (existing != null) {
            existing.held = held
            return true
        }
        if (!held) return true
        val voice = loops.firstOrNull { it.id == null } ?: return false
        voice.id = id
        voice.frame = 0
        voice.level = 0
        voice.held = true
        return true
    }

    fun trigger(cue: HitCue, gain: Float, rate: Float, priority: Int): Boolean {
        var candidate = voices.firstOrNull { it.clip == null }
        if (candidate == null) {
            for (voice in voices) {
                val best = candidate
                if (best == null || voice.priority < best.priority ||
                    (voice.priority == best.priority && voice.order < best.order)) candidate = voice
            }
            if (candidate == null || candidate.priority > priority) return false
        }
        candidate.clip = clips[cue.ordinal]
        candidate.position = 0f
        candidate.rate = rate
        candidate.gain = gain * clipGains[cue.ordinal]
        candidate.priority = priority
        candidate.order = ++order
        return true
    }

    fun renderStereo(output: ShortArray) {
        val long = sustain
        val loopGain = sustainVolume * sustainGain
        val fadeStart = HitSampleMix.LOOP_END - HitSampleMix.CROSSFADE_FRAMES
        var index = 0
        while (index < output.size) {
            var mix = 0f
            for (voice in voices) {
                val clip = voice.clip ?: continue
                val frame = voice.position.toInt()
                val a = clip[frame].toInt()
                val b = clip.getOrElse(frame + 1) { 0 }.toInt()
                mix += (a + (b - a) * (voice.position - frame)) * voice.gain
                voice.position += voice.rate
                if (voice.position >= clip.size) voice.clip = null
            }
            if (long != null) for (voice in loops) {
                if (voice.id == null) continue
                val frame = voice.frame
                val tail = long[frame].toFloat()
                val value = if (frame < fadeStart) tail else {
                    val overlap = frame - fadeStart
                    val weight = overlap.toFloat() / HitSampleMix.CROSSFADE_FRAMES
                    tail + (long[HitSampleMix.LOOP_START + overlap] - tail) * weight
                }
                voice.level = if (voice.held) minOf(voice.level + 1, HitSampleMix.STOP_FRAMES) else maxOf(voice.level - 1, 0)
                mix += value * loopGain * (voice.level.toFloat() / HitSampleMix.STOP_FRAMES)
                if (!voice.held && voice.level == 0) voice.id = null
                voice.frame = if (frame + 1 == HitSampleMix.LOOP_END)
                    HitSampleMix.LOOP_START + HitSampleMix.CROSSFADE_FRAMES else frame + 1
            }
            val sample = HitPcmLevels.limit(mix)
            output[index++] = sample
            output[index++] = sample
        }
    }

    fun clear() {
        for (voice in voices) voice.clip = null
        for (voice in loops) voice.id = null
    }
}

internal object HitPcmLevels {
    // PCM16 units. Keep single hits below the knee even at maximum user volume.
    private const val clipPeakLimit = 14_500f
    private const val knee = 21_000f
    const val ceiling = 29_490f

    fun sampleGain(sample: HitSample, clip: ShortArray): Float {
        val target = if (sample == HitSample.LONG) 1_400f else 2_500f
        val sampledPeakLimit = 12_000f
        val peak = clip.maxOf { abs(it.toInt()) }
        if (peak == 0) return 1f
        val rms = sqrt(clip.sumOf { it.toDouble() * it } / clip.size).toFloat()
        // Reserve peak headroom for the Critical multiplier.
        return minOf(target / rms, sampledPeakLimit / (peak * HitSampleMix.CRITICAL_GAIN))
    }

    /** Compute once after decode on the SFX worker. The source WAVs also serve SoundPool. */
    fun clipGains(clips: Array<ShortArray>): FloatArray = FloatArray(clips.size) { index ->
        val clip = clips[index]
        val peak = clip.maxOf { abs(it.toInt()) }
        if (peak == 0) 1f else {
            val rms = sqrt(clip.sumOf { it.toDouble() * it } / clip.size).toFloat()
            minOf(targetRms(HitCue.entries[index]) / rms, clipPeakLimit / peak)
        }
    }

    private fun targetRms(cue: HitCue): Float = when (cue) {
        HitCue.TAP -> 2_700f
        HitCue.CRITICAL -> 3_000f
        HitCue.FLICK -> 2_700f
        HitCue.CRITICAL_FLICK -> 3_100f
        HitCue.HEAD -> 2_450f
        HitCue.CRITICAL_HEAD -> 2_750f
        HitCue.TICK -> 1_800f
        HitCue.CRITICAL_TICK -> 2_250f
        HitCue.TAIL -> 2_650f
        HitCue.CRITICAL_TAIL -> 2_900f
        HitCue.FLICK_TAIL -> 2_800f
        HitCue.TRACE -> 2_350f
        HitCue.CRITICAL_TRACE -> 2_650f
    }

    /** Linear below the knee. Continuous slope at the knee and no look-ahead. */
    fun limit(sample: Float): Short {
        val magnitude = abs(sample)
        if (magnitude <= knee) return sample.roundToInt().toShort()
        val over = magnitude - knee
        val span = ceiling - knee
        val limited = knee + span * (over / (span + over))
        return (if (sample < 0f) -limited else limited).roundToInt().toShort()
    }
}

/** Reader for the bundled 48 kHz PCM16 mono WAV assets. Invoked on the SFX worker only. */
internal fun readHitWave(bytes: ByteArray): ShortArray {
    val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun fourCc(value: String) = ByteArray(4).also { input.get(it) }.contentEquals(value.toByteArray(Charsets.US_ASCII))
    require(input.remaining() >= 12 && fourCc("RIFF")) { "Invalid SFX WAV" }
    input.int
    require(fourCc("WAVE")) { "Invalid SFX WAVE header" }
    var format = false
    while (input.remaining() >= 8) {
        val id = ByteArray(4).also { input.get(it) }.toString(Charsets.US_ASCII)
        val size = input.int
        require(size >= 0 && size <= input.remaining()) { "Invalid SFX chunk size" }
        val end = input.position() + size
        when (id) {
            "fmt " -> {
                require(size >= 16 && input.short.toInt() == 1 && input.short.toInt() == 1 &&
                    input.int == PcmSignal.sampleRate) { "Expected mono PCM at 48 kHz" }
                input.int
                require(input.short.toInt() == 2 && input.short.toInt() == 16) { "Expected PCM16" }
                format = true
            }
            "data" -> {
                require(format && size > 0 && size % 2 == 0) { "Invalid SFX data" }
                return ShortArray(size / 2) { input.short }
            }
        }
        input.position(end + (size and 1))
    }
    error("Missing SFX data")
}
