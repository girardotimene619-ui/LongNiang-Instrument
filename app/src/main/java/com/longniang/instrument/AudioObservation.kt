package com.longniang.instrument

import kotlin.math.abs
import kotlin.math.sin

object PcmSignal {
    const val sampleRate = 48_000
    const val channels = 2
    const val durationSeconds = 30
    const val totalFrames = sampleRate * durationSeconds
    // 220Hz continuous tone (6% full scale), 1kHz 20ms markers every second (8%).
    // 10ms edge envelope avoids start/end clicks. Stereo channels are bit-identical.
    fun fill(output: ShortArray, firstFrame: Long, frames: Int) {
        require(firstFrame >= 0 && frames * channels <= output.size)
        for (i in 0 until frames) {
            val frame = firstFrame + i
            val edge = minOf(frame / 480.0, (totalFrames - 1 - frame) / 480.0, 1.0).coerceAtLeast(0.0)
            val within = frame % sampleRate
            val marker = if (within < 960) .08 * sin(Math.PI * within / 960) * sin(2 * Math.PI * 1000 * frame / sampleRate) else 0.0
            val value = ((.06 * sin(2 * Math.PI * 220 * frame / sampleRate) + marker) * edge * 32767).toInt().toShort()
            output[i * 2] = value; output[i * 2 + 1] = value
        }
    }
}
object SampleTime {
    fun us(frames: Long, rate: Int): Long {
        require(frames >= 0 && rate > 0)
        return Math.addExact(Math.multiplyExact(frames / rate, 1_000_000L), frames % rate * 1_000_000L / rate)
    }
}
data class ObservationPolicy(val maxAgeNs: Long = 30_000_000_000L, val residualUs: Long = 20_000L, val acquisitionTimeoutNs: Long = 5_000_000_000L) {
    init { require(maxAgeNs > 0 && residualUs >= 0 && acquisitionTimeoutNs > 0) }
}
data class AudioObservation(val frame: Long, val nanoTime: Long, val rate: Int, val token: ClockToken)

enum class ObservationPhase { STARTING, RUNNING, RECOVERING, PAUSED, FAILED }

/** Pure observation adapter. No PCM writes are accepted by this API. Controls/queries serialized. */
class AudioObservationClock(val rate: Int, val audioOffsetUs: Long = 0,
                            val policy: ObservationPolicy = ObservationPolicy()) : GameClock {
    init { require(rate > 0) }
    var phase = ObservationPhase.FAILED; private set
    var lastRejection: String? = null; private set
    var falseCount = 0L; private set
    var establishmentNs: Long? = null; private set
    var rawObservation: AudioObservation? = null; private set
    private var acquisitionStartNs = 0L
    var preTimeoutRejection: String? = null; private set
    private var timedOut = false
    private var recoveryFloor: Long? = null
    private var generation = 0L
    private var revision = 0L
    private var state = ClockState.INVALID
    private var reason: String? = "NOT_STARTED"
    private var audioUs = 0L
    private var baseNs = 0L
    private var acceptAfterNs = Long.MAX_VALUE
    var invalidations = 0L; private set
    var establishments = 0L; private set
    private var expiryNs = Long.MAX_VALUE
    private var lastNowNs = Long.MIN_VALUE
    private var previous: AudioObservation? = null
    private var wrappedFrames = 0L
    private var armed = false
    private var established = false
    private var continuity = false
    var lastFrame: Long? = null; private set
    var lastTimestampNs: Long? = null; private set
    var residualUs: Long? = null; private set
    private fun token() = ClockToken(generation, revision)
    private fun estimate(ns: Long) = audioUs + if (state == ClockState.RUNNING) (ns - baseNs) / 1000 else 0
    override fun snapshot(nowNs: Long): ClockSnapshot {
        if (nowNs < lastNowNs) fail(lastNowNs, "MONOTONIC_REVERSED")
        val now = maxOf(nowNs, lastNowNs)
        if (state == ClockState.RUNNING && now > expiryNs) fail(expiryNs, "TIMESTAMP_EXPIRED")
        if ((phase == ObservationPhase.STARTING || phase == ObservationPhase.RECOVERING) &&
            now - acquisitionStartNs > policy.acquisitionTimeoutNs) {
            if (!timedOut) preTimeoutRejection = lastRejection
            timedOut = true; state = ClockState.INVALID; reason = "ANCHOR_ACQUISITION_TIMEOUT"
        }
        lastNowNs = now
        return ClockSnapshot(AudioOffset.chartUs(estimate(now), audioOffsetUs), now, token(), state,
            ClockSource.AUDIO_PRESENTATION, reason)
    }
    fun start(nowNs: Long, generation: Long) {
        require(generation > this.generation)
        this.generation = generation; revision++; state = ClockState.READY; reason = "WAITING_TIMESTAMP"
        audioUs = 0; baseNs = nowNs; lastNowNs = nowNs; expiryNs = Long.MAX_VALUE
        armed = true; established = false; continuity = false; previous = null; wrappedFrames = 0
        lastFrame = null; lastTimestampNs = null; residualUs = null
        acceptAfterNs = Long.MAX_VALUE
        phase = ObservationPhase.STARTING; acquisitionStartNs = nowNs; timedOut = false; preTimeoutRejection = null
        recoveryFloor = null; falseCount = 0; establishmentNs = null; rawObservation = null; lastRejection = null
    }
    private fun fail(nowNs: Long, why: String) {
        audioUs = estimate(nowNs); baseNs = nowNs; state = ClockState.INVALID; reason = why
        revision++; armed = false; phase = ObservationPhase.FAILED; lastRejection = why
        acceptAfterNs = Long.MAX_VALUE; invalidations++
    }
    fun invalidate(nowNs: Long, why: String) { snapshot(nowNs); fail(maxOf(nowNs, lastNowNs), why) }
    fun finish(nowNs: Long, durationUs: Long) {
        val s = snapshot(nowNs)
        if (!s.canJudge) { invalidate(nowNs, "ENDED_WITHOUT_VALID_CLOCK"); return }
        audioUs = minOf(AudioOffset.audioUs(s.chartTimeUs, audioOffsetUs), durationUs)
        baseNs = nowNs; state = ClockState.PAUSED; phase = ObservationPhase.PAUSED
        reason = "ENDED"; revision++; acceptAfterNs = Long.MAX_VALUE
    }
    fun pause(nowNs: Long) {
        val s = snapshot(nowNs)
        if (phase == ObservationPhase.FAILED) return // A control must not re-arm a hard fault.
        if (state == ClockState.PAUSED) return
        audioUs = AudioOffset.audioUs(s.chartTimeUs, audioOffsetUs)
        state = ClockState.PAUSED; reason = "PAUSED"; revision++; armed = false; phase = ObservationPhase.PAUSED
        acceptAfterNs = Long.MAX_VALUE
    }
    fun resume(nowNs: Long) {
        snapshot(nowNs)
        if (state != ClockState.PAUSED) return
        baseNs = nowNs; state = ClockState.READY; reason = "WAITING_RESUME_TIMESTAMP"
        revision++; armed = true; continuity = established; previous = null
        phase = ObservationPhase.RECOVERING; acquisitionStartNs = nowNs; timedOut = false; preTimeoutRejection = null
        recoveryFloor = lastFrame; establishmentNs = null
    }
    private fun pending(why: String): Boolean {
        lastRejection = why
        reason = if (timedOut) "ANCHOR_ACQUISITION_TIMEOUT: $why" else why
        return false
    }
    fun unavailable(nowNs: Long) {
        snapshot(nowNs); falseCount++
        if (phase == ObservationPhase.STARTING || phase == ObservationPhase.RECOVERING) {
            previous = null; state = ClockState.INVALID; pending("TIMESTAMP_UNAVAILABLE"); return
        }
        if (state == ClockState.RUNNING) invalidate(nowNs, "TIMESTAMP_UNAVAILABLE")
    }
    fun observe(o: AudioObservation, nowNs: Long): Boolean {
        val s = snapshot(nowNs)
        // Do not overwrite this instance's raw diagnostic with a stale session's packet.
        if (o.token != s.token) { lastRejection = "STALE_TOKEN"; return false }
        rawObservation = o
        if (phase == ObservationPhase.FAILED || phase == ObservationPhase.PAUSED) return false
        val acquiring = phase == ObservationPhase.STARTING || phase == ObservationPhase.RECOVERING
        fun reject(why: String): Boolean { invalidate(nowNs, why); return false }
        if (o.rate != rate) return reject("SAMPLE_RATE_MISMATCH")
        if (o.frame < 0) return reject("INVALID_TIMESTAMP")
        if (acquiring && o.nanoTime < baseNs) return pending("WAITING_NEW_TIMESTAMP")
        if (o.nanoTime > nowNs) return pending("TIMESTAMP_COMMITTED_IN_FUTURE")
        if (nowNs - o.nanoTime > policy.maxAgeNs) {
            if (acquiring) { previous = null; state = ClockState.INVALID; return pending("TIMESTAMP_EXPIRED") }
            return reject("TIMESTAMP_EXPIRED")
        }
        val old = previous
        if (phase == ObservationPhase.RECOVERING && recoveryFloor != null &&
            o.frame + wrappedFrames < recoveryFloor!!) return reject("FRAME_REVERSED_ON_RESUME")
        var wrap = 0L
        if (old != null) {
            if (o.nanoTime < old.nanoTime) return reject("TIMESTAMP_REVERSED")
            if (o.frame == old.frame) {
                if (acquiring) {
                    previous = o // Sliding baseline: zero/stalled warmup cannot accumulate a false rate error.
                    return pending("WAITING_FRAME_ADVANCE")
                }
                if (o.nanoTime == old.nanoTime) return pending("DUPLICATE_TIMESTAMP")
            }
            if (o.nanoTime == old.nanoTime) return reject("FRAME_JUMP")
            if (o.frame < old.frame) {
                if (old.frame > 0xf0000000L && o.frame < 0x0fffffffL) wrap = 1L shl 32
                else return reject("FRAME_REVERSED")
            }
            val frameDelta = o.frame - old.frame + wrap
            if (abs(SampleTime.us(frameDelta, rate) - (o.nanoTime - old.nanoTime) / 1000) > policy.residualUs) {
                if (acquiring) { previous = o; return pending("UNSTABLE_CANDIDATE_RATE") }
                return reject("FRAME_TIME_JUMP")
            }
        }
        if (old == null) { previous = o; return pending("WAITING_ADVANCING_TIMESTAMP") }
        if (timedOut) { previous = o; return pending("LATE_CANDIDATES_REQUIRE_RESTART") }
        val candidateFrame = o.frame + wrappedFrames + wrap
        val observedUs = SampleTime.us(candidateFrame, rate) + (nowNs - o.nanoTime) / 1000
        val predicted = if (state == ClockState.RUNNING || continuity) audioUs + (nowNs - baseNs) / 1000 else observedUs
        residualUs = observedUs - predicted
        if (phase == ObservationPhase.RECOVERING && observedUs < audioUs)
            return pending("RECOVERY_BEFORE_FROZEN_POSITION")
        if (state == ClockState.RUNNING && abs(residualUs!!) > policy.residualUs) {

            return reject("PRESENTATION_DISCONTINUITY")
        }
        previous = o; wrappedFrames += wrap
        lastFrame = candidateFrame; lastTimestampNs = o.nanoTime
        if (!established) { audioUs = observedUs; baseNs = nowNs; established = true }
        else if (state != ClockState.RUNNING) { audioUs = observedUs; baseNs = nowNs }
        if (acquiring) {
            establishmentNs = nowNs - acquisitionStartNs
            acceptAfterNs = nowNs; establishments++
        }
        state = ClockState.RUNNING; phase = ObservationPhase.RUNNING; reason = null
        armed = false; continuity = false
        expiryNs = o.nanoTime + policy.maxAgeNs
        return true
    }
    override fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput? {
        val s = snapshot(nowNs)
        if (!s.canJudge || token != s.token || sampleNs < acceptAfterNs || sampleNs > nowNs) return null
        return TimedInput(AudioOffset.chartUs(audioUs, audioOffsetUs) + Math.floorDiv(sampleNs - baseNs, 1000), token)
    }
    fun reading(nowNs: Long, playbackState: String): AudioClockReading = AudioClockReading(
        snapshot(nowNs), acceptAfterNs, expiryNs, phase, playbackState, audioOffsetUs,
        invalidations, establishments, inputBaseNs = baseNs, inputBaseChartUs = AudioOffset.chartUs(audioUs, audioOffsetUs))
}





