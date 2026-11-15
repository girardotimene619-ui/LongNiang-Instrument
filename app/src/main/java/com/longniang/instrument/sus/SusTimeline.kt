package com.longniang.instrument.sus

data class TimedSusOccurrence(
    val occurrence: SusOccurrence, val exactBeat: String, val timeUs: Long, val exactTimeUs: String
)
class SusTimingReport internal constructor(
    val raw: RawSusDocument, val events: List<TimedSusOccurrence>,
    val diagnostics: List<SusDiagnostic>, val ticksPerBeat: Int?,
    internal val tempoSegments: List<TempoSegment> = emptyList()
)

internal data class TempoSegment(val beat: Rational, val time: Rational, val bpm: Rational)

object SusTimeline {
    fun analyze(bytes: ByteArray): SusTimingReport {
        val raw = SusReader.read(bytes)
        if (raw.diagnostics.any { it.error }) return SusTimingReport(raw, emptyList(), raw.diagnostics, null)
        return try {
            val normalized = SusNormalizer.normalize(raw)
            if (normalized.diagnostics.any { it.error })
                return SusTimingReport(raw, emptyList(), normalized.diagnostics, normalized.ticksPerBeat)
            var beat = Rational.of(0)
            var elapsed = Rational.of(0)
            var bpm = normalized.tempos.getValue(beat)
            val events = normalized.occurrences.map { entry ->
                elapsed += (entry.beat - beat) * Rational.of(60_000_000) / bpm
                beat = entry.beat
                normalized.tempos[beat]?.let { bpm = it }
                TimedSusOccurrence(entry.raw, beat.toString(), elapsed.micros(), elapsed.toString())
            }
            val segments = mutableListOf<TempoSegment>()
            for ((at, tempo) in normalized.tempos) {
                val previous = segments.lastOrNull()
                val time = if (previous == null) Rational.of(0)
                    else previous.time + (at - previous.beat) * Rational.of(60_000_000) / previous.bpm
                segments += TempoSegment(at, time, tempo)
            }
            SusTimingReport(raw, events, normalized.diagnostics, normalized.ticksPerBeat, segments)
        } catch (error: ArithmeticException) {
            SusTimingReport(raw, emptyList(), raw.diagnostics +
                SusDiagnostic(null, "TIMING_LIMIT", error.message.orEmpty(), true), null)
        }
    }
}
