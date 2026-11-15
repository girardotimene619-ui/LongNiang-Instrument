package com.longniang.instrument.sus

import com.longniang.instrument.Tap
import java.security.MessageDigest
import java.util.Collections

enum class ChartNoteKind { TAP, FLICK, HOLD, SLIDE, UNRESOLVED }

data class ChartNote(
    val id: String,
    val timeUs: Long,
    val kind: ChartNoteKind,
    val left: Int?,
    val width: Int?,
    val critical: Boolean?,
    val source: SusOccurrence
)

class Chart internal constructor(
    val id: String,
    metadata: List<SusRecord>,
    notes: List<ChartNote>,
    diagnostics: List<SusDiagnostic>,
    val columns: Int = 4,
    rawRecords: List<SusRecord> = emptyList(),
    sources: List<ChartSource> = emptyList(),
    objects: List<ChartObject> = emptyList(),
    tempoSegments: List<TempoSegment> = emptyList()
) {
    internal val tempoSegments = immutableChartList(tempoSegments)
    val metadata = immutableChartList(metadata)
    /** Legacy per-record view used by the Tap-only adapter. */
    val notes = immutableChartList(notes)
    val diagnostics = immutableChartList(diagnostics)
    val rawRecords = immutableChartList(rawRecords)
    val sources = immutableChartList(sources)
    val objects = immutableChartList(objects)
    val unresolvedSources: List<ChartSource>

    init {
        val assigned = hashSetOf<String>()
        for (objectSource in this.objects.flatMap { it.sources }) {
            check(assigned.add(objectSource.id)) { "Multiple owners of ${objectSource.id}" }
        }
        unresolvedSources = immutableChartList(this.sources.filterNot { it.id in assigned })
    }
}

object NoteResolver {
    private const val MERGE_IDENTICAL_ORDINARY_TAPS_BY_DEFAULT = true

    fun resolve(bytes: ByteArray,
                mergeIdenticalOrdinaryTaps: Boolean = MERGE_IDENTICAL_ORDINARY_TAPS_BY_DEFAULT): Chart {
        val source = bytes.copyOf()
        val controlled = source.contentEquals(ControlledTapChart.bytes())
        val timing = SusTimeline.analyze(source)
        val id = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        val diagnostics = timing.diagnostics.toMutableList()
        val patched = timing.raw.records.any {
            it.text.trim() ==
                "This chart is created for Project Sekai: Colorful Stage, and patched as playable SUS with PjskSUSPatcher."
        }
        val columns = if (patched) 12 else 4
        val associatedBeats = timing.events.filter { it.occurrence.channel.first() in "2359" }
            .mapTo(hashSetOf()) { it.exactBeat }
        for (record in timing.raw.records.filter { it.key == "WAVEOFFSET" }) {
            if (record.value?.trim()?.toBigDecimalOrNull()?.signum() != 0)
                diagnostics += SusDiagnostic(record.line, "UNSUPPORTED_OFFSET", "Only zero WAVEOFFSET is supported", true)
        }
        val sources = timing.events.filter { it.occurrence.channel != "08" }.map { event ->
            val raw = event.occurrence
            val left = raw.channel.getOrNull(1)?.digitToIntOrNull(36)?.minus(if (patched) 2 else 0)
            val width = raw.token.getOrNull(1)?.digitToIntOrNull(36)
            val interval = if (left != null && left in 0 until columns &&
                width != null && width in 1..(columns - left)) LaneInterval(left, width) else null
            ChartSource("$id:${raw.source.line}:${raw.index}", event, interval)
        }
        val notes = sources.map { sourceRecord ->
            val event = sourceRecord.timing
            val raw = event.occurrence
            val encodedLane = raw.channel.getOrNull(1)?.digitToIntOrNull(36)
            val width = raw.token.getOrNull(1)?.digitToIntOrNull(36)
            val fixtureTap = controlled && raw.channel.matches(Regex("1[0-3]")) && raw.token == "11"
            val ordinaryTap = patched && raw.channel.length == 2 && raw.channel.first() == '1' &&
                raw.token.first() == '1' && event.exactBeat !in associatedBeats &&
                sourceRecord.interval != null
            val tap = fixtureTap || ordinaryTap
            if (!patched && !tap) diagnostics += SusDiagnostic(raw.source.line, "UNRESOLVED_NOTE",
                "Unverified channel or note semantics: ${raw.channel}/${raw.token}", true)
            ChartNote(sourceRecord.id, event.timeUs,
                if (tap) ChartNoteKind.TAP else ChartNoteKind.UNRESOLVED,
                if (tap) sourceRecord.interval?.left else encodedLane,
                width,
                if (tap) false else null, raw)
        }
        val objects = when {
            patched -> PatchedChartObjects.resolve(sources, diagnostics, mergeIdenticalOrdinaryTaps)
            controlled -> sources.map { InstantChartObject("single:${it.id}", it, ChartModifiers(), listOf(it)) }
            else -> emptyList()
        }
        return Chart(id, timing.raw.records.filter { it.key != null && it.key.first().isLetter() },
            notes, diagnostics, columns, timing.raw.records, sources, objects, timing.tempoSegments)
    }
}

data class TapAdaptation(val notes: List<Tap>, val diagnostics: List<SusDiagnostic>, val columns: Int = 4) {
    val playable: Boolean get() = notes.isNotEmpty() && diagnostics.none { it.error }
}

object TapChartAdapter {
    fun adapt(chart: Chart): TapAdaptation {
        val diagnostics = chart.diagnostics.map {
            if (it.code.startsWith("UNVERIFIED_")) it.copy(error = true) else it
        }.toMutableList()
        val absorbedSources = chart.objects.asSequence().filterIsInstance<InstantChartObject>()
            .filter { it.modifiers == ChartModifiers() }
            .flatMap { obj -> obj.sources.asSequence().filter { it.id != obj.subject.id } }
            .mapTo(hashSetOf()) { it.id }
        val notes = chart.notes.filterNot { it.id in absorbedSources }.mapNotNull { note ->
            if (note.kind != ChartNoteKind.TAP || note.critical != false ||
                note.left == null || note.left !in 0 until chart.columns ||
                note.width == null || note.width !in 1..(chart.columns - note.left) || note.timeUs < 0) {
                diagnostics += SusDiagnostic(note.source.source.line, "TAP_INCOMPATIBLE",
                    "Unsupported kind, modifier, position or width for ${note.id}", true)
                null
            } else Tap(note.id, note.timeUs, note.left, note.width)
        }
        if (notes.isEmpty()) diagnostics += SusDiagnostic(null, "NO_PLAYABLE_TAPS", "No compatible Tap notes", true)
        return TapAdaptation(
            if (diagnostics.any { it.error }) emptyList() else Collections.unmodifiableList(notes),
            Collections.unmodifiableList(diagnostics), chart.columns)
    }
}

internal object ControlledTapChart {
    fun bytes() = """
        #TITLE "M3-2 Controlled Tap"
        #00002:4
        #BPM01:120
        #00008:01
        #00110:11
        #00111:00110000
        #00112:00001100
        #00113:00001100
        #00210:11
    """.trimIndent().toByteArray(Charsets.UTF_8)
}
