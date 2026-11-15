package com.longniang.instrument.sus

import java.util.Collections

internal fun <T> immutableChartList(items: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(items))

data class LaneInterval(val left: Int, val width: Int) {
    val right: Int get() = left + width
}

data class LaneSpan(val left: Float, val right: Float) {
    val center: Float get() = (left + right) / 2
    fun contains(x: Float, margin: Float = 0f) = x >= left - margin && x < right + margin
}

data class ChartSource(
    val id: String,
    val timing: TimedSusOccurrence,
    val interval: LaneInterval?
) {
    val line: Int get() = timing.occurrence.source.line
    val slot: Int get() = timing.occurrence.index
}

enum class FlickDirection { UP, UPPER_LEFT, UPPER_RIGHT }
enum class PathRole { START, END, RELAY, INVISIBLE }
enum class PathGeometry { STATIONARY_INTERVALS, CHANGING_INTERVALS }
enum class LongNoteKind { HOLD, SLIDE, UNCLASSIFIED }

data class ChartModifiers(
    val criticalSource: ChartSource? = null,
    val direction: FlickDirection? = null,
    val directionSource: ChartSource? = null
)

sealed interface ChartObject {
    val id: String
    val subject: ChartSource
    val sources: List<ChartSource>
}

class InstantChartObject internal constructor(
    override val id: String,
    override val subject: ChartSource,
    val modifiers: ChartModifiers,
    sources: List<ChartSource>
) : ChartObject {
    val kind: ChartNoteKind get() =
        if (modifiers.direction == null) ChartNoteKind.TAP else ChartNoteKind.FLICK
    override val sources = immutableChartList(sources)
}

class GuideChartObject internal constructor(
    override val id: String,
    val channel: Char,
    nodes: List<PathNode>
) : ChartObject {
    val nodes = immutableChartList(nodes)
    override val subject get() = nodes.first().subject
    override val sources = immutableChartList(nodes.flatMap { it.sources })
}

class TraceChartObject internal constructor(
    override val id: String,
    override val subject: ChartSource,
    val critical: Boolean
) : ChartObject {
    override val sources = immutableChartList(listOf(subject))
}

class PathNode internal constructor(
    val subject: ChartSource,
    val role: PathRole,
    val modifiers: ChartModifiers,
    sources: List<ChartSource>
) {
    val visible: Boolean get() = role != PathRole.INVISIBLE
    val interval: LaneInterval get() = requireNotNull(subject.interval)
    val timeUs: Long get() = subject.timing.timeUs
    val sources = immutableChartList(sources)
}

class LongChartObject internal constructor(
    override val id: String,
    val channel: Char,
    val kind: LongNoteKind,
    nodes: List<PathNode>
) : ChartObject {
    val nodes = immutableChartList(nodes)
    override val subject: ChartSource get() = nodes.first().subject
    override val sources = immutableChartList(this.nodes.flatMap { it.sources })
    val geometry: PathGeometry = if (this.nodes.all { it.interval == this.nodes.first().interval })
        PathGeometry.STATIONARY_INTERVALS else PathGeometry.CHANGING_INTERVALS
}

enum class JudgmentAction { PRESS, FLICK, RELEASE, TICK, TRACE }
enum class JudgmentOrigin { EXPLICIT, DERIVED, MERGED }

/** Planned actions use separate IDs, including when they share a beat with a path node. */
class JudgmentEvent(
    val id: String,
    val objectId: String,
    val action: JudgmentAction,
    val origin: JudgmentOrigin,
    val exactBeat: String,
    val timeUs: Long,
    val interval: LaneSpan,
    sourceIds: List<String>
) {
    val sourceIds = immutableChartList(sourceIds)
}

fun interface JudgmentPlan {
    fun eventsFor(chart: Chart): List<JudgmentEvent>
}
