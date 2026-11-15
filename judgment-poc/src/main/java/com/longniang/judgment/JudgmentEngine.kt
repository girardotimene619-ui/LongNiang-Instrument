package com.longniang.judgment

import kotlin.math.*

data class Policy(
    val contactGraceUs: Long,
    val pathStepUs: Long,
    val pathMargin: Float,
    val gestureHistoryUs: Long,
    val minimumDistance: Float,
    val minimumSpeed: Float,
    val diagonalRadians: Double,
    val directionTolerance: Double,
    val spatialExpansion: Float = 0f
) {
    init { require(pathStepUs > 0 && contactGraceUs >= 0 && gestureHistoryUs > 0) }
}

/** A round has no clock of its own. All timestamps come from its caller. */
class JudgmentEngine(private val plan: Plan, private val policy: Policy, private val recoverRelease: Boolean = true,
                     private val recoverFlickTail: Boolean = true, openingTapEarlyUs: Long = 0L) {
    private val inputStart = if (plan.segment.start == 0L) -openingTapEarlyUs else plan.segment.start
    var diagnostics: JudgmentTrace? = null
    private data class Held(var owner: Int?, var lastContact: Long?)
    private data class Sample(val time: Long, val contact: Contact)
    private data class Candidate(val event: Event, val outcome: Outcome)

    private val heads = plan.events.filter { !it.tail && it.action in setOf(Action.PRESS, Action.FLICK) }
    private val openings = heads.filter { it.pathId != null && it.action == Action.PRESS }
    private val headByPath = openings.associateBy { it.pathId }
    private val ticks = plan.events.filter { it.action == Action.TICK || it.action == Action.TRACE }
    private val traceNotes = ticks.filter { it.action == Action.TRACE }
    private val maxTraceLate = traceNotes.maxOfOrNull { it.window.late } ?: 0L
    private val deadlines = plan.events.filter { it.action != Action.TICK }
        .sortedBy { it.time + it.window.late }
    private val tails = plan.events.filter { it.tail }.associateBy { it.pathId }
    private val objects = plan.events.filter { it.pathId == null || it.pathId in tails }.groupBy { it.objectId }
    private val pending = mutableMapOf<String, Int>()
    private val worst = mutableMapOf<String, Rating>()
    private val contacts = sortedMapOf<Int, ArrayDeque<Sample>>()
    private val held = linkedMapOf<String, Held>()
    private val releaseClaims = ReleaseClaims()
    private val armed = mutableMapOf<Int, Event>()
    private val deferred = mutableMapOf<String, Candidate>()
    private val settled = linkedMapOf<String, Outcome>()
    private val completed = linkedMapOf<String, ObjectOutcome>()
    private val trace = ArrayDeque<TraceEntry>()
    val outcomes: Map<String, Outcome> get() = settled
    val completedObjects: Map<String, ObjectOutcome> get() = completed
    val objectCount get() = objects.size
    fun recentTrace(): List<TraceEntry> = trace.toList()
    fun hasContact(pathId: String): Boolean = held[pathId]?.owner != null
    var contactObserver: ((Long, (String) -> Int?) -> Unit)? = null
    private val readContactOwner: (String) -> Int? = { held[it]?.owner }
    fun freezeState(): String =
        "poc finalized=$watermark tick=$tickIndex/${ticks.size}:${ticks.getOrNull(tickIndex)?.id}" +
            " deadline=$deadlineIndex/${deadlines.size}:${deadlines.getOrNull(deadlineIndex)?.id}" +
            " pointers=${contacts.mapValues { it.value.lastOrNull()?.contact?.lane }} armed=${armed.mapValues { it.value.id }}" +
            " held=${held.mapValues { (id, h) -> "${h.owner}@${h.lastContact}:tail=${tails[id]?.id}" }} buffered=${deferred.keys}"
    var points = 0; private set
    var combo = 0; private set
    private var tickIndex = 0
    private var openingIndex = 0
    private var deadlineIndex = 0
    private var nextContact = plan.segment.start
    private var watermark = inputStart - 1
    private var lastInput = watermark

    init { reset() }

    fun reset() {
        contacts.clear(); held.clear(); armed.clear(); deferred.clear()
        releaseClaims.clear()
        settled.clear(); completed.clear(); worst.clear(); pending.clear(); trace.clear()
        objects.forEach { (id, events) -> pending[id] = events.size }
        points = 0
        combo = 0
        tickIndex = 0
        openingIndex = 0
        deadlineIndex = 0
        nextContact = plan.segment.start
        watermark = inputStart - 1
        lastInput = watermark
    }

    fun clearContacts(at: Long? = null) {
        contacts.clear()
        releaseClaims.clear()
        armed.clear()
        deferred.clear()
        for ((id, state) in held) {
            if (state.owner != null) {
                if (at != null) state.lastContact = maxOf(state.lastContact ?: at, at)
                record(at ?: watermark, "detach", id, state.owner)
            }
            state.owner = null
        }
    }

    fun discard(pointer: Int) {
        contacts.remove(pointer)
        releaseClaims.discard(pointer)
        armed.remove(pointer)
        refresh(watermark)
    }

    fun submit(input: Input): Boolean {
        val (action, pointer, at, contact) = input
        if (at !in inputStart until plan.segment.end || at <= watermark || at < lastInput) {
            diagnostics?.record("INPUT_REJECT", at, null, pointer, "OUTSIDE_SEGMENT_OR_FINALIZED")
            return false
        }
        advance(at - 1)
        lastInput = at
        when (action) {
            InputAction.DOWN -> {
                if (pointer in contacts) return false
                contacts[pointer] = ArrayDeque<Sample>().apply { add(Sample(at, contact)) }
                val event = headAt(contact, at, pointer)
                val directTrace = if (at < plan.segment.start) null else traceDown(contact, at, pointer, event != null)
                if (directTrace != null)
                    settle(directTrace, directTrace.window.rating(at - directTrace.time), at, pointer, "TRACE_DOWN")
                if (event != null) {
                    if (event.action == Action.FLICK) {
                        armed[pointer] = event
                        record(at, "arm", event.id, pointer)
                    } else {
                        if (event.pathId != null) {
                            if (held.isEmpty()) nextContact = at
                            held[event.pathId] = Held(null, null)
                        }
                        settle(event, event.window.rating(at - event.time), at, pointer, "PRESS")
                    }
                }
                refresh(at)
            }
            InputAction.MOVE -> {
                val history = contacts[pointer] ?: return false
                val previous = history.last()
                val previousTail = tailFor(pointer)
                while (history.isNotEmpty() && at - history.first().time > policy.gestureHistoryUs) history.removeFirst()
                history.add(Sample(at, contact))
                if (contact.lane != null && traceNotes.isNotEmpty()) {
                    val first = traceNotes.binarySearch { if (it.time < at - maxTraceLate) -1 else 1 }.inv()
                    var swept = false
                    for (i in first until traceNotes.size) {
                        val note = traceNotes[i]
                        if (note.time >= at) break
                        if (at - note.time > note.window.late || note.id in settled) continue
                        if (note.span.accepts(contact, policy.spatialExpansion)) {
                            settle(note, note.window.rating(at - note.time), at, pointer, "TRACE_MOVE")
                        } else if (!swept && previous.contact.lane != null) {
                            val laneX = TraceSweep.laneAt(note.time, previous.time, at,
                                previous.contact.laneX, contact.laneX) ?: continue
                            if (laneX >= note.span.left && laneX < note.span.right) {
                                settle(note, Rating.PERFECT, note.time, pointer, "TRACE_SWEEP")
                                swept = true
                            }
                        }
                    }
                }
                refresh(at)
                val tail = (tailFor(pointer) ?: previousTail?.takeIf {
                    !recoverFlickTail || releaseClaims.path(pointer) == it.pathId
                })?.takeIf {
                    it.action == Action.FLICK && it.window.accepts(at - it.time)
                }
                val immediate = tail ?: armed[pointer]
                val origins = if (recoverFlickTail && immediate == null)
                    releaseClaims.flickOrigins(pointer, at, policy.gestureHistoryUs) else null
                val event = immediate ?: (if (origins != null) releaseClaims.path(pointer)?.let(tails::get)
                    ?.takeIf { it.action == Action.FLICK } else null) ?: return true
                if (origins != null && event.id in deferred) return true
                if (event.id in settled || !event.window.accepts(at - event.time)) {
                    diagnostics?.record("FLICK_REJECT", at, event.id, pointer, if (event.id in settled) "EVENT_ALREADY_RESOLVED"
                        else if (at < event.time) "TOO_EARLY" else "TOO_LATE")
                    return true
                }
                val rating = history.asSequence().filter { origins == null || it.time in origins }
                    .mapNotNull { gesture(it, contact, at, event, pointer) }.maxOrNull() ?: return true
                val grade = minOf(rating, event.window.rating(at - event.time))
                if (origins != null) diagnostics?.record("FLICK_TAIL_HISTORY", at, event.id, pointer, grade.name)
                accept(event, grade, at, pointer)
                if (!event.tail) {
                    armed.remove(pointer)
                    history.clear()
                    history.add(Sample(at, contact))
                    refresh(at)
                }
            }
            InputAction.UP -> {
                if (pointer !in contacts) return false
                val owned = tailFor(pointer)
                val historical = if (recoverRelease && owned == null && pointer !in armed)
                    releaseClaims.path(pointer)?.let(tails::get)?.takeIf { it.action == Action.RELEASE } else null
                val event = owned ?: historical
                val releaseContact = if (recoverRelease && contact.releaseLane != contact.lane)
                    contact.copy(lane = contact.releaseLane) else contact
                val reason = if (event == null) "NO_OWNED_TAIL" else when {
                    event.action != Action.RELEASE -> "FLICK_REQUIRES_MOVE"
                    event.id in settled -> "EVENT_ALREADY_RESOLVED"
                    at - event.time < -event.window.early -> "TOO_EARLY"
                    at - event.time > event.window.late -> "TOO_LATE"
                    historical != null && !releaseClaims.canRelease(pointer, at) -> "RELEASE_HISTORY_EXPIRED"
                    recoverRelease && releaseContact.lane == null -> "OUTSIDE_RELEASE_REGION"
                    !event.span.accepts(releaseContact, policy.spatialExpansion) -> "OUT_OF_LANE"
                    historical != null -> "ELIGIBLE_RELEASE_HISTORY"
                    else -> "ELIGIBLE_RELEASE"
                }
                diagnostics?.record(if (event == null) "RELEASE_UNASSIGNED" else "TAIL_INPUT", at, event?.id, pointer, reason)
                if (event != null && (reason == "ELIGIBLE_RELEASE" || reason == "ELIGIBLE_RELEASE_HISTORY"))
                    accept(event, event.window.rating(at - event.time), at, pointer)
                contacts.remove(pointer)
                releaseClaims.discard(pointer)
                armed.remove(pointer)
                refresh(at)
            }
        }
        return true
    }

    private fun traceDown(contact: Contact, at: Long, pointer: Int, reserved: Boolean): Event? =
        traceNotes.asSequence().filter { it.window.accepts(at - it.time) }.filter {
            val reason = when {
                it.id in settled -> "EVENT_ALREADY_RESOLVED"
                contact.lane == null -> "NO_LOGICAL_LANE"
                !it.span.accepts(contact, policy.spatialExpansion) -> "OUT_OF_LANE"
                reserved -> "PRESS_RESERVED"
                else -> null
            }
            diagnostics?.record("TRACE_DOWN_CANDIDATE", at, it.id, pointer, reason ?: "ELIGIBLE")
            reason == null
        }.minWithOrNull(compareBy<Event> { !it.span.includes(contact.lane) }
            .thenBy { abs(at - it.time) }.thenBy { it.time }.thenBy { it.id })

    private fun headAt(contact: Contact, at: Long, pointer: Int): Event? {
        var count = 0
        val choice = heads.asSequence()
            .filter { at >= plan.segment.start || it.time == 0L && it.pathId == null && it.action == Action.PRESS }
            .filter {
            val delta = at - it.time
            val reason = when {
                delta < -it.window.early -> "TOO_EARLY"
                delta > it.window.late -> "TOO_LATE"
                it.id in settled -> "EVENT_ALREADY_RESOLVED"
                it in armed.values -> "POINTER_ALREADY_ASSIGNED"
                contact.lane == null -> "NO_LOGICAL_LANE"
                !it.span.accepts(contact, policy.spatialExpansion) -> "OUT_OF_LANE"
                else -> null
            }
            if (diagnostics != null && abs(delta) <= 250_000)
                diagnostics?.record("PRESS_CANDIDATE", at, it.id, pointer, reason ?: "ELIGIBLE")
            if (reason == null) count++
            reason == null
        }.minWithOrNull(compareBy<Event> { !it.span.includes(contact.lane) }
            .thenBy { abs(requireNotNull(contact.lane) + .5f - it.span.center) }
            .thenBy { abs(at - it.time) }.thenBy { it.time }.thenBy { it.id })
        diagnostics?.record("PRESS_ASSIGN", at, choice?.id, pointer, "candidates=$count")
        return choice
    }

    private fun tailFor(pointer: Int): Event? =
        held.entries.firstOrNull { it.value.owner == pointer }?.key?.let { tails[it] }

    private fun gesture(from: Sample, to: Contact, at: Long, event: Event, pointer: Int): Rating? {
        val elapsed = at - from.time
        val dx = to.x - from.contact.x
        val dy = to.y - from.contact.y
        val distance = hypot(dx, dy)
        val inSpan = event.span.includes(from.contact.lane, policy.pathMargin) ||
            event.span.accepts(from.contact, policy.spatialExpansion)
        val angle = when (event.direction) {
            Direction.LEFT -> -policy.diagonalRadians
            Direction.RIGHT -> policy.diagonalRadians
            else -> null
        }
        val rating = when {
            !inSpan || elapsed <= 0 || distance < policy.minimumDistance ||
                distance * 1_000_000 / elapsed < policy.minimumSpeed -> null
            event.direction == Direction.OMNI -> Rating.PERFECT
            angle == null -> null
            else -> {
                val difference = atan2(dx.toDouble(), -dy.toDouble()) - angle
                if (abs(atan2(sin(difference), cos(difference))) <= policy.directionTolerance) Rating.PERFECT
                else Rating.GREAT
            }
        }
        if (diagnostics != null) diagnostics?.record("FLICK_SAMPLE", at, event.id, pointer,
            if (!inSpan) "reason=OUT_OF_LANE dx=$dx dy=$dy dtUs=$elapsed"
            else gestureDetail(dx, dy, elapsed, distance, policy.minimumDistance, policy.minimumSpeed,
                rating?.name, event.direction.toString()))
        return rating
    }

    private fun accept(event: Event, rating: Rating, at: Long, pointer: Int) {
        if (!event.tail || at >= event.time) {
            settle(event, rating, at, pointer, if (event.tail) "TAIL" else "FLICK")
            return
        }
        val result = Outcome(event.id, rating, at - event.time, pointer)
        val old = deferred[event.id]?.outcome
        if (old == null || rating > old.rating || (rating == old.rating && result.deltaUs > old.deltaUs))
            deferred[event.id] = Candidate(event, result)
        diagnostics?.record("TAIL_BUFFER", at, event.id, pointer, rating.name)
    }

    fun advance(to: Long) {
        val limit = minOf(to, plan.segment.end - 1)
        if (limit <= watermark) return
        while (true) {
            val tick = ticks.getOrNull(tickIndex)
            val deadline = deadlines.getOrNull(deadlineIndex)
            val expiry = deadline?.let { it.time + it.window.late + 1 } ?: Long.MAX_VALUE
            val sample = if (held.isEmpty()) Long.MAX_VALUE else nextContact
            val early = deferred.values.minWithOrNull(compareBy({ it.event.time }, { it.event.id }))
            val opening = openings.getOrNull(openingIndex)?.time ?: Long.MAX_VALUE
            val time = minOf(tick?.time ?: Long.MAX_VALUE, expiry, sample, early?.event?.time ?: Long.MAX_VALUE, opening)
            if (time > limit) break
            if (opening == time) {
                if (held.isEmpty()) nextContact = time + policy.pathStepUs
                while (openings.getOrNull(openingIndex)?.time == time) {
                    val id = requireNotNull(openings[openingIndex++].pathId)
                    if (id !in held) held[id] = Held(null, null)
                }
                refresh(time)
            }
            if (sample == time) {
                refresh(time)
                nextContact = time + policy.pathStepUs
            }
            if (tick?.time == time) {
                tickIndex++
                refresh(time)
                if (tick.action == Action.TRACE) {
                    val pointer = contacts.entries.asSequence()
                        .filter { tick.span.accepts(it.value.last().contact, policy.spatialExpansion) }
                        .map { it.key }.minOrNull()
                    if (tick.id !in settled) {
                        if (pointer != null) settle(tick, Rating.PERFECT, time, pointer, "TRACE_CONTACT")
                        else diagnostics?.record("TRACE_WAIT", time, tick.id, null, "NO_CONTACT_AT_TARGET")
                    }
                } else {
                    val state = held[tick.pathId]
                    val valid = state?.owner != null || state?.lastContact?.let { time - it <= policy.contactGraceUs } == true
                    settle(tick, if (valid) Rating.PERFECT else Rating.MISS, time, state?.owner,
                        if (!valid) "HOLD_NOT_CONTACTED" else if (state?.owner == null) "CONTACT_GRACE" else "CONTACT")
                }
            }
            if (early?.event?.time == time) settle(early.event, early.outcome.rating, time + early.outcome.deltaUs,
                early.outcome.pointer, "BUFFERED_TAIL")
            if (deadline != null && expiry == time) {
                deadlineIndex++
                settle(deadline, Rating.MISS, time, null, "WINDOW_EXPIRED_${deadline.action}")
            }
        }
        watermark = limit
    }

    private fun refresh(at: Long) {
        val reserved = hashSetOf<Int>()
        fun fits(pointer: Int, id: String): Boolean {
            val lane = contacts[pointer]?.lastOrNull()?.contact?.lane
            return plan.paths.getValue(id).spanAt(at).includes(lane, policy.pathMargin)
        }
        for ((id, state) in held) {
            val owner = state.owner ?: continue
            if (fits(owner, id) && reserved.add(owner)) state.lastContact = at
            else {
                if (recoverRelease || recoverFlickTail) releaseClaims.lose(owner, id, at)
                state.owner = null
                record(at, "detach", id, owner)
            }
        }
        for ((id, state) in held) {
            if (state.owner != null) continue
            val pointer = contacts.keys.firstOrNull { it !in reserved && it !in armed && fits(it, id) } ?: continue
            reserved.add(pointer)
            state.owner = pointer
            state.lastContact = at
            if (recoverRelease || recoverFlickTail) releaseClaims.acquire(pointer, id, at)
            record(at, "attach", id, pointer)
        }
        contactObserver?.invoke(at, readContactOwner)
    }

    private fun settle(event: Event, rating: Rating, at: Long, pointer: Int?, reason: String) {
        if (event.id in settled) return
        settled[event.id] = Outcome(event.id, rating, at - event.time, pointer)
        diagnostics?.record("SETTLE", at, event.id, pointer, "grade=$rating reason=$reason")
        if (diagnostics != null && event.pathId != null && (event.tail || event.action == Action.TICK)) {
            val path = plan.paths.getValue(event.pathId)
            val state = held[path.id]
            val span = path.spanAt(at)
            diagnostics?.record("LONG_STATE", at, event.id, state?.owner,
                "chain=${path.id} head=${settled[headByPath[path.id]?.id]?.rating} " +
                    "node=${path.nodeAt(at)} span=${span.left}:${span.right} lastContact=${state?.lastContact} tail=${event.tail}")
        }
        record(maxOf(at, event.time), rating.name, event.id, pointer)
        armed.entries.removeAll { it.value.id == event.id }
        deferred.remove(event.id)
        if (event.tail) {
            held.remove(event.pathId)
            if (recoverRelease || recoverFlickTail) event.pathId?.let(releaseClaims::finish)
        }
        points += rating.points
        combo = if (rating == Rating.MISS) 0 else combo + 1
        val left = pending[event.objectId] ?: return
        val grade = minOf(rating, worst[event.objectId] ?: Rating.PERFECT)
        if (left == 1) {
            completed[event.objectId] = ObjectOutcome(event.objectId, objects.getValue(event.objectId).last().id, grade)
            pending.remove(event.objectId)
            worst.remove(event.objectId)
        } else {
            pending[event.objectId] = left - 1
            worst[event.objectId] = grade
        }
    }

    private fun record(at: Long, kind: String, id: String, pointer: Int? = null) {
        if (kind == "attach" || kind == "detach")
            diagnostics?.record(kind.uppercase(), at, id, pointer, "lastContact=${held[id]?.lastContact}")
        if (trace.size == 64) trace.removeFirst()
        trace.addLast(TraceEntry(at, kind, id, pointer))
    }
}
