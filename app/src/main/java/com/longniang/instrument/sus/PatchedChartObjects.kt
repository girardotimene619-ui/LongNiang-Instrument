package com.longniang.instrument.sus

/**
 * Resolves patched SUS subjects and modifiers before constructing judgment events.
 * Attachment uses exact beat and lane interval. Source order identifies records for
 * diagnostics; it must not decide which record wins an ambiguous attachment.
 */
internal object PatchedChartObjects {
    private val nodeRoles = mapOf(
        '1' to PathRole.START, '2' to PathRole.END,
        '3' to PathRole.RELAY, '5' to PathRole.INVISIBLE
    )
    private val directions = mapOf(
        '1' to FlickDirection.UP, '3' to FlickDirection.UPPER_LEFT, '4' to FlickDirection.UPPER_RIGHT
    )

    private data class MatchKey(val beat: String, val interval: LaneInterval)
    private data class Companions(
        val ordinary: ChartSource?, val critical: ChartSource?, val direction: ChartSource?
    ) {
        val sources get() = listOfNotNull(ordinary, critical, direction)
        val tap get() = critical ?: ordinary

        fun modifiers() = ChartModifiers(
            criticalSource = critical,
            direction = direction?.let { directions.getValue(it.type) },
            directionSource = direction
        )
    }

    private val ChartSource.channel get() = timing.occurrence.channel
    private val ChartSource.type get() = timing.occurrence.token.first()
    private val ChartSource.isNode get() = channel.length == 3 && channel.first() == '3'

    private val ChartSource.isTrace get() =
        channel.length == 2 && channel.first() == '1' && type in "56"

    fun resolve(sources: List<ChartSource>, diagnostics: MutableList<SusDiagnostic>,
                mergeIdenticalOrdinaryTaps: Boolean): List<ChartObject> {
        val invalid = hashSetOf<String>()
        fun reject(rows: List<ChartSource>, code: String, reason: String) {
            for (row in rows) {
                invalid += row.id
                diagnostics += SusDiagnostic(row.line, code, "Slot ${row.slot}: $reason", true)
            }
        }
        val guides = sources.filter { it.channel.length == 3 && it.channel.first() == '9' }
        val guideIds = guides.mapTo(hashSetOf()) { it.id }
        val guideObjects = mutableListOf<GuideChartObject>()
        for ((channel, records) in guides.groupBy { it.channel.last() }) {
            val chain = mutableListOf<ChartSource>()
            for (row in records) {
                chain += row
                if (row.type != '2') continue
                if (chain.size >= 2 && chain.first().type == '1' &&
                    chain.drop(1).dropLast(1).all { it.type == '5' } &&
                    chain.all { it.interval != null } &&
                    chain.zipWithNext().all { (a, b) -> a.timing.timeUs < b.timing.timeUs }) {
                    val nodes = chain.map { PathNode(it, nodeRoles.getValue(it.type), ChartModifiers(), listOf(it)) }
                    guideObjects += GuideChartObject("guide:${chain.first().id}", channel, nodes)
                } else reject(chain, "INVALID_GUIDE_CHAIN", "Invalid Guide start, anchor, end or interval")
                chain.clear()
            }
            if (chain.isNotEmpty()) reject(chain, "UNCLOSED_GUIDE_CHAIN", "Guide channel $channel has no end")
        }

        fun supported(row: ChartSource): Boolean = when {
            row.interval == null -> false
            row.isNode -> row.type in nodeRoles
            row.channel.length != 2 -> false
            row.channel.first() == '1' -> row.type == '1' || row.type == '2' || row.isTrace
            row.channel.first() == '5' -> row.type in directions
            else -> false
        }

        val objects = mutableListOf<ChartObject>().apply { addAll(guideObjects) }
        val attachments = mutableMapOf<String, Companions>()
        for (row in sources.filter { it.id !in guideIds && !supported(it) }) {
            reject(listOf(row), "UNRESOLVED_NOTE",
                "Unsupported record ${row.channel}/${row.timing.occurrence.token} or lane interval")
        }
        val groups = sources.filter { it.interval != null && it.id !in guideIds }
            .groupBy { MatchKey(it.timing.exactBeat, requireNotNull(it.interval)) }
        val byBeatAndStart = sources.groupBy { it.timing.exactBeat to it.channel.getOrNull(1) }
        val independentTraces = hashSetOf<String>()
        for (trace in sources.filter { it.isTrace && it.interval != null }) {
            val peers = byBeatAndStart.getValue(trace.timing.exactBeat to trace.channel[1])
            if (peers.any { it !== trace && it.channel.first() != '9' }) {
                reject(listOf(trace), "UNSUPPORTED_TRACE_ASSOCIATION",
                    "Trace shares its beat and start lane with another gameplay record")
            } else {
                independentTraces += trace.id
                objects += TraceChartObject("trace:${trace.id}", trace, trace.type == '6')
            }
        }
        for (group in groups.values) {
            val rows = group.filterNot { it.id in independentTraces }
            if (rows.isEmpty()) continue
            if (rows.any { it.id in invalid }) {
                reject(rows.filterNot { it.id in invalid }, "UNVERIFIED_COMPANION",
                    "Unsupported record shares the exact beat and interval")
                continue
            }
            val ordinary = rows.filter { it.channel.first() == '1' && it.type == '1' }
            val critical = rows.filter { it.channel.first() == '1' && it.type == '2' }
            val directional = rows.filter { it.channel.first() == '5' }
            val nodes = rows.filter { it.isNode }
            if (nodes.isEmpty() && directional.isEmpty()) {
                // Standalone Tap deduplication does not apply to competing modifiers.
                val ordinaryGroups = if (mergeIdenticalOrdinaryTaps)
                    ordinary.groupBy { it.timing.exactTimeUs }.values else ordinary.map(::listOf)
                for (sameTap in ordinaryGroups) {
                    val primary = sameTap.first()
                    objects += InstantChartObject("single:${primary.id}", primary, ChartModifiers(), sameTap)
                    if (sameTap.size > 1) {
                        val locations = sameTap.joinToString { "${it.line}:${it.slot}" }
                        diagnostics += SusDiagnostic(primary.line, "IDENTICAL_ORDINARY_TAPS_MERGED",
                            "Ordinary Tap sources $locations share one playable event.", false)
                    }
                }
                critical.forEach { objects += instant(it, Companions(null, it, null)) }
                continue
            }
            val modifiers = Companions(ordinary.singleOrNull(), critical.singleOrNull(), directional.singleOrNull())
            if (ordinary.size > 1 || critical.size > 1 || directional.size > 1 ||
                (ordinary.isNotEmpty() && critical.isNotEmpty() && directional.isEmpty()) ||
                nodes.size > 1) {
                reject(rows, "AMBIGUOUS_NOTE_ASSOCIATION", "Competing subjects or modifiers at the exact beat and interval")
                continue
            }
            if (nodes.isEmpty()) {
                val tap = modifiers.tap
                if (tap == null) reject(rows, "ORPHAN_DIRECTION", "No matching Tap or path node")
                else objects += instant(tap, modifiers)
                continue
            }
            for (node in nodes) {
                val allowed = modifiers.sources.isEmpty() || when (nodeRoles.getValue(node.type)) {
                    PathRole.START -> modifiers.direction == null
                    PathRole.END -> modifiers.direction != null
                    else -> false
                }
                if (allowed) attachments[node.id] = modifiers
                else reject(rows, "UNVERIFIED_NODE_MODIFIER", "Unsupported path node modifier combination")
            }
        }

        // SusTimeline has already sorted these records by exact beat and source position.
        for ((channel, nodes) in sources.filter { it.isNode }.groupBy { it.channel.last() }) {
            val coincident = nodes.groupBy { it.timing.exactBeat }.values
                .filter { it.size > 1 }.flatten().mapTo(hashSetOf()) { it.id }
            val pending = mutableListOf<ChartSource>()
            for (node in nodes) {
                pending += node
                if (node.type != '2') continue
                val valid = pending.size >= 2 && pending.first().type == '1' &&
                    pending.drop(1).dropLast(1).all { it.type == '3' || it.type == '5' } &&
                    pending.none { it.id in invalid || it.id in coincident }
                if (valid) {
                    val path = pending.map { source ->
                        val local = attachments.getValue(source.id)
                        PathNode(source, nodeRoles.getValue(source.type), local.modifiers(),
                            listOf(source) + local.sources)
                    }
                    objects += LongChartObject("chain:${pending.first().id}", channel,
                        LongNoteKind.UNCLASSIFIED, path)
                } else {
                    reject(chainSources(pending, attachments), "INVALID_PATH_CHAIN",
                        "Channel $channel has conflicting nodes or an invalid start–end sequence")
                }
                pending.clear()
            }
            if (pending.isNotEmpty()) {
                reject(chainSources(pending, attachments), "UNCLOSED_PATH_CHAIN", "Channel $channel has no end")
            }
        }
        val bySubject = objects.associateBy { it.subject.id }
        return sources.mapNotNull { bySubject[it.id] }
    }

    private fun chainSources(nodes: List<ChartSource>, attachments: Map<String, Companions>) =
        nodes.flatMap { node -> listOf(node) + attachments[node.id]?.sources.orEmpty() }

    private fun instant(tap: ChartSource, companions: Companions) =
        InstantChartObject("single:${tap.id}", tap, companions.modifiers(), companions.sources)
}
