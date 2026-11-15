package com.longniang.instrument

import com.longniang.instrument.sus.JudgmentAction
import com.longniang.instrument.sus.LaneSpan
import com.longniang.judgment.JudgmentTrace
import com.longniang.judgment.ReleaseClaims
import com.longniang.judgment.gestureDetail
import kotlin.math.abs

internal data class MixedPoint(val x: Float, val y: Float, val lane: Int?, val laneX: Float = x,
                               val releaseLane: Int? = lane)
internal data class MixedFeedback(val action: MixedAction, val grade: Grade, val deltaUs: Long, val pointer: Int? = null)
internal data class MixedObjectFeedback(val action: MixedAction, val grade: Grade)

/** All mutations are serialized by TapSurface; sampling time drives the session. */
internal class MixedSession(
    chart: MixedChart,
    override val startUs: Long,
    override val endUs: Long,
    private val rules: MixedRules = chart.rules,
    private val recoverRelease: Boolean = true,
    private val recoverFlickTail: Boolean = true,
    openingTapEarlyUs: Long = 0L
) : MixedPlayback {
    private val inputStartUs = if (startUs == 0L) -openingTapEarlyUs else startUs
    override val guides = chart.guides
    override var trace: JudgmentTrace? = null
    override val actions = chart.actions.filter {
        it.at in startUs until endUs && (it.path == null || it.path.start >= startUs)
    }
    override val paths = chart.paths.filter { it.end >= startUs && it.start < endUs }
    override val comboObservation = chart.comboPlan?.let {
        ComboObservation(it, actions, startUs, endUs, rules.pathStepUs * 2)
    }
    private val openings = paths.filter { it.start >= startUs }.sortedWith(compareBy({ it.start }, { it.id }))
    private val ticks = actions.filter { it.event.action == JudgmentAction.TICK || it.event.action == JudgmentAction.TRACE }
    private val traceNotes = ticks.filter { it.event.action == JudgmentAction.TRACE }
    private val deadlines = actions.sortedBy { it.at + rules.window(it).late }
    private val tails = actions.filter { it.isTail }.associateBy { it.path!!.id }
    private val touches = linkedMapOf<Int, Touch>()
    private val held = linkedMapOf<String, Held>()
    private val readContactOwner: (String) -> Int? = { held[it]?.owner }
    private val releaseClaims = ReleaseClaims()
    private val armed = mutableMapOf<Int, MixedAction>()
    private val settled = mutableMapOf<String, MixedFeedback>()
    private val earlyTails = mutableMapOf<String, MixedFeedback>()
    // A clipped chain has playable events but no whole-object completion in this round.
    private val objectActions = actions.filter { it.path == null || it.path.id in tails }.groupBy { it.event.objectId }
    private val pendingByObject = objectActions.mapValues { it.value.size }.toMutableMap()
    private val worstByObject = mutableMapOf<String, Grade>()
    private val completed = mutableMapOf<String, MixedObjectFeedback>()
    override val results: Map<String, MixedFeedback> get() = settled
    override val objectResults: Map<String, MixedObjectFeedback> get() = completed
    override val objectCount: Int get() = objectActions.size
    internal fun contactOwners(): Map<String, Int?> = held.mapValues { it.value.owner }
    override fun hasContact(pathId: String) = held[pathId]?.owner != null
    override fun freezeState(): String =
        "legacy finalized=$finalizedUs tick=$tickIndex/${ticks.size}:${ticks.getOrNull(tickIndex)?.id}" +
            " deadline=$deadlineIndex/${deadlines.size}:${deadlines.getOrNull(deadlineIndex)?.id}" +
            " pointers=${touches.mapValues { it.value.point.lane }} armed=${armed.mapValues { it.value.id }}" +
            " held=${held.mapValues { (_, h) -> "${h.owner}@${h.lastContact}:node=${h.path.nodes.indexOfFirst { it.timeUs >= finalizedUs }}:tail=${tails[h.path.id]?.id}" }}" +
            " buffered=${earlyTails.keys}"
    override var points = 0; private set
    override var combo = 0; private set
    private var tickIndex = 0
    private var openingIndex = 0
    private var deadlineIndex = 0
    private var nextContactUs = startUs
    private var finalizedUs = inputStartUs - 1

    private data class Sample(val at: Long, val point: MixedPoint)
    private class Touch(first: Sample) {
        val history = ArrayDeque<Sample>().apply { add(first) }
        val point get() = history.last().point
    }
    private data class Held(val path: MixedPath, var owner: Int?, var lastContact: Long?)

    override fun reset() {
        cancel()
        held.clear()
        settled.clear()
        completed.clear()
        worstByObject.clear()
        pendingByObject.clear()
        objectActions.forEach { (id, events) -> pendingByObject[id] = events.size }
        points = 0; combo = 0
        tickIndex = 0; deadlineIndex = 0; openingIndex = 0
        finalizedUs = inputStartUs - 1; nextContactUs = startUs
        comboObservation?.reset()
    }

    override fun cancel(at: Long?) {
        for (state in held.values) {
            val owner = state.owner ?: continue
            if (at != null) state.lastContact = maxOf(state.lastContact ?: at, minOf(at, state.path.end))
            trace?.record("DETACH", at ?: finalizedUs, state.path.id, owner, "CANCEL last=${state.lastContact}")
            state.owner = null
        }
        touches.clear()
        releaseClaims.clear()
        armed.clear()
        earlyTails.clear()
        comboObservation?.breakContact()
    }

    override fun down(pointer: Int, point: MixedPoint, at: Long): Boolean {
        if (!begin(at) || pointer in touches) {
            trace?.record("INPUT_REJECT", at, null, pointer, "FINALIZED_OR_DUPLICATE_DOWN")
            return false
        }
        touches[pointer] = Touch(Sample(at, point))
        var candidates = 0
        val action = actions.asSequence().filter { !it.isTail && it.event.action in pressActions }
            .filter { at >= startUs || it.at == 0L && it.path == null && it.event.action == JudgmentAction.PRESS }
            .filter {
            val delta = at - it.at
            val reason = when {
                delta < -rules.window(it).early -> "TOO_EARLY"
                delta > rules.window(it).late -> "TOO_LATE"
                it.id in settled -> "EVENT_ALREADY_RESOLVED"
                it in armed.values -> "POINTER_ALREADY_ASSIGNED"
                point.lane == null -> "NO_LOGICAL_LANE"
                !inAction(point, it) -> "OUT_OF_LANE"
                else -> null
            }
            if (trace != null && abs(delta) <= 250_000)
                trace?.record("PRESS_CANDIDATE", at, it.id, pointer, reason ?: "ELIGIBLE")
            if (reason == null) candidates++
            reason == null
        }.minWithOrNull(compareBy<MixedAction> { !inOriginalLane(point, it) }
            .thenBy { abs(at - it.at) }.thenBy { it.at }.thenBy { it.id })
        trace?.record("PRESS_ASSIGN", at, action?.id, pointer, "candidates=$candidates")
        val directTrace = if (at < startUs) null else traceDown(point, at, pointer, action != null)
        if (action == null) {
            if (directTrace != null)
                settle(directTrace, rules.tap.grade(at - directTrace.at), at, pointer, "TRACE_DOWN")
            refreshContact(at)
            return true
        }
        if (action.event.action == JudgmentAction.FLICK) armed[pointer] = action
        else {
            settle(action, rules.window(action).grade(at - action.at), at, pointer, "PRESS")
            action.path?.let { held[it.id] = Held(it, pointer, at) }
        }
        if (action.path != null) trace?.record("ATTACH", at, action.path.id, pointer, "HEAD")
        refreshContact(at)
        return true
    }

    private fun traceDown(point: MixedPoint, at: Long, pointer: Int, reserved: Boolean): MixedAction? =
        traceNotes.asSequence().filter { rules.tap.accepts(at - it.at) }.filter {
            val reason = when {
                it.id in settled -> "EVENT_ALREADY_RESOLVED"
                point.lane == null -> "NO_LOGICAL_LANE"
                !inAction(point, it) -> "OUT_OF_LANE"
                reserved -> "PRESS_RESERVED"
                else -> null
            }
            trace?.record("TRACE_DOWN_CANDIDATE", at, it.id, pointer, reason ?: "ELIGIBLE")
            reason == null
        }.minWithOrNull(compareBy<MixedAction> { !inOriginalLane(point, it) }
            .thenBy { abs(at - it.at) }.thenBy { it.at }.thenBy { it.id })

    override fun move(pointer: Int, point: MixedPoint, at: Long) = move(pointer, point, at, true)

    private fun move(pointer: Int, point: MixedPoint, at: Long, allowSweep: Boolean) {
        if (!begin(at)) return
        val touch = touches[pointer] ?: return
        if (at < touch.history.last().at) return
        val previousSample = touch.history.last()
        val previous = previousSample.point
        val previousTail = ownedTail(pointer)
        markLastContact(pointer, at)
        while (touch.history.isNotEmpty() && at - touch.history.first().at > rules.gestureSpanUs)
            touch.history.removeFirst()
        if (touch.history.lastOrNull()?.at == at) touch.history.removeLast()
        touch.history.addLast(Sample(at, point))
        if (point.lane != null && traceNotes.isNotEmpty()) {
            val first = traceNotes.binarySearch { if (it.at < at - rules.tap.late) -1 else 1 }.inv()
            var swept = false
            for (i in first until traceNotes.size) {
                val note = traceNotes[i]
                if (note.at >= at) break
                if (note.id in settled) continue
                if (inAction(point, note)) {
                    settle(note, rules.tap.grade(at - note.at), at, pointer, "TRACE_MOVE")
                } else if (allowSweep && !swept && previous.lane != null) {
                    val laneX = com.longniang.judgment.TraceSweep.laneAt(note.at, previousSample.at, at,
                        previous.laneX, point.laneX) ?: continue
                    if (note.span.contains(laneX)) {
                        settle(note, Grade.PERFECT, note.at, pointer, "TRACE_SWEEP")
                        swept = true
                    }
                }
            }
        }
        refreshContact(at)
        val tail = (ownedTail(pointer) ?: previousTail?.takeIf {
            !recoverFlickTail || releaseClaims.path(pointer) == it.path?.id
        })?.takeIf { it.direction != null }
        val immediate = tail ?: armed[pointer]
        val origins = if (recoverFlickTail && immediate == null)
            releaseClaims.flickOrigins(pointer, at, rules.gestureSpanUs) else null
        val action = immediate ?: (if (origins != null) releaseClaims.path(pointer)?.let(tails::get)
            ?.takeIf { it.event.action == JudgmentAction.FLICK } else null) ?: return
        // A historical gesture is a fallback for a tail without an accepted swipe.
        if (origins != null && action.id in earlyTails) return
        val window = rules.window(action)
        if (action.id in settled || !window.accepts(at - action.at)) {
            trace?.record("FLICK_REJECT", at, action.id, pointer, if (action.id in settled) "EVENT_ALREADY_RESOLVED"
                else if (at < action.at) "TOO_EARLY" else "TOO_LATE")
            return
        }
        if (origins == null && !inAction(point, action) && !inAction(previous, action)) {
            trace?.record("FLICK_REJECT", at, action.id, pointer, "OUT_OF_LANE")
            return
        }
        val direction = requireNotNull(action.direction)
        val directionGrade = touch.history.asSequence().mapNotNull { from ->
            if (origins != null && (from.at !in origins || !inAction(from.point, action))) return@mapNotNull null
            val dx = point.x - from.point.x
            val dy = point.y - from.point.y
            val dt = at - from.at
            val grade = rules.gesture(dx, dy, dt, direction)
            if (trace != null) {
                val distance = kotlin.math.hypot(dx, dy)
                trace?.record("FLICK_SAMPLE", at, action.id, pointer, gestureDetail(dx, dy, dt, distance,
                    rules.flickDistance, rules.flickSpeed, grade?.name, direction.name))
            }
            grade
        }.maxOrNull() ?: return
        // An early wrong-way motion leaves room for a correct swipe at the target.
        if (directionGrade == Grade.GREAT && at < action.at) {
            trace?.record("FLICK_REJECT", at, action.id, pointer, "EARLY_WRONG_DIRECTION")
            return
        }
        val grade = minOf(window.grade(at - action.at), directionGrade)
        if (origins != null) trace?.record("FLICK_TAIL_HISTORY", at, action.id, pointer, grade.name)
        if (action.isTail) acceptTail(action, grade, at, pointer) else settle(action, grade, at, pointer, "FLICK")
        armed.remove(pointer)
    }

    override fun up(pointer: Int, point: MixedPoint, at: Long) {
        if (!begin(at)) {
            discard(pointer)
            return
        }
        val touch = touches[pointer] ?: return
        if (at < touch.history.last().at) return
        markLastContact(pointer, at)
        val owned = ownedTail(pointer)
        val historical = if (recoverRelease && owned == null && pointer !in armed)
            releaseClaims.path(pointer)?.let(tails::get)?.takeIf { it.event.action == JudgmentAction.RELEASE } else null
        val action = owned ?: historical
        val lane = if (recoverRelease) point.releaseLane else point.lane
        val reason = if (action == null) "NO_OWNED_TAIL" else when {
            action.event.action != JudgmentAction.RELEASE -> "FLICK_REQUIRES_MOVE"
            action.id in settled -> "EVENT_ALREADY_RESOLVED"
            at - action.at < -rules.window(action).early -> "TOO_EARLY"
            at - action.at > rules.window(action).late -> "TOO_LATE"
            historical != null && !releaseClaims.canRelease(pointer, at) -> "RELEASE_HISTORY_EXPIRED"
            recoverRelease && lane == null -> "OUTSIDE_RELEASE_REGION"
            !inAction(point, action, lane) -> "OUT_OF_LANE"
            historical != null -> "ELIGIBLE_RELEASE_HISTORY"
            else -> "ELIGIBLE_RELEASE"
        }
        trace?.record(if (action == null) "RELEASE_UNASSIGNED" else "TAIL_INPUT", at, action?.id, pointer, reason)
        if (action != null && (reason == "ELIGIBLE_RELEASE" || reason == "ELIGIBLE_RELEASE_HISTORY")) {
            acceptTail(action, rules.window(action).grade(at - action.at), at, pointer)
        } else if (pointer in armed) {
            move(pointer, point, at, allowSweep = false)
        }
        detach(pointer, at)
        refreshContact(at)
    }

    private fun ownedTail(pointer: Int): MixedAction? =
        held.values.firstOrNull { it.owner == pointer }?.let { tails[it.path.id] }

    private fun markLastContact(pointer: Int, at: Long) {
        val lane = touches[pointer]?.point?.lane ?: return
        val state = held.values.firstOrNull { it.owner == pointer } ?: return
        if (state.path.spanAt(at).contains(lane + .5f, rules.pathMargin)) state.lastContact = at
    }

    private fun acceptTail(action: MixedAction, grade: Grade, at: Long, pointer: Int) {
        val candidate = MixedFeedback(action, grade, at - action.at, pointer)
        val old = earlyTails[action.id]
        val best = if (old == null || grade > old.grade ||
            (grade == old.grade && abs(candidate.deltaUs) < abs(old.deltaUs))) candidate else old
        if (at >= action.at) settle(action, best.grade, action.at + best.deltaUs, best.pointer, "TAIL")
        else {
            earlyTails[action.id] = best
            trace?.record("TAIL_BUFFER", at, action.id, pointer, grade.name)
        }
    }

    override fun discard(pointer: Int) {
        detach(pointer, finalizedUs)
        comboObservation?.breakContact()
    }

    private fun detach(pointer: Int, at: Long) {
        touches.remove(pointer)
        releaseClaims.discard(pointer)
        armed.remove(pointer)
        held.values.filter { it.owner == pointer }.forEach {
            trace?.record("DETACH", at, it.path.id, pointer, "POINTER_REMOVED last=${it.lastContact}")
            it.owner = null
        }
    }

    private fun inOriginalLane(point: MixedPoint, action: MixedAction) =
        point.lane?.let { action.span.contains(it + .5f) } == true

    private fun inAction(point: MixedPoint, action: MixedAction, candidateLane: Int? = point.lane): Boolean {
        val lane = candidateLane ?: return false
        val centerMatch = if (action.isTail) action.span.contains(lane + .5f, rules.pathMargin)
            else inOriginalLane(point, action)
        return centerMatch || rules.spatialExpansion > 0 &&
            action.span.contains(point.laneX, (action.span.right - action.span.left) * rules.spatialExpansion / 2)
    }

    private fun begin(at: Long): Boolean {
        if (at !in inputStartUs until endUs || at <= finalizedUs) return false
        advance(at - 1)
        return true
    }

    override fun advance(watermarkUs: Long) {
        val limit = minOf(watermarkUs, endUs - 1)
        if (limit <= finalizedUs) return
        if (nextContactUs <= finalizedUs) nextContactUs = finalizedUs + 1
        while (true) {
            val tick = ticks.getOrNull(tickIndex)
            val deadline = deadlines.getOrNull(deadlineIndex)
            val expires = deadline?.let { it.at + rules.window(it).late + 1 } ?: Long.MAX_VALUE
            val contact = if (held.isEmpty()) Long.MAX_VALUE else nextContactUs
            val buffered = earlyTails.values.minWithOrNull(compareBy({ it.action.at }, { it.action.id }))
            val opening = openings.getOrNull(openingIndex)?.start ?: Long.MAX_VALUE
            val next = minOf(tick?.at ?: Long.MAX_VALUE, expires, contact, buffered?.action?.at ?: Long.MAX_VALUE, opening)
            if (next > limit) break
            if (next == opening) {
                if (held.isEmpty()) nextContactUs = next + rules.pathStepUs
                while (openings.getOrNull(openingIndex)?.start == next) {
                    val path = openings[openingIndex++]
                    if (path.id !in held) held[path.id] = Held(path, null, null)
                }
            }
            refreshContact(next)
            if (next == contact) nextContactUs = next + rules.pathStepUs
            if (next == tick?.at) {
                if (tick.event.action == JudgmentAction.TRACE) {
                    val pointer = touches.entries.asSequence()
                        .filter { inAction(it.value.point, tick) }
                        .map { it.key }.minOrNull()
                    if (tick.id !in settled) {
                        if (pointer != null) settle(tick, Grade.PERFECT, next, pointer, "TRACE_CONTACT")
                        else trace?.record("TRACE_WAIT", next, tick.id, null, "NO_CONTACT_AT_TARGET")
                    }
                } else {
                    val state = held[tick.path!!.id]
                    val hit = state?.owner != null || state?.lastContact?.let { next - it <= rules.gapUs } == true
                    settle(tick, if (hit) Grade.PERFECT else Grade.MISS, next, state?.owner,
                        if (!hit) "HOLD_NOT_CONTACTED" else if (state?.owner == null) "CONTACT_GRACE" else "CONTACT")
                }
                tickIndex++
            }
            if (buffered != null && next == buffered.action.at) {
                settle(buffered.action, buffered.grade, next + buffered.deltaUs, buffered.pointer, "BUFFERED_TAIL")
            }
            if (next == expires && deadline != null) {
                if (deadline.id !in settled) {
                    settle(deadline, Grade.MISS, next, null, "WINDOW_EXPIRED_${deadline.event.action}")
                }
                deadlineIndex++
            }
        }
        finalizedUs = limit
    }

    private fun refreshContact(at: Long) {
        val reserved = hashSetOf<Int>()
        fun fits(id: Int, span: LaneSpan): Boolean {
            val lane = touches[id]?.point?.lane ?: return false
            return id !in armed && span.contains(lane + .5f, rules.pathMargin)
        }
        // Keep valid incumbents first. A disconnected path cannot steal another path's finger.
        for (state in held.values) {
            val owner = state.owner ?: continue
            if (!fits(owner, state.path.spanAt(at)) || !reserved.add(owner)) {
                if (recoverRelease || recoverFlickTail) releaseClaims.lose(owner, state.path.id, at)
                trace?.record("DETACH", at, state.path.id, owner, "PATH_OR_ALLOCATION last=${state.lastContact}")
                state.owner = null
            }
            else {
                state.lastContact = at
                if (recoverRelease || recoverFlickTail) releaseClaims.acquire(owner, state.path.id, at)
            }
        }
        for (state in held.values) {
            if (state.owner != null) continue
            val span = state.path.spanAt(at)
            state.owner = touches.keys.asSequence().filter { it !in reserved && fits(it, span) }.minWithOrNull(
                compareBy<Int> { abs(requireNotNull(touches.getValue(it).point.lane) + .5f - span.center) }.thenBy { it })
            state.owner?.let {
                reserved += it
                if (recoverRelease || recoverFlickTail) releaseClaims.acquire(it, state.path.id, at)
                trace?.record("ATTACH", at, state.path.id, it, "last=${state.lastContact} span=${span.left}:${span.right}")
                state.lastContact = at
            }
        }
        comboObservation?.takeIf { it.hasIndependentCheckpoints }?.observe(at, readContactOwner)
    }

    private fun settle(action: MixedAction, grade: Grade, at: Long, pointer: Int?, reason: String) {
        if (settled.putIfAbsent(action.id, MixedFeedback(action, grade, at - action.at, pointer)) != null) return
        trace?.record("SETTLE", at, action.id, pointer, "grade=$grade reason=$reason")
        if (trace != null && action.path != null && (action.isTail || action.event.action == JudgmentAction.TICK)) {
            val path = action.path
            val state = held[path.id]
            val span = path.spanAt(at)
            trace?.record("LONG_STATE", at, action.id, state?.owner,
                "chain=${path.id} head=${settled[path.nodes.first().subject.id]?.grade} " +
                    "node=${path.nodes.indexOfLast { it.timeUs <= at }} span=${span.left}:${span.right} " +
                    "lastContact=${state?.lastContact} tail=${action.isTail}")
        }
        earlyTails.remove(action.id)
        if (action.isTail) {
            val pathId = action.path!!.id
            held.remove(pathId)
            if (recoverRelease || recoverFlickTail) releaseClaims.finish(pathId)
        }
        armed.entries.removeAll { it.value.id == action.id }
        points += grade.points
        combo = if (grade == Grade.MISS) 0 else combo + 1
        val id = action.event.objectId
        val remaining = pendingByObject[id] ?: return
        val worst = minOf(worstByObject[id] ?: Grade.PERFECT, grade)
        if (remaining == 1) {
            val display = objectActions.getValue(id).last()
            completed[id] = MixedObjectFeedback(display, worst)
            pendingByObject.remove(id)
            worstByObject.remove(id)
        } else {
            pendingByObject[id] = remaining - 1
            worstByObject[id] = worst
        }
    }

    private companion object {
        val pressActions = setOf(JudgmentAction.PRESS, JudgmentAction.FLICK)
    }
}
