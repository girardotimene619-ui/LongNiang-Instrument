package com.longniang.instrument

internal class PcmLoop(val startFrame: Long, val endFrame: Long, val rate: Int, totalFrames: Long) {
    init { require(rate > 0 && startFrame >= 0 && endFrame > startFrame && endFrame <= totalFrames) }
    val lengthFrames = endFrame - startFrame
    val startUs = SampleTime.us(startFrame, rate)
    val endUs = SampleTime.us(endFrame, rate)
    private val periodUs = SampleTime.us(lengthFrames, rate)
    init {
        require(periodUs > 0 && lengthFrames * 1_000_000 % rate == 0L)
        require(startFrame * 1_000_000 % rate == 0L)
    }

    fun sourceFrame(outputFrame: Long) = startFrame + outputFrame % lengthFrames
    fun chunkFrames(outputFrame: Long, capacity: Int) =
        minOf(capacity.toLong(), endFrame - sourceFrame(outputFrame)).toInt()
    fun iteration(outputUs: Long) = outputUs.coerceAtLeast(0) / periodUs
    fun chartTime(outputUs: Long) = startUs + outputUs.coerceAtLeast(0) % periodUs

    companion object {
        val practice = PcmLoop(96_000, 288_000, PcmSignal.sampleRate, PcmSignal.totalFrames.toLong())
    }
}

/** Keep observer time continuous. Only the game-facing projection wraps. */
internal class LoopGameClock(private val reading: AudioClockReading, private val loop: PcmLoop) : GameClock {
    private fun project(s: ClockSnapshot): ClockSnapshot {
        val outputUs = AudioOffset.audioUs(s.chartTimeUs, reading.offsetUs)
        return s.copy(
            chartTimeUs = AudioOffset.chartUs(loop.chartTime(outputUs), reading.offsetUs),
            token = s.token.copy(generation = s.token.generation + loop.iteration(outputUs))
        )
    }

    override fun snapshot(nowNs: Long) = project(reading.snapshot(nowNs))

    override fun inputAt(sampleNs: Long, nowNs: Long, token: ClockToken): TimedInput? {
        val current = snapshot(nowNs)
        if (!current.canJudge || current.token != token) return null
        val raw = reading.inputAt(sampleNs, nowNs, reading.anchor.token) ?: return null
        val sample = project(current.copy(chartTimeUs = raw.chartTimeUs, token = raw.token))
        if (sample.token != token) return null
        return TimedInput(sample.chartTimeUs, token)
    }
}
