package com.longniang.instrument

// All times are chart microseconds. These parameters are test settings, not PJSk rules.
data class Tap(val id: String, val timeUs: Long, val lane: Int, val width: Int = 1)
enum class Result { PENDING, HIT, MISS }
data class Feedback(val id: String, val result: Result, val deltaUs: Long)
class TapSession(val notes: List<Tap>, val windowUs: Long = 120_000L, initialGeneration: Long = 0, private val columns: Int = 4) {
    val results = mutableMapOf<String, Result>()
    val feedback = mutableListOf<Feedback>()
    private val pointers = mutableSetOf<Int>()
    var generation = initialGeneration; private set
    init {
        require(windowUs > 0)
        require(notes.map { it.id }.distinct().size == notes.size)
        require(columns > 0)
        require(notes.all { it.lane in 0 until columns && it.width in 1..(columns - it.lane) && it.timeUs >= 0 })
        reset()
    }
    fun advance(timeUs: Long) {
        for (n in notes) if (results[n.id] == Result.PENDING && timeUs > n.timeUs + windowUs) {
            results[n.id] = Result.MISS
            feedback.add(Feedback(n.id, Result.MISS, timeUs - n.timeUs))
        }
    }
    fun down(pointer: Int, lane: Int, timeUs: Long, eventGeneration: Long = generation, allowMissCorrection: Boolean = true): Boolean {
        if (eventGeneration != generation || !pointers.add(pointer)) return false
        if (lane !in 0 until columns) return true
        val n = notes.asSequence().filter {
            (results[it.id] == Result.PENDING || (allowMissCorrection && results[it.id] == Result.MISS)) &&
                lane >= it.lane && lane - it.lane < it.width &&
                timeUs >= it.timeUs - windowUs && timeUs <= it.timeUs + windowUs
        }.minWithOrNull(compareBy<Tap> { kotlin.math.abs(timeUs - it.timeUs) }.thenBy { it.timeUs }.thenBy { it.id })
        if (n != null) {
            // A delayed dispatch is judged by its sampling time, even if MISS was already displayed.
            feedback.removeAll { it.id == n.id }
            results[n.id] = Result.HIT
            feedback.add(Feedback(n.id, Result.HIT, timeUs - n.timeUs))
        }
        return true
    }
    fun up(pointer: Int) { pointers.remove(pointer) }
    fun cancel() { pointers.clear() }
    fun reset(nextGeneration: Long = generation + 1) {
        require(nextGeneration > generation)
        generation = nextGeneration; pointers.clear(); results.clear(); feedback.clear()
        notes.forEach { results[it.id] = Result.PENDING }
    }
}

object Geometry {
    fun laneAt(x: Float, width: Float): Int? =
        if (width > 0 && x >= 0 && x < width) (x / (width / 4)).toInt() else null
    fun noteY(targetUs: Long, nowUs: Long, top: Float, line: Float, travelUs: Long): Float =
        line - (targetUs - nowUs).toDouble().div(travelUs).toFloat() * (line - top)
}
object MockChart {
    val notes = listOf(
        Tap("a", 2_000_000, 0), Tap("b", 3_000_000, 3),
        Tap("c", 4_000_000, 1), Tap("d", 4_000_000, 2),
        Tap("e", 5_000_000, 0), Tap("f", 5_300_000, 0),
        Tap("g", 5_600_000, 0), Tap("h", 7_000_000, 3),
        Tap("i", 8_000_000, 0), Tap("j", 8_000_000, 3)
    )
}


