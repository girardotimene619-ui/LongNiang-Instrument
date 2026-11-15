package com.longniang.instrument

import com.longniang.instrument.sus.FlickDirection
import com.longniang.instrument.sus.JudgmentAction
import com.longniang.instrument.sus.LaneSpan
import com.longniang.instrument.sus.LongNoteKind

internal enum class HitCue {
    TAP, CRITICAL, FLICK, HEAD, TICK, TAIL, CRITICAL_FLICK, CRITICAL_HEAD, CRITICAL_TICK, CRITICAL_TAIL, FLICK_TAIL, TRACE, CRITICAL_TRACE
}

private fun MixedAction.flashCue(): HitCue = when {
    event.action == JudgmentAction.FLICK -> HitCue.FLICK
    event.action == JudgmentAction.TRACE -> if (critical) HitCue.CRITICAL_TRACE else HitCue.TRACE
    event.action == JudgmentAction.TICK -> HitCue.TICK
    isTail -> HitCue.TAIL
    path != null -> HitCue.HEAD
    critical -> HitCue.CRITICAL
    else -> HitCue.TAP
}

internal fun MixedAction.soundCue(): HitCue {
    val cue = flashCue()
    return when {
        cue == HitCue.FLICK && critical -> HitCue.CRITICAL_FLICK
        cue == HitCue.FLICK && isTail -> HitCue.FLICK_TAIL
        cue == HitCue.HEAD && critical -> HitCue.CRITICAL_HEAD
        cue == HitCue.TICK && critical -> HitCue.CRITICAL_TICK
        cue == HitCue.TAIL && critical -> HitCue.CRITICAL_TAIL
        else -> cue
    }
}

internal data class HitEvent(
    val id: String, val generation: Long, val cue: HitCue, val targetUs: Long, val observedChartUs: Long,
    val sliding: Boolean = false
) {
    val rate: Float get() = if (!sliding) 1f else when (cue) {
        HitCue.HEAD, HitCue.CRITICAL_HEAD -> 1.08f
        HitCue.TICK, HitCue.CRITICAL_TICK -> 1.12f
        HitCue.TAIL, HitCue.CRITICAL_TAIL -> .95f
        else -> 1f
    }
}

internal data class HitFlash(
    val span: LaneSpan, val grade: Grade, val cue: HitCue, val critical: Boolean,
    val direction: FlickDirection?, val bornUs: Long
) {
    val durationUs get() = if (cue == HitCue.TICK) 145_000L else 245_000L
    fun remaining(at: Long): Float =
        if (at < bornUs) 0f else (1f - (at - bornUs).toFloat() / durationUs).coerceAtLeast(0f)
}

/** Presentation state shared by both playback adapters. Mutated under the session lock. */
internal class NoteFeedback {
    private var generation = Long.MIN_VALUE
    private var mixedResults = 0
    private var pending: MutableMap<String, Int>? = null
    private val seen = mutableMapOf<String, Grade>()
    private val flashes = ArrayDeque<HitFlash>()
    var combo = 0
        private set

    fun reset(generation: Long, playback: MixedPlayback? = null) {
        this.generation = generation
        mixedResults = 0
        pending = playback?.let(::objectCounts)
        seen.clear()
        flashes.clear()
        combo = 0
    }

    fun collect(playback: MixedPlayback, generation: Long, nowUs: Long, onHit: (HitEvent) -> Unit) {
        if (generation != this.generation || mixedResults == playback.results.size) return
        val remaining = pending ?: objectCounts(playback).also { pending = it }
        for ((id, outcome) in playback.results) {
            if (seen.putIfAbsent(id, outcome.grade) != null) continue
            val action = outcome.action
            val objectId = action.event.objectId
            val count = remaining[objectId]
            if (count != null) {
                if (outcome.grade == Grade.MISS) combo = 0
                if (count > 1) remaining[objectId] = count - 1
                else {
                    remaining.remove(objectId)
                    if (playback.objectResults[objectId]?.grade?.let { it != Grade.MISS } == true) combo++
                }
            }
            if (outcome.grade == Grade.MISS) continue
            emit(HitFlash(action.span, outcome.grade, action.flashCue(), action.critical, action.direction, nowUs))
            onHit(HitEvent(id, generation, action.soundCue(), action.at, action.at + outcome.deltaUs, action.path?.kind == LongNoteKind.SLIDE))
        }
        mixedResults = playback.results.size
    }

    private fun objectCounts(playback: MixedPlayback) = playback.actions.asSequence()
        .filter { it.path == null || it.path.end < playback.endUs }
        .groupingBy { it.event.objectId }.eachCount().toMutableMap()

    fun tap(note: Tap, grade: Grade, generation: Long, nowUs: Long, onHit: (HitEvent) -> Unit) {
        if (generation != this.generation || seen.put(note.id, grade) == grade) return
        if (grade == Grade.MISS) combo = 0
        else {
            combo++
            emit(HitFlash(LaneSpan(note.lane.toFloat(), (note.lane + note.width).toFloat()),
                grade, HitCue.TAP, false, null, nowUs))
            onHit(HitEvent(note.id, generation, HitCue.TAP, note.timeUs, nowUs))
        }
    }

    private fun emit(flash: HitFlash) {
        while (flashes.size >= 32) flashes.removeFirst()
        flashes.addLast(flash)
    }

    fun visible(at: Long) = flashes.filter { it.remaining(at) > 0f }
    fun prune(at: Long) { flashes.removeAll { at >= it.bornUs + it.durationUs } }
    fun clearFlashes() = flashes.clear()
}
