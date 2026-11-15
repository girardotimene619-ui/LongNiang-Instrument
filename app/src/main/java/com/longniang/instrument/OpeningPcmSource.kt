package com.longniang.instrument

/** Present silence before frame zero of the song, using the same hardware clock for both. */
internal class OpeningPcmSource(
    private val song: PcmSource,
    val silentFrames: Long
) : PcmSource {
    val offsetUs = SampleTime.us(silentFrames, PcmSignal.sampleRate)
    override val totalFrames = Math.addExact(silentFrames, song.totalFrames)
    private val scratch = ShortArray(960 * 2)

    override fun fill(output: ShortArray, firstFrame: Long, frames: Int) {
        require(firstFrame >= 0 && frames >= 0 && frames <= output.size / 2 && firstFrame <= totalFrames - frames)
        val silence = (silentFrames - firstFrame).coerceIn(0, frames.toLong()).toInt()
        if (silence == 0) {
            song.fill(output, firstFrame - silentFrames, frames)
            return
        }
        output.fill(0, 0, silence * 2)
        val remaining = frames - silence
        if (remaining > 0) {
            song.fill(scratch, 0, remaining)
            scratch.copyInto(output, silence * 2, 0, remaining * 2)
        }
    }

    companion object {
        // Time for the usual timestamp acquisition, before the first note reaches the spawn line.
        private const val CLOCK_WARMUP_US = 500_000L

        fun visualLeadUs(playback: MixedPlayback?, speed: NoteSpeed, wholeSong: Boolean): Long {
            if (!wholeSong || playback == null || playback.startUs != 0L) return 0
            val first = playback.actions.firstOrNull { it.visible } ?: return 0
            return (speed.travelUs - first.at).coerceAtLeast(0)
        }

        fun wrap(song: PcmSource, visualLeadUs: Long): PcmSource {
            if (visualLeadUs == 0L) return song
            val samples = ((visualLeadUs + CLOCK_WARMUP_US) * PcmSignal.sampleRate + 999_999) / 1_000_000
            return OpeningPcmSource(song, samples)
        }

        fun visualTimeUs(chartUs: Long, visualLeadUs: Long) =
            if (visualLeadUs > 0) maxOf(chartUs, -visualLeadUs) else chartUs
    }
}

/** Keep the measured audio-clock timestamp, including the opening Tap's existing early window. */
internal fun openingTapInput(clock: GameClock, sampleNs: Long, nowNs: Long, token: ClockToken,
                             earlyUs: Long): TimedInput? =
    clock.inputAt(sampleNs, nowNs, token)?.takeIf { it.chartTimeUs >= -earlyUs }
