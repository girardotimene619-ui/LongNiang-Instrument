package com.longniang.instrument

import com.longniang.instrument.sus.FlickDirection
import com.longniang.judgment.*

internal enum class PracticeMode(val label: String, private val frames: LongRange? = null, val poc: Boolean = false) {
    FULL("完整谱面"),
    FIXED("固定循环 [2s,6s)", 96_000L until 288_000L),
    LEGACY_HIBANA("对照 · 旧内核 [30s,40s)", 1_440_000L until 1_920_000L),
    POC_HIBANA("PoC · 新内核 [30s,40s)", 1_440_000L until 1_920_000L, true),
    POC_BOUNDARY("PoC · 跨界长音 [36.2s,39s)", 1_737_600L until 1_872_000L, true),
    POC_TAIL("PoC · 方向尾 [74s,77s)", 3_552_000L until 3_696_000L, true);

    fun loop(totalFrames: Long) =
        frames?.let { PcmLoop(it.first, it.last + 1, PcmSignal.sampleRate, totalFrames) }
}

/** The existing practice controller owns time, epochs, and audio. */
internal interface MixedPlayback {
    val startUs: Long
    val endUs: Long
    val actions: List<MixedAction>
    val paths: List<MixedPath>
    val guides: List<com.longniang.instrument.sus.GuideChartObject> get() = emptyList()
    val results: Map<String, MixedFeedback>
    val objectResults: Map<String, MixedObjectFeedback>
    val objectCount: Int
    val points: Int
    val combo: Int
    val comboObservation: ComboObservation? get() = null
    var trace: JudgmentTrace?
    fun hasContact(pathId: String): Boolean
    fun freezeState(): String
    /** True when the engine owns the touch stream, even if no note matched. */
    fun down(pointer: Int, point: MixedPoint, at: Long): Boolean
    fun move(pointer: Int, point: MixedPoint, at: Long)
    fun up(pointer: Int, point: MixedPoint, at: Long)
    fun advance(watermarkUs: Long)
    fun reset()
    fun cancel(at: Long? = null)
    fun discard(pointer: Int)
}

internal class PocPlayback(
    chart: MixedChart, override val startUs: Long, override val endUs: Long, recoverRelease: Boolean = true,
    recoverFlickTail: Boolean = true, openingTapEarlyUs: Long = 0L
) : MixedPlayback {
    override val guides = chart.guides
    private val plan = Plan(chart.actions.map { note ->
        val w = chart.rules.window(note)
        Event(note.id, note.event.objectId, note.at, Span(note.span.left, note.span.right),
            Action.valueOf(note.event.action.name), Window(w.early, w.late, w.perfect, w.great),
            when (note.direction) {
                FlickDirection.UP -> Direction.OMNI
                FlickDirection.UPPER_LEFT -> Direction.LEFT
                FlickDirection.UPPER_RIGHT -> Direction.RIGHT
                null -> null
            }, note.path?.id, note.isTail)
    }, chart.paths.map { path ->
        HoldPath(path.id, path.nodes.map { PathAnchor(it.timeUs,
            Span(it.interval.left.toFloat(), it.interval.right.toFloat())) })
    }, Segment(startUs, endUs))
    private val rules = chart.rules
    private val engine = JudgmentEngine(plan, Policy(rules.gapUs, rules.pathStepUs, rules.pathMargin,
        rules.gestureSpanUs, rules.flickDistance, rules.flickSpeed, rules.diagonalRadians, rules.angleTolerance,
        rules.spatialExpansion), recoverRelease, recoverFlickTail, openingTapEarlyUs)
    override var trace: JudgmentTrace?
        get() = engine.diagnostics
        set(value) { engine.diagnostics = value }
    private val actionById = chart.actions.associateBy { it.id }
    override val actions = plan.events.map { actionById.getValue(it.id) }
    override val paths = chart.paths.filter { it.end >= startUs && it.start < endUs }
    override val comboObservation = chart.comboPlan?.let {
        ComboObservation(it, actions, startUs, endUs, chart.rules.pathStepUs * 2)
    }
    init { engine.contactObserver = comboObservation?.takeIf { it.hasIndependentCheckpoints }?.let { it::observe } }
    private val settled = linkedMapOf<String, MixedFeedback>()
    private val completed = linkedMapOf<String, MixedObjectFeedback>()
    override val results get() = settled
    override val objectResults get() = completed
    override val objectCount get() = engine.objectCount
    override val points get() = engine.points
    override val combo get() = engine.combo
    override fun hasContact(pathId: String) = engine.hasContact(pathId)
    override fun freezeState() = engine.freezeState()
    private var rounds = 0
    private var maxInputNs = 0L

    override fun down(pointer: Int, point: MixedPoint, at: Long) = input(InputAction.DOWN, pointer, point, at)
    override fun move(pointer: Int, point: MixedPoint, at: Long) { input(InputAction.MOVE, pointer, point, at) }
    override fun up(pointer: Int, point: MixedPoint, at: Long) {
        if (!input(InputAction.UP, pointer, point, at)) engine.discard(pointer)
    }
    override fun advance(watermarkUs: Long) { engine.advance(watermarkUs); publish() }
    override fun cancel(at: Long?) {
        engine.clearContacts(at)
        comboObservation?.breakContact()
        publish()
    }
    override fun discard(pointer: Int) {
        engine.discard(pointer)
        comboObservation?.breakContact()
    }
    override fun reset() {
        engine.reset()
        comboObservation?.reset()
        settled.clear()
        completed.clear()
        rounds++
    }

    internal fun trace() = engine.recentTrace()
    internal fun diagnosticText(): String {
        val recent = trace().takeLast(5).joinToString("\n") {
            "${it.time} ${it.kind} ${it.id}${it.pointer?.let { id -> " p$id" }.orEmpty()}"
        }
        return "PoC rounds=$rounds · maxInput=${maxInputNs / 1000} us\n$recent"
    }

    private fun input(action: InputAction, pointer: Int, point: MixedPoint, at: Long): Boolean {
        val start = System.nanoTime() // Measures CPU work only. Never drives judgment.
        val accepted = engine.submit(Input(action, pointer, at,
            Contact(point.lane, point.x, point.y, point.laneX, point.releaseLane)))
        publish()
        maxInputNs = maxOf(maxInputNs, System.nanoTime() - start)
        return accepted
    }

    private fun publish() {
        if (settled.size != engine.outcomes.size) for ((id, event) in engine.outcomes) {
            if (id !in settled) settled[id] =
                MixedFeedback(actionById.getValue(id), Grade.valueOf(event.rating.name), event.deltaUs, event.pointer)
        }
        if (completed.size != engine.completedObjects.size) for ((id, obj) in engine.completedObjects) {
            if (id !in completed) completed[id] =
                MixedObjectFeedback(actionById.getValue(obj.displayEvent), Grade.valueOf(obj.rating.name))
        }
    }
}
