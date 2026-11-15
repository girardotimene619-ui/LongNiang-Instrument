package com.longniang.instrument

import com.longniang.instrument.sus.*

internal enum class ComboKind { ACTION, RELAY, HALF_BEAT, ATTACHED_RELAY }

internal data class ComboContribution(
    val id: String, val kind: ComboKind, val beat: String, val at: Long,
    val pathId: String?, val judgmentId: String?, val sources: List<String>
)

internal class ComboPlan(contributions: List<ComboContribution>, val verifiedUpstream: Boolean) {
    val contributions = immutableChartList(contributions.sortedWith(compareBy({ it.at }, { it.id })))

    companion object {
        fun build(chart: Chart, actions: List<MixedAction>, paths: List<MixedPath>, provenance: List<String>): ComboPlan {
            val result = mutableListOf<ComboContribution>()
            val ticks = actions.filter { it.event.action == JudgmentAction.TICK }
                .associateBy { it.path!!.id to it.event.exactBeat }
            fun timeAt(beat: Rational): Long {
                val segment = chart.tempoSegments.last { it.beat <= beat }
                return (segment.time + (beat - segment.beat) * Rational.of(60_000_000) / segment.bpm).micros()
            }
            for (action in actions) {
                val event = action.event
                if (event.origin == JudgmentOrigin.DERIVED) continue
                val kind = if (event.action == JudgmentAction.TICK) ComboKind.RELAY else ComboKind.ACTION
                result += ComboContribution(event.id, kind, event.exactBeat, event.timeUs,
                    action.path?.id, event.id, event.sourceIds)
            }
            val two = Rational.of(2)
            for (path in paths) {
                val first = Rational.parse(path.nodes.first().subject.timing.exactBeat) * two
                val end = Rational.parse(path.nodes.last().subject.timing.exactBeat)
                var grid = (first.n / first.d).longValueExact() + 1
                while (true) {
                    val beat = Rational.of(grid++, 2)
                    if (beat >= end) break
                    val key = beat.toString()
                    val judgment = ticks[path.id to key]
                    val at = judgment?.at ?: timeAt(beat)
                    val tail = path.nodes.indexOfFirst { it.timeUs >= at }.coerceAtLeast(1)
                    val sources = judgment?.event?.sourceIds ?: listOf(
                        path.nodes[tail - 1].subject.id, path.nodes[tail].subject.id)
                    result += ComboContribution("${path.id}:combo-half:$key", ComboKind.HALF_BEAT,
                        key, at, path.id, judgment?.id, sources)
                }
            }
            val records = provenance.map { it.split('\t') }.filter { it.size == 7 && it[0] == chart.id }
            for ((_, upstream, beatText, channel, token, nodeSlot, markerSlot) in records.map {
                SourceRow(it[0], it[1], it[2], it[3], it[4], it[5], it[6])
            }) {
                require(channel.matches(Regex("3[0-9a-z]{2}")) && token.matches(Regex("3[1-9a-z]")))
                val beat = Rational.parse(beatText)
                val path = paths.single {
                    it.objectData.channel.lowercaseChar() == channel.last() &&
                        beat > Rational.parse(it.nodes.first().subject.timing.exactBeat) &&
                        beat < Rational.parse(it.nodes.last().subject.timing.exactBeat)
                }
                val at = timeAt(beat)
                val tick = ticks[path.id to beatText]
                result += ComboContribution("attach:$upstream:$nodeSlot", ComboKind.ATTACHED_RELAY,
                    beatText, at, path.id, tick?.id, listOf("$upstream:$nodeSlot:$channel/$token",
                        "$upstream:$markerSlot:1${channel[1]}/$token"))
            }
            check(result.map { it.id }.toSet().size == result.size)
            return ComboPlan(result, records.isNotEmpty())
        }

        private data class SourceRow(val chart: String, val upstream: String, val beat: String,
                                     val channel: String, val token: String, val node: String, val marker: String)
    }
}

/** Diagnostic projection. Existing engine settlements and contact samples are read-only inputs. */
internal class ComboObservation(plan: ComboPlan, actions: List<MixedAction>, start: Long, end: Long,
                                private val sampleGapUs: Long,
                                private val pathIds: Set<String> = actions.mapNotNullTo(hashSetOf()) { it.path?.id }) {
    private val actionIds = actions.mapTo(hashSetOf()) { it.id }
    private val contributions = plan.contributions.filter {
        it.at in start until end && (it.judgmentId?.let(actionIds::contains) ?: (it.pathId in pathIds))
    }
    private val checkpoints = contributions.filter { it.judgmentId == null }
    private val checkpointPaths = checkpoints.map { requireNotNull(it.pathId) }.distinct()
    private val contacts = mutableMapOf<String, Boolean?>()
    private val prior = mutableMapOf<String, Int>()
    private var last = Long.MIN_VALUE
    private var index = 0
    val planned get() = contributions.size
    val hasIndependentCheckpoints get() = checkpoints.isNotEmpty()
    val upstreamVerified = plan.verifiedUpstream

    fun observe(at: Long, owner: (String) -> Int?) {
        if (index == checkpoints.size || at < last) return
        while (index < checkpoints.size && checkpoints[index].at <= at) {
            val item = checkpoints[index++]
            val path = requireNotNull(item.pathId)
            val current = owner(path)
            contacts[item.id] = when {
                at == item.at -> current != null
                last == Long.MIN_VALUE || at - last > sampleGapUs -> null
                current != null && current == prior[path] -> true
                current == null && prior[path] == null -> false
                else -> null
            }
        }
        last = at
        prior.clear()
        for (path in checkpointPaths) owner(path)?.let { prior[path] = it }
    }

    fun breakContact() { last = Long.MIN_VALUE; prior.clear() }
    fun reset() { breakContact(); index = 0; contacts.clear() }

    data class Summary(val confirmed: Int, val missed: Int, val unknown: Int, val pending: Int)

    fun summary(results: Map<String, MixedFeedback>): Summary {
        var confirmed = 0
        var missed = 0
        var unknown = 0
        var pending = 0
        for (item in contributions) {
            val judgment = item.judgmentId
            val hit = if (judgment == null) contacts[item.id] else results[judgment]?.let { it.grade != Grade.MISS }
            when (hit) {
                true -> confirmed++
                false -> missed++
                null -> if (item.id in contacts) unknown++ else pending++
            }
        }
        return Summary(confirmed, missed, unknown, pending)
    }
}
