package com.longniang.instrument.sus

import java.math.BigInteger

internal class Rational private constructor(val n: BigInteger, val d: BigInteger) : Comparable<Rational> {
    operator fun plus(other: Rational) = of(n * other.d + other.n * d, d * other.d)
    operator fun minus(other: Rational) = of(n * other.d - other.n * d, d * other.d)
    operator fun times(other: Rational) = of(n * other.n, d * other.d)
    operator fun div(other: Rational) = of(n * other.d, d * other.n)
    override fun compareTo(other: Rational) = (n * other.d).compareTo(other.n * d)
    fun micros(): Long = ((n * BigInteger.valueOf(2) + d) / (d * BigInteger.valueOf(2))).longValueExact()
    override fun toString() = if (d == BigInteger.ONE) n.toString() else "$n/$d"

    companion object {
        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun parse(text: String): Rational =
            of(text.substringBefore('/').toBigInteger(), text.substringAfter('/', "1").toBigInteger())
        private fun of(n: BigInteger, d: BigInteger): Rational {
            require(d.signum() > 0)
            val gcd = n.gcd(d)
            val a = n / gcd
            val b = d / gcd
            if (a.bitLength() > 4096 || b.bitLength() > 4096) throw ArithmeticException("Rational size limit")
            return Rational(a, b)
        }
        fun decimal(text: String): Rational? {
            if (text.length > 32 || !text.matches(Regex("""[0-9]+(?:\.[0-9]+)?"""))) return null
            return of(text.replace(".", "").toBigInteger(),
                BigInteger.TEN.pow(text.substringAfter('.', "").length))
        }
    }
}

data class SusOccurrence(
    val source: SusRecord, val measure: Int, val channel: String,
    val index: Int, val divisions: Int, val token: String
)
internal data class PositionedOccurrence(val raw: SusOccurrence, val beat: Rational)
internal data class NormalizedSus(
    val occurrences: List<PositionedOccurrence>, val tempos: Map<Rational, Rational>,
    val diagnostics: List<SusDiagnostic>, val ticksPerBeat: Int?
)

internal object SusNormalizer {
    private val measureKey = Regex("""([0-9]{3})([A-Z0-9]{2,})""")
    private val bpmKey = Regex("""BPM([A-Z0-9]{2})""")
    private val metadata = setOf("TITLE", "ARTIST", "DESIGNER", "DIFFICULTY", "PLAYLEVEL",
        "SONGID", "WAVE", "WAVEOFFSET", "JACKET")

    fun normalize(raw: RawSusDocument): NormalizedSus {
        val diagnostics = raw.diagnostics.toMutableList()
        fun issue(record: SusRecord?, code: String, message: String, error: Boolean = true) {
            diagnostics += SusDiagnostic(record?.line, code, message, error)
        }
        val lengths = sortedMapOf<Int, Rational>()
        val bpms = mutableMapOf<String, Rational>()
        val occurrences = mutableListOf<SusOccurrence>()
        var ticks: Int? = null
        var uncertainTimeline = false
        for (record in raw.records) {
            val key = record.key ?: continue
            val value = record.value.orEmpty().trim()
            val bpm = bpmKey.matchEntire(key)
            val measure = measureKey.matchEntire(key)
            when {
                bpm != null -> {
                    val tempo = Rational.decimal(value)
                    val id = bpm.groupValues[1]
                    if (tempo == null || tempo.n.signum() <= 0) issue(record, "BAD_BPM", value)
                    else if (bpms[id]?.compareTo(tempo)?.let { it != 0 } == true)
                        issue(record, "CONFLICTING_BPM", id)
                    else bpms[id] = tempo
                }
                measure != null -> {
                    val m = measure.groupValues[1].toInt()
                    val channel = measure.groupValues[2]
                    if (channel == "02") {
                        val length = Rational.decimal(value)
                        if (length == null || length.n.signum() <= 0) issue(record, "BAD_MEASURE_LENGTH", value)
                        else if (lengths[m]?.compareTo(length)?.let { it != 0 } == true)
                            issue(record, "CONFLICTING_MEASURE_LENGTH", key)
                        else lengths[m] = length
                    } else {
                        if (value.isEmpty() || value.length % 2 != 0 || !value.matches(Regex("[A-Za-z0-9]+"))) {
                            issue(record, "BAD_PAYLOAD", key)
                            continue
                        }
                        if (channel == "03") uncertainTimeline = true
                        if (channel != "08" && !channel.matches(Regex("[15][A-Z0-9]|[39][A-Z0-9]{2}")))
                            issue(record, "UNVERIFIED_CHANNEL", channel, false)
                        for (i in 0 until value.length / 2) {
                            val token = value.substring(i * 2, i * 2 + 2)
                            if (token == "00") continue
                            if (occurrences.size >= 250_000)
                                return NormalizedSus(emptyList(), emptyMap(), diagnostics +
                                    SusDiagnostic(record.line, "RESOURCE_LIMIT", "Too many occurrences", true), ticks)
                            occurrences += SusOccurrence(record, m, channel, i, value.length / 2, token)
                        }
                    }
                }
                key == "REQUEST" && value.removeSurrounding("\"").startsWith("ticks_per_beat") -> {
                    val parsed = Regex("""ticks_per_beat\s+(\d+)""").matchEntire(value.removeSurrounding("\""))
                        ?.groupValues?.get(1)?.toIntOrNull()
                    if (parsed == null || parsed <= 0 || (ticks != null && ticks != parsed))
                        issue(record, "BAD_TICKS_PER_BEAT", value)
                    else ticks = parsed
                }
                key == "MEASUREBS" || key == "BPM" -> {
                    uncertainTimeline = true
                    issue(record, "UNSUPPORTED_TIMING_DIRECTIVE", key)
                }
                key == "VOLUME" && value == "\"\"" -> Unit
                key !in metadata -> issue(record, "UNVERIFIED_DIRECTIVE", key, false)
            }
        }
        if (uncertainTimeline && diagnostics.none { it.code == "UNSUPPORTED_TIMING_DIRECTIVE" })
            issue(null, "UNSUPPORTED_TIMING_DIRECTIVE", "Inline BPM channel 03")
        if (lengths[0] == null) issue(null, "MISSING_INITIAL_MEASURE_LENGTH", "Expected #00002")
        if (diagnostics.any { it.error }) return NormalizedSus(emptyList(), emptyMap(), diagnostics, ticks)
        val starts = mutableListOf<Rational>()
        val effectiveLengths = mutableListOf<Rational>()
        var beat = Rational.of(0)
        var length = lengths.getValue(0)
        for (m in 0..(occurrences.maxOfOrNull { it.measure } ?: 0)) {
            lengths[m]?.let { length = it }
            starts += beat
            effectiveLengths += length
            beat += length
        }
        val positioned = occurrences.map {
            PositionedOccurrence(it, starts[it.measure] + effectiveLengths[it.measure] *
                Rational.of(it.index.toLong(), it.divisions.toLong()))
        }.sortedWith(compareBy({ it.beat }, { it.raw.source.line }, { it.raw.index }))
        val tempos = sortedMapOf<Rational, Rational>()
        for (entry in positioned.filter { it.raw.channel == "08" }) {
            val tempo = bpms[entry.raw.token.uppercase(java.util.Locale.ROOT)]
            if (tempo == null) issue(entry.raw.source, "MISSING_BPM_REFERENCE", entry.raw.token)
            else if (tempos[entry.beat]?.compareTo(tempo)?.let { it != 0 } == true)
                issue(entry.raw.source, "CONFLICTING_TEMPO", entry.beat.toString())
            else tempos[entry.beat] = tempo
        }
        if (!tempos.containsKey(Rational.of(0))) issue(null, "MISSING_INITIAL_BPM", "Expected BPM at beat zero")
        return NormalizedSus(positioned, tempos, diagnostics.toList(), ticks)
    }
}

