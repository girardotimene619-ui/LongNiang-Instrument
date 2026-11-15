package com.longniang.instrument

import com.longniang.instrument.sus.*
import kotlin.math.*

internal data class ActionWindow(val early: Long, val late: Long, val perfect: Long, val great: Long) {
    fun accepts(delta: Long) = delta in -early..late
    fun grade(delta: Long) = when {
        !accepts(delta) -> Grade.MISS
        abs(delta) <= perfect -> Grade.PERFECT
        abs(delta) <= great -> Grade.GREAT
        else -> Grade.GOOD
    }
}

internal enum class Grade(val points: Int) { MISS(0), GOOD(1), GREAT(2), PERFECT(3) }

/** Project defaults, expressed in chart microseconds and logical lane widths. */
internal data class MixedRules(
    val tap: ActionWindow = ActionWindow(120_000, 120_000, 42_000, 83_000),
    val criticalTap: ActionWindow = ActionWindow(120_000, 120_000, 55_000, 75_000),
    val flick: ActionWindow = ActionWindow(120_000, 135_000, 42_000, 100_000),
    val criticalFlick: ActionWindow = ActionWindow(120_000, 135_000, 58_000, 100_000),
    val tail: ActionWindow = ActionWindow(125_000, 142_000, 60_000, 110_000),
    val gapUs: Long = 80_000,
    val pathStepUs: Long = 8_000,
    val pathLeniency: Float = .25f,
    val gestureSpanUs: Long = 60_000,
    val flickDistance: Float = .25f,
    val flickSpeed: Float = 8f,
    val diagonalRadians: Double = 1.0,
    val angleTolerance: Double = PI / 2,
    val ticksPerBeat: Int = 2,
    val spatialExpansion: Float = .125f
) {
    init {
        require(pathStepUs > 0 && gapUs >= 0 && ticksPerBeat in 1..16 && spatialExpansion in 0f..1f)
    }
    val pathMargin get() = pathLeniency * (1 + spatialExpansion)
    fun window(action: MixedAction): ActionWindow = when {
        action.event.action == JudgmentAction.TICK -> ActionWindow(0, 0, 0, 0)
        action.event.action == JudgmentAction.TRACE -> tap
        action.isTail -> tail
        action.event.action == JudgmentAction.FLICK -> if (action.critical) criticalFlick else flick
        else -> if (action.critical) criticalTap else tap
    }

    fun gesture(dx: Float, dy: Float, dtUs: Long, direction: FlickDirection): Grade? {
        if (dtUs <= 0 || dtUs > gestureSpanUs) return null
        val distance = hypot(dx, dy)
        if (distance < flickDistance || distance * 1_000_000 / dtUs < flickSpeed) return null
        val center = when (direction) {
            FlickDirection.UP -> return Grade.PERFECT
            FlickDirection.UPPER_LEFT -> -diagonalRadians
            FlickDirection.UPPER_RIGHT -> diagonalRadians
        }
        val delta = atan2(dx.toDouble(), -dy.toDouble()) - center
        return if (abs(atan2(sin(delta), cos(delta))) <= angleTolerance) Grade.PERFECT else Grade.GREAT
    }
}

internal fun LaneInterval.span() = LaneSpan(left.toFloat(), right.toFloat())

internal data class MixedPath(val objectData: LongChartObject) {
    val id get() = objectData.id
    val nodes get() = objectData.nodes
    val start get() = nodes.first().timeUs
    val end get() = nodes.last().timeUs
    val kind get() = if (objectData.geometry == PathGeometry.STATIONARY_INTERVALS)
        LongNoteKind.HOLD else LongNoteKind.SLIDE

    fun spanAt(timeUs: Long): LaneSpan {
        val i = nodes.indexOfFirst { it.timeUs >= timeUs }
        if (i == -1) return nodes.last().interval.span()
        if (i == 0) return nodes.first().interval.span()
        val from = nodes[i - 1]
        val to = nodes[i]
        val p = (timeUs - from.timeUs).toDouble().div(to.timeUs - from.timeUs).toFloat()
        return LaneSpan(from.interval.left + (to.interval.left - from.interval.left) * p,
            from.interval.right + (to.interval.right - from.interval.right) * p)
    }
}

internal data class MixedAction(
    val event: JudgmentEvent,
    val critical: Boolean = false,
    val direction: FlickDirection? = null,
    val path: MixedPath? = null,
    val isTail: Boolean = false,
    val visible: Boolean = true
) {
    val id get() = event.id
    val at get() = event.timeUs
    val span get() = event.interval
}

/** Shared chart and rules for both engines. Visual paths and Combo provenance do not dispatch input. */
internal class MixedChart internal constructor(
    val columns: Int, val actions: List<MixedAction>, val paths: List<MixedPath>,
    val diagnostics: List<SusDiagnostic>, val rules: MixedRules, val comboPlan: ComboPlan? = null,
    val guides: List<GuideChartObject> = emptyList()
) {
    val playable get() = actions.isNotEmpty() && diagnostics.none { it.error }
    val lastTimeUs get() = actions.lastOrNull()?.at ?: 0L

    fun openingTapEarlyUs(): Long = actions.asSequence().takeWhile { it.at == 0L }
        .filter { it.path == null && it.event.action == JudgmentAction.PRESS }
        .maxOfOrNull { rules.window(it).early } ?: 0L

    companion object {
        fun prepare(chart: Chart, rules: MixedRules = MixedRules(),
                    comboProvenance: List<String> = emptyList()): MixedChart {
            val acceptedSpeedLines = emptySpeedLines(chart)
            val diagnostics = chart.diagnostics.filterNot {
                it.code == "UNVERIFIED_DIRECTIVE" && it.line in acceptedSpeedLines
            }.map { if (it.code.startsWith("UNVERIFIED_")) it.copy(error = true) else it }.toMutableList()
            if (chart.unresolvedSources.isNotEmpty() || diagnostics.any { it.error } || chart.objects.isEmpty())
                return MixedChart(chart.columns, emptyList(), emptyList(), diagnostics, rules)
            return try {
                build(chart, diagnostics, rules, comboProvenance)
            } catch (e: ArithmeticException) {
                diagnostics += SusDiagnostic(null, "JUDGMENT_TIMELINE_LIMIT", e.message.orEmpty(), true)
                MixedChart(chart.columns, emptyList(), emptyList(), diagnostics, rules)
            }
        }

        private fun emptySpeedLines(chart: Chart): Set<Int> {
            val definitions = chart.rawRecords.filter { it.key?.startsWith("TIL") == true }
            val definition = definitions.singleOrNull() ?: return emptySet()
            if (definition.key != "TIL00" || definition.value?.trim() != "\"\"") return emptySet()
            val refs = chart.rawRecords.filter { it.key in setOf("HISPEED", "MEASUREHS", "NOSPEED") }
            if (refs.any { it.key == "NOSPEED" || it.value?.trim() != "00" || it.line < definition.line })
                return emptySet()
            return (refs + definition).mapTo(hashSetOf()) { it.line }
        }

        private fun build(chart: Chart, diagnostics: List<SusDiagnostic>, rules: MixedRules,
                          comboProvenance: List<String>): MixedChart {
            val actions = mutableListOf<MixedAction>()
            val paths = mutableListOf<MixedPath>()
            val gridScale = Rational.of(rules.ticksPerBeat.toLong())
            for (obj in chart.objects) when (obj) {
                is InstantChartObject -> actions += explicit(obj.id, obj.subject, obj.modifiers,
                    if (obj.modifiers.direction == null) JudgmentAction.PRESS else JudgmentAction.FLICK, obj.sources)
                is GuideChartObject -> Unit
                is TraceChartObject -> actions += explicit(obj.id, obj.subject,
                    ChartModifiers(criticalSource = obj.subject.takeIf { obj.critical }),
                    JudgmentAction.TRACE, obj.sources)
                is LongChartObject -> {
                    val path = MixedPath(obj)
                    paths += path
                    for (node in path.nodes) {
                        val action = when (node.role) {
                            PathRole.START -> JudgmentAction.PRESS
                            PathRole.END -> if (node.modifiers.direction == null) JudgmentAction.RELEASE else JudgmentAction.FLICK
                            PathRole.RELAY -> JudgmentAction.TICK
                            PathRole.INVISIBLE -> continue
                        }
                        val onGrid = (Rational.parse(node.subject.timing.exactBeat) * gridScale).d ==
                            java.math.BigInteger.ONE
                        val origin = if (action == JudgmentAction.TICK && onGrid) JudgmentOrigin.MERGED
                            else JudgmentOrigin.EXPLICIT
                        actions += explicit(obj.id, node.subject, node.modifiers, action, node.sources, path,
                            node.role == PathRole.END, origin)
                    }
                    val byBeat = path.nodes.associateBy { it.subject.timing.exactBeat }
                    val first = Rational.parse(obj.subject.timing.exactBeat)
                    val last = Rational.parse(path.nodes.last().subject.timing.exactBeat)
                    val scaled = first * gridScale
                    var grid = (scaled.n / scaled.d).longValueExact() + 1
                    while (true) {
                        val beat = Rational.of(grid++, rules.ticksPerBeat.toLong())
                        if (beat >= last) break
                        val anchor = byBeat[beat.toString()]
                        if (anchor?.role == PathRole.RELAY) continue // Explicit Tick wins; raw sources stay on it.
                        if (actions.size >= 500_000) throw ArithmeticException("Too many judgment events")
                        val segment = chart.tempoSegments.last { it.beat <= beat }
                        val at = (segment.time + (beat - segment.beat) * Rational.of(60_000_000) / segment.bpm).micros()
                        val span = path.spanAt(at)
                        val sources = anchor?.sources ?: enclosingNodes(path, at).flatMap { it.sources }
                        val event = JudgmentEvent("${path.id}:tick:$beat", path.id, JudgmentAction.TICK,
                            JudgmentOrigin.DERIVED, beat.toString(), at, span, sources.map { it.id })
                        actions += MixedAction(event, path = path, visible = false)
                    }
                }
            }
            return MixedChart(chart.columns, actions.sortedWith(compareBy({ it.at }, { it.id })), paths.toList(),
                diagnostics, rules, ComboPlan.build(chart, actions, paths, comboProvenance),
                chart.objects.filterIsInstance<GuideChartObject>())
        }

        private fun enclosingNodes(path: MixedPath, timeUs: Long): List<PathNode> {
            val end = path.nodes.indexOfFirst { it.timeUs >= timeUs }.coerceAtLeast(1)
            return listOf(path.nodes[end - 1], path.nodes[end])
        }

        private fun explicit(
            objectId: String, source: ChartSource, mods: ChartModifiers, action: JudgmentAction,
            sources: List<ChartSource>, path: MixedPath? = null, tail: Boolean = false,
            origin: JudgmentOrigin = JudgmentOrigin.EXPLICIT
        ): MixedAction {
            val interval = requireNotNull(source.interval)
            return MixedAction(JudgmentEvent(source.id, objectId, action, origin,
                source.timing.exactBeat, source.timing.timeUs, interval.span(), sources.map { it.id }),
                mods.criticalSource != null, mods.direction, path, tail)
        }
    }
}
