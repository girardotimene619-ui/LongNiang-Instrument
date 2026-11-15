package com.longniang.judgment

import kotlin.math.abs

data class Segment(val start: Long, val end: Long) {
    init { require(start >= 0 && end > start) }
    fun contains(time: Long) = time in start until end
}

data class Span(val left: Float, val right: Float) {
    val center get() = (left + right) * .5f
    fun includes(lane: Int?, margin: Float = 0f): Boolean {
        val position = (lane ?: return false) + .5f
        return position >= left - margin && position < right + margin
    }
    fun accepts(contact: Contact, expansion: Float): Boolean = includes(contact.lane) ||
        (expansion > 0 && contact.lane != null && contact.laneX >= left - (right - left) * expansion / 2 &&
            contact.laneX < right + (right - left) * expansion / 2)
}

data class PathAnchor(val time: Long, val span: Span)

class HoldPath(val id: String, anchors: List<PathAnchor>) {
    private val anchors = anchors.toList()
    val start get() = anchors.first().time
    internal fun nodeAt(time: Long) = anchors.indexOfLast { it.time <= time }

    fun spanAt(time: Long): Span {
        val i = anchors.indexOfFirst { it.time >= time }
        if (i == -1) return anchors.last().span
        if (i == 0) return anchors.first().span
        val a = anchors[i - 1]
        val b = anchors[i]
        val f = (time - a.time).toDouble().div(b.time - a.time).toFloat()
        return Span(a.span.left + f * (b.span.left - a.span.left),
            a.span.right + f * (b.span.right - a.span.right))
    }
}

enum class Action { PRESS, FLICK, TICK, RELEASE, TRACE }
enum class Direction { OMNI, LEFT, RIGHT }
enum class Rating(val points: Int) { MISS(0), GOOD(1), GREAT(2), PERFECT(3) }

data class Window(val early: Long, val late: Long, val perfect: Long, val great: Long) {
    fun accepts(delta: Long) = delta in -early..late
    fun rating(delta: Long) = when {
        !accepts(delta) -> Rating.MISS
        abs(delta) <= perfect -> Rating.PERFECT
        abs(delta) <= great -> Rating.GREAT
        else -> Rating.GOOD
    }
}

data class Event(
    val id: String, val objectId: String, val time: Long, val span: Span,
    val action: Action, val window: Window, val direction: Direction? = null,
    val pathId: String? = null, val tail: Boolean = false
)

class Plan(events: List<Event>, paths: List<HoldPath>, val segment: Segment) {
    internal val paths = paths.associateBy { it.id }
    val events = events.filter { segment.contains(it.time) &&
        (it.pathId == null || this.paths.getValue(it.pathId).start >= segment.start) }
        .sortedWith(compareBy({ it.time }, { it.id }))
    init { require(this.events.distinctBy { it.id }.size == this.events.size) }
}

data class Contact(val lane: Int?, val x: Float, val y: Float, val laneX: Float = x, val releaseLane: Int? = lane)
enum class InputAction { DOWN, MOVE, UP }
data class Input(val action: InputAction, val pointer: Int, val time: Long, val contact: Contact)
data class Outcome(val id: String, val rating: Rating, val deltaUs: Long, val pointer: Int? = null)
data class ObjectOutcome(val id: String, val displayEvent: String, val rating: Rating)
data class TraceEntry(val time: Long, val kind: String, val id: String, val pointer: Int? = null)

/** Optional evidence from the branch that accepted or rejected a candidate. */
fun interface JudgmentTrace {
    fun record(kind: String, at: Long, eventOrChain: String?, pointer: Int?, detail: String)
}

fun gestureDetail(dx: Float, dy: Float, dt: Long, distance: Float, minDistance: Float,
                  minSpeed: Float, grade: String?, direction: String): String {
    val speed = if (dt > 0) distance * 1_000_000 / dt else null
    val reason = when {
        dt <= 0 -> "NON_POSITIVE_SAMPLE_INTERVAL"
        distance < minDistance -> "FLICK_DISTANCE_TOO_SHORT"
        speed != null && speed < minSpeed -> "FLICK_SPEED_TOO_LOW"
        grade == "GREAT" -> "DIRECTION_DOWNGRADED"
        grade == null -> "GESTURE_REJECTED"
        else -> "ELIGIBLE"
    }
    return "reason=$reason dx=$dx dy=$dy dtUs=$dt distance=$distance speed=$speed " +
        "angle=${kotlin.math.atan2(dx.toDouble(), -dy.toDouble())} target=$direction"
}
