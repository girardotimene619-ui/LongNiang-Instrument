package com.longniang.instrument

/** Centisecond editing is sample-exact at the existing 48 kHz output rate. */
internal data class PracticeSegment(val a: Int, val b: Int) {
    val startUs get() = a * 10_000L
    val endUs get() = b * 10_000L
    fun valid(max: Int) = a >= 0 && b <= max && b - a >= 50
    fun cutsStart(chart: MixedChart) = chart.paths.any { startUs > it.start && startUs <= it.end }
    fun cutsEnd(chart: MixedChart) = chart.paths.any { endUs > it.start && endUs <= it.end }
    fun hasPlayableEvents(chart: MixedChart) = chart.actions.any {
        it.at in startUs until endUs && (it.path == null || it.path.start >= startUs && it.path.end < endUs)
    }
    // Practice only judges complete long notes inside [A,B). A clipped chain
    // has no head, ticks, tail, or Combo in this session.
    fun chartForPlay(chart: MixedChart): MixedChart {
        val paths = chart.paths.filter { it.start >= startUs && it.end < endUs }
        val ids = paths.mapTo(hashSetOf()) { it.id }
        val actions = chart.actions.filter { it.at in startUs until endUs && (it.path == null || it.path.id in ids) }
        return MixedChart(chart.columns, actions, paths, chart.diagnostics, chart.rules, chart.comboPlan, chart.guides)
    }
}

internal class SegmentPcmSource(private val source: PcmSource, segment: PracticeSegment) : PcmSource {
    private val offset = segment.a * (PcmSignal.sampleRate / 100L)
    override val totalFrames = (segment.b - segment.a) * (PcmSignal.sampleRate / 100L)
    init { require(offset >= 0 && totalFrames > 0 && offset + totalFrames <= source.totalFrames) }
    override fun fill(output: ShortArray, firstFrame: Long, frames: Int) {
        require(firstFrame >= 0 && frames >= 0 && firstFrame <= totalFrames - frames)
        source.fill(output, offset + firstFrame, frames)
    }
}

/** Translate the finite source's relative media position into original chart coordinates. */
internal class SegmentGameClock(private val clock: GameClock, private val segment: PracticeSegment) : GameClock {
    override fun snapshot(nowNs: Long): ClockSnapshot {
        val snapshot = clock.snapshot(nowNs)
        val duration = segment.endUs - segment.startUs
        return snapshot.copy(chartTimeUs = segment.startUs + snapshot.chartTimeUs.coerceIn(0, duration),
            state = if (snapshot.canJudge && snapshot.chartTimeUs >= duration) ClockState.PAUSED else snapshot.state)
    }
    override fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput? {
        if (!snapshot(nowNs).canJudge) return null
        val input = clock.inputAt(sampleNs, nowNs, token) ?: return null
        val at = segment.startUs + input.chartTimeUs
        return input.copy(chartTimeUs = at).takeIf { at in segment.startUs until segment.endUs }
    }
}
