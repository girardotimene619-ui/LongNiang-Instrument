package com.longniang.instrument

/** Monotonic nanoseconds are System.nanoTime's domain; chart/media units are microseconds. */
enum class ClockState { READY, RUNNING, PAUSED, INVALID }
enum class ClockSource { TEST, AUDIO_PRESENTATION }
data class ClockToken(val generation: Long, val revision: Long)
data class ClockSnapshot(val chartTimeUs: Long, val monotonicNs: Long, val token: ClockToken,
                         val state: ClockState, val source: ClockSource, val reason: String?) {
    val valid: Boolean get() = state != ClockState.INVALID
    val canJudge: Boolean get() = state == ClockState.RUNNING
}
data class TimedInput(val chartTimeUs: Long, val token: ClockToken)
interface GameClock {
    fun snapshot(nowNs: Long): ClockSnapshot
    fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput?
}
object AudioOffset {
    fun audioUs(chartUs: Long, offsetUs: Long) = Math.addExact(chartUs, offsetUs)
    fun chartUs(audioUs: Long, offsetUs: Long) = Math.subtractExact(audioUs, offsetUs)
}

// Caller serializes controls, queries and input routing under the existing session lock.
open class AnchoredClock(private val source: ClockSource) : GameClock {
    private var positionUs = 0L
    private var anchorNs = 0L
    private var lastNowNs = Long.MIN_VALUE
    private var acceptAfterNs = Long.MAX_VALUE
    private var validUntilNs = Long.MAX_VALUE
    private var generation = 0L
    private var revision = 0L
    private var state = ClockState.READY
    private var reason: String? = null
    val running: Boolean get() = state == ClockState.RUNNING
    private fun token() = ClockToken(generation, revision)
    private fun position(nowNs: Long) = if (state == ClockState.RUNNING)
        Math.addExact(positionUs, Math.subtractExact(nowNs, anchorNs) / 1000) else positionUs
    override fun snapshot(nowNs: Long): ClockSnapshot {
        if (nowNs < lastNowNs) {
            positionUs = position(lastNowNs); state = ClockState.INVALID
            reason = "MONOTONIC_TIME_REVERSED"; revision++; acceptAfterNs = Long.MAX_VALUE
        }
        if (state == ClockState.RUNNING && nowNs > validUntilNs) {
            positionUs = position(validUntilNs); state = ClockState.INVALID
            reason = "PRESENTATION_ANCHOR_EXPIRED"; revision++; acceptAfterNs = Long.MAX_VALUE
        }
        lastNowNs = maxOf(nowNs, lastNowNs)
        return ClockSnapshot(position(lastNowNs), lastNowNs, token(), state, source, reason)
    }
    fun resetAt(nowNs: Long, generation: Long, chartUs: Long = 0) {
        require(nowNs >= lastNowNs) { "Reset cannot reverse monotonic time" }
        require(generation > this.generation) { "Reset must start a new generation" }
        this.generation = generation; revision++; positionUs = chartUs; anchorNs = nowNs
        lastNowNs = nowNs; reason = null; validUntilNs = Long.MAX_VALUE
        state = if (source == ClockSource.TEST) ClockState.RUNNING else ClockState.READY
        acceptAfterNs = nowNs
    }
    fun pauseAt(nowNs: Long) {
        val s = snapshot(nowNs)
        if (state == ClockState.RUNNING) {
            positionUs = s.chartTimeUs; state = ClockState.PAUSED; revision++
            acceptAfterNs = Long.MAX_VALUE
        }
    }
    fun resumeAt(nowNs: Long) {
        snapshot(nowNs)
        if (state != ClockState.PAUSED) return // INVALID never silently resumes/falls back.
        anchorNs = nowNs; revision++; acceptAfterNs = nowNs
        state = if (source == ClockSource.TEST) ClockState.RUNNING else ClockState.READY
    }
    fun invalidateAt(nowNs: Long, why: String) {
        positionUs = snapshot(nowNs).chartTimeUs; state = ClockState.INVALID
        reason = why; revision++; acceptAfterNs = Long.MAX_VALUE
    }
    /** Resume/recovery only. Incoming position at receipt must equal frozen chart time exactly.
     * No guessed tolerance, snapping, offset correction, or live-anchor replacement is allowed. */
    protected fun anchorPresentedAt(chartUsAtReceipt: Long, nowNs: Long, expected: ClockToken, validUntilNs: Long): Boolean {
        val s = snapshot(nowNs)
        if (s.token != expected || source != ClockSource.AUDIO_PRESENTATION) return false
        if (state == ClockState.RUNNING) return false // live discipline belongs to M2-2/3
        if (chartUsAtReceipt != positionUs) {
            invalidateAt(nowNs, "PRESENTATION_DISCONTINUITY"); return false
        }
        anchorNs = nowNs; state = ClockState.RUNNING; reason = null
        this.validUntilNs = validUntilNs
        revision++; acceptAfterNs = nowNs
        return true
    }
    override fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput? {
        val s = snapshot(nowNs)
        if (!s.canJudge || token != s.token || sampleNs < acceptAfterNs || sampleNs > nowNs) return null
        return TimedInput(position(sampleNs), s.token)
    }
}

class TestClock : AnchoredClock(ClockSource.TEST) {
    // M1 compatibility helpers; new consumers use snapshot/inputAt in monotonic nanoseconds.
    private var legacyGeneration = 0L
    fun timeUs(uptimeMs: Long) = snapshot(uptimeMs * 1_000_000).chartTimeUs
    fun reset(uptimeMs: Long) { resetAt(uptimeMs * 1_000_000, ++legacyGeneration) }
    fun pause(uptimeMs: Long) { pauseAt(uptimeMs * 1_000_000) }
    fun resume(uptimeMs: Long) { resumeAt(uptimeMs * 1_000_000) }
}

/** A presentation-time bridge only: no AudioTrack, buffer, writer, PCM or playback implementation.
 * M2-2 must supply verified PRESENTED media position, never submitted/written frame progress. */
data class PresentedAudioAnchor(val mediaTimeUs: Long, val monotonicNs: Long, val token: ClockToken)
class AudioPresentationClock(val maxAnchorAgeNs: Long, val audioOffsetUs: Long = 0) : AnchoredClock(ClockSource.AUDIO_PRESENTATION) {
    init { require(maxAnchorAgeNs > 0) }
    fun acceptPresentation(anchor: PresentedAudioAnchor, nowNs: Long): Boolean {
        if (anchor.monotonicNs > nowNs || nowNs - anchor.monotonicNs > maxAnchorAgeNs) return false
        val projectedAudioUs = Math.addExact(anchor.mediaTimeUs, (nowNs - anchor.monotonicNs) / 1000)
        return anchorPresentedAt(AudioOffset.chartUs(projectedAudioUs, audioOffsetUs), nowNs, anchor.token, Math.addExact(anchor.monotonicNs, maxAnchorAgeNs))
    }
}

/** Conservative conversion: uptime sample is bracketed with nanoTime reads.
 * Millisecond fallback has <=1ms quantization; lower bound prevents pre-epoch events entering it.
 * Re-capture after pause/resume/reset; never reuse across sleep or clock invalidation. */
data class InputTimeBridge(val earliestOffsetNs: Long, val uncertaintyNs: Long) {
    fun toMonotonicNs(uptimeEventNs: Long) = Math.addExact(uptimeEventNs, earliestOffsetNs)
    companion object {
        fun capture(beforeNs: Long, uptimeMs: Long, afterNs: Long): InputTimeBridge {
            require(afterNs >= beforeNs)
            return InputTimeBridge(Math.subtractExact(beforeNs, Math.multiplyExact(uptimeMs + 1, 1_000_000)), Math.addExact(afterNs - beforeNs, 1_000_000))
        }
    }
}



/** Validate again at delivery, not just at capture. Caller uses the same session lock as controls. */
object SessionInput {
    fun down(session: TapSession, clock: GameClock, input: TimedInput, nowNs: Long,
             pointer: Int, lane: Int): Boolean {
        val current = clock.snapshot(nowNs)
        if (!current.canJudge || current.token != input.token || session.generation != input.token.generation) return false
        return session.down(pointer, lane, input.chartTimeUs, input.token.generation)
    }
}
