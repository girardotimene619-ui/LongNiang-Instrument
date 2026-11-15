package com.longniang.instrument

/** Immutable worker publication. The consumer never calls the worker-owned mutable observer. */
data class AudioClockReading(
    val anchor: ClockSnapshot,
    val acceptAfterNs: Long,
    val expiresNs: Long,
    val phase: ObservationPhase,
    val playbackState: String,
    val offsetUs: Long = 0,
    val invalidations: Long = 0,
    val establishments: Long = 0,
    private val inputBaseNs: Long = anchor.monotonicNs,
    private val inputBaseChartUs: Long = anchor.chartTimeUs
) : GameClock {
    override fun snapshot(nowNs: Long): ClockSnapshot {
        if (!anchor.canJudge) return anchor.copy(monotonicNs = nowNs)
        if (nowNs < anchor.monotonicNs) return anchor.copy(state = ClockState.INVALID, reason = "PUBLICATION_FROM_FUTURE")
        val end = minOf(nowNs, expiresNs)
        val chart = anchor.chartTimeUs + (end - anchor.monotonicNs).coerceAtLeast(0) / 1000
        return anchor.copy(chartTimeUs = chart, monotonicNs = nowNs,
            state = if (nowNs <= expiresNs) ClockState.RUNNING else ClockState.INVALID,
            reason = if (nowNs <= expiresNs) null else "PRESENTATION_ANCHOR_EXPIRED")
    }
    override fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput? {
        val s = snapshot(nowNs)
        if (!s.canJudge || token != s.token || sampleNs < acceptAfterNs || sampleNs > nowNs) return null
        // The display snapshot is rounded at publication. Input is rounded once from the epoch anchor.
        return TimedInput(inputBaseChartUs + Math.floorDiv(sampleNs - inputBaseNs, 1000), token)
    }
    fun frozen(nowNs: Long, reason: String) = copy(
        anchor = snapshot(nowNs).copy(state = ClockState.INVALID, reason = reason),
        acceptAfterNs = Long.MAX_VALUE, playbackState = reason)

    companion object {
        fun idle(generation: Long) = AudioClockReading(
            ClockSnapshot(0, 0, ClockToken(generation, 0), ClockState.INVALID,
                ClockSource.AUDIO_PRESENTATION, "NOT_STARTED"), Long.MAX_VALUE, 0,
            ObservationPhase.FAILED, "STOPPED")
    }
}

/** Experimental dispatch bound, not an enlarged hit window. Final MISS cannot be rewritten. */
object AudioTapInput {
    const val maxDelayNs = 100_000_000L
    const val graceUs = maxDelayNs / 1000
    fun down(session: TapSession, clock: GameClock, sampleNs: Long, receivedNs: Long,
             streamStartNs: Long, token: ClockToken, pointer: Int, lane: Int): Boolean {
        val current = clock.snapshot(receivedNs)
        if (!current.canJudge || current.token != token || session.generation != token.generation ||
            sampleNs > receivedNs || receivedNs - sampleNs > maxDelayNs) return false
        if (clock.inputAt(streamStartNs, receivedNs, token) == null) return false
        val input = clock.inputAt(sampleNs, receivedNs, token) ?: return false
        session.advance(current.chartTimeUs - graceUs)
        return session.down(pointer, lane, input.chartTimeUs, token.generation, allowMissCorrection = false)
    }
    fun advance(session: TapSession, snapshot: ClockSnapshot) {
        if ((snapshot.canJudge || (snapshot.valid && snapshot.reason == "ENDED")) && snapshot.token.generation == session.generation)
            session.advance(snapshot.chartTimeUs - graceUs)
    }
}

object AudioMockChart {
    // Marker onset, not the sinusoid's peak: second * 48000. First two seconds are lead-in.
    val markerFrames = (2..28).map { it * PcmSignal.sampleRate.toLong() }
    val notes = markerFrames.flatMapIndexed { index, frame ->
        val time = SampleTime.us(frame, PcmSignal.sampleRate)
        if (index % 4 == 2) listOf(Tap("pcm-$index-L", time, 1), Tap("pcm-$index-R", time, 2))
        else listOf(Tap("pcm-$index", time, index % 4))
    }
}
