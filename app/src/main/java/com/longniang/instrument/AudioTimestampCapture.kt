package com.longniang.instrument

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors

internal data class TimestampPoll(
    val player: Long, val track: Long, val generation: Long, val revision: Long, val rate: Int,
    val queryStartNs: Long, val queryEndNs: Long, val frame: Long?, val timestampNs: Long?,
    val head: Long?, val routeId: Int?, val routeType: Int?,
    val previousAcceptedFrame: Long?, val previousAcceptedNs: Long?,
    val phaseBefore: ObservationPhase, val phaseAfter: ObservationPhase,
    val accepted: Boolean, val reason: String?
)

/** Called only by the file writer. Deltas never span track changes or failed raw polls. */
internal class TimestampCsv {
    private var previous: TimestampPoll? = null
    private var firstJump = false
    var sequence = 0
        private set

    fun append(sample: TimestampPoll): Pair<String, Boolean> {
        val old = previous?.takeIf {
            it.player == sample.player && it.track == sample.track && it.generation == sample.generation
        }
        previous = sample
        val frame = sample.frame?.and(0xffffffffL)
        val priorFrame = old?.frame?.and(0xffffffffL)
        val trigger = !firstJump && sample.phaseBefore == ObservationPhase.RUNNING &&
            sample.phaseAfter == ObservationPhase.FAILED && sample.reason == "FRAME_TIME_JUMP"
        if (trigger) firstJump = true
        val observerFrameDelta = frame?.let { n -> sample.previousAcceptedFrame?.let { n - (it and 0xffffffffL) } }
        val observerNsDelta = sample.timestampNs?.let { n -> sample.previousAcceptedNs?.let { n - it } }
        return listOf(
            ++sequence, sample.player, sample.track, sample.generation, sample.revision, sample.rate,
            sample.queryStartNs, sample.queryEndNs, sample.frame != null, sample.frame, sample.timestampNs,
            frame?.let { n -> priorFrame?.let { n - it } },
            sample.timestampNs?.let { n -> old?.timestampNs?.let { n - it } },
            sample.head, sample.routeId, sample.routeType, sample.previousAcceptedFrame, sample.previousAcceptedNs,
            observerFrameDelta, observerNsDelta, sample.phaseBefore, sample.phaseAfter, sample.accepted,
            sample.reason, trigger
        ).joinToString(",") { it?.toString().orEmpty() } to trigger
    }

    companion object {
        const val HEADER = "seq,player,track,generation,revision,sample_rate,query_start_ns,query_end_ns," +
            "raw_ok,frame_position,nano_time,delta_frames,delta_ns,playback_head,route_id,route_type," +
            "previous_accepted_frame,previous_accepted_ns,observer_delta_frames,observer_delta_ns," +
            "phase_before,phase_after,accepted,rejection,first_frame_time_jump"
    }
}

/** Debug-only recorder. Producers enqueue immutable samples; all storage runs on the writer thread. */
internal class AudioTimestampCapture(private val directory: File) {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "AudioTimestampCapture") }
    private class Run(val file: File) {
        val csv = TimestampCsv()
        val beforeJump = ArrayDeque<String>(32)
        val incidentFile = File(file.parentFile, "${file.nameWithoutExtension}-jump.csv")
        var remainingAfterJump = 0
        var jumpSeen = false
        var queued = 0
        var stream: FileOutputStream? = null
        var text: BufferedWriter? = null
    }
    @Volatile private var active: Run? = null
    @Volatile var status = "未开始"
        private set
    private var closed = false
    val isRecording get() = active != null

    fun fileNamed(name: String): File? = if (name.matches(Regex("audiotrack-[0-9]+-[0-9]+(-jump)?[.]csv")))
        File(directory, name).takeIf { it.isFile } else null

    @Synchronized fun start() {
        if (closed || active != null) return
        val file = File(directory, "audiotrack-${System.currentTimeMillis()}-${System.nanoTime()}.csv")
        val run = Run(file)
        active = run
        status = "正在采集 · ${file.name}"
        writer.execute {
            write(run) {
                directory.mkdirs()
                run.stream = FileOutputStream(file)
                run.text = run.stream!!.bufferedWriter().apply {
                    appendLine(TimestampCsv.HEADER)
                    flush()
                }
            }
        }
    }

    @Synchronized fun record(sample: TimestampPoll) {
        val run = active ?: return
        writer.execute {
            write(run) {
                val text = run.text ?: return@write
                val (line, trigger) = run.csv.append(sample)
                text.appendLine(line)
                text.flush()
                if (trigger) run.stream?.fd?.sync()
                writeIncident(run, line, trigger)
            }
        }
        // 6000 polls at the existing game cadence cover about ten minutes per capture.
        if (++run.queued >= 6000) stop()
    }

    @Synchronized fun stop() {
        val run = active ?: return
        active = null
        status = "正在保存 · ${run.file.name}"
        writer.execute {
            write(run) {
                if (run.text == null) return@write
                run.text?.flush()
                run.stream?.fd?.sync()
                closeFile(run)
                if (active == null) status = "已保存 · ${run.file.name}"
            }
        }
    }

    @Synchronized fun latest(incidentOnly: Boolean = false, callback: (File?) -> Unit) {
        if (closed) return
        stop()
        writer.execute {
            val full = directory.listFiles()?.filter { it.extension == "csv" && !it.name.endsWith("-jump.csv") }
                ?.maxByOrNull { it.name }
            callback(if (incidentOnly && full != null) fileNamed("${full.nameWithoutExtension}-jump.csv") else full)
        }
    }

    @Synchronized fun close() {
        stop()
        closed = true
        writer.shutdown()
    }

    private fun write(run: Run, action: () -> Unit) {
        try { action() }
        catch (error: IOException) {
            closeFile(run)
            synchronized(this) {
                if (active == run || active == null) {
                    active = null
                    status = "采集写入失败 · ${error.message}"
                }
            }
        }
    }

    private fun closeFile(run: Run) {
        try { run.text?.close() ?: run.stream?.close() }
        catch (_: IOException) { /* Preserve the original write error. */ }
        finally { run.text = null; run.stream = null }
    }

    private fun writeIncident(run: Run, line: String, trigger: Boolean) {
        if (!run.jumpSeen && !trigger) {
            if (run.beforeJump.size == 32) run.beforeJump.removeFirst()
            run.beforeJump.addLast(line)
            return
        }
        if (!trigger && run.remainingAfterJump == 0) return
        if (trigger) {
            run.jumpSeen = true
            run.remainingAfterJump = 32
        }
        try {
            FileOutputStream(run.incidentFile, !trigger).use { stream ->
                val out = stream.bufferedWriter()
                if (trigger) {
                    out.appendLine(TimestampCsv.HEADER)
                    run.beforeJump.forEach { out.appendLine(it) }
                }
                out.appendLine(line)
                out.flush()
                if (trigger || run.remainingAfterJump == 1) stream.fd.sync()
            }
        } catch (error: IOException) {
            // The full CSV has already kept this row.
            android.util.Log.w("LongNiangAudio", "Failed to write timestamp incident window", error)
            run.remainingAfterJump = 0
        } finally {
            if (trigger) run.beforeJump.clear()
            else if (run.remainingAfterJump > 0) run.remainingAfterJump--
        }
    }
}
