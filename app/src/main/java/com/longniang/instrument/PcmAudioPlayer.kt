package com.longniang.instrument

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack

internal data class AudioProgress(val generation: Long, val trackId: Long, val sampledNs: Long,
                                  val rawOk: Boolean?, val rawFrame: Long?, val rawNs: Long?, val headFrame: Long?)


/** One owner thread, non-blocking writes, bounded reusable PCM chunk; controls never write audio on UI. */
/** Streams song PCM on its worker and publishes observations of AudioTrack presentation. */
internal class PcmAudioPlayer(private val gameMode: Boolean = false,
    private val source: PcmSource = TestPcmSource, private val offsetUs: Long = 0,
    private val loop: PcmLoop? = if (gameMode) PcmLoop.practice else null,
    private val timestampCapture: AudioTimestampCapture? = null, initialVolume: Float = 1f) {
    companion object { private val playerIds = java.util.concurrent.atomic.AtomicLong() }
    private val playerId = playerIds.incrementAndGet()

    private val commands = AudioCommandQueue()
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    @Volatile var isPlaying = false; private set
    @Volatile var pending = false; private set
    private var latestRequestId = 0L
    @Volatile var requestedKind: AudioCommandKind? = null; private set
    @Volatile var diagnostics = "PCM尚未初始化（与Tap独立）"; private set
    @Volatile var reading = AudioClockReading.idle(0); private set
    @Volatile var progress: AudioProgress? = null; private set
    @Volatile var diagnosticRoute: String? = null; private set
    @Volatile private var musicVolume = initialVolume
    fun setMusicVolume(value: Float) { musicVolume = value.coerceIn(0f, 1f) }
    fun workerState(): String = worker.state.name
    @Synchronized private fun send(kind: AudioCommandKind, generation: Long? = null) {
        if (closed) return
        val now = System.nanoTime()
        val cmd = commands.submit(kind, now, generation) ?: return
        latestRequestId = cmd.id; requestedKind = kind; pending = true
        reading = reading.frozen(now, "REQUEST_${kind}")
        if (kind == AudioCommandKind.RELEASE) closed = true
        android.util.Log.i("LongNiangAudio", "request player=$playerId id=${cmd.id} kind=${cmd.kind} ns=${cmd.requestedNs}")
    }
    @Synchronized fun toggle() {
        val wantsPlay = if (pending) requestedKind == AudioCommandKind.PLAY else isPlaying
        send(if (wantsPlay) AudioCommandKind.PAUSE else AudioCommandKind.PLAY)
    }
    fun play() = send(AudioCommandKind.PLAY)
    @Synchronized fun restart(generation: Long) {
        send(AudioCommandKind.STOP)
        send(AudioCommandKind.PLAY, generation)
    }
    fun pause() = send(AudioCommandKind.PAUSE)
    fun stop() = send(AudioCommandKind.STOP)
    fun release() = send(AudioCommandKind.RELEASE)
    /** Call off the UI thread after release before deleting a file used by this player. */
    fun awaitRelease() = worker.join()
    @Synchronized private fun acknowledge(id: Long, playing: Boolean, value: AudioClockReading) {
        isPlaying = playing
        if (id == latestRequestId) {
            pending = false
            if (!closed) reading = value
        }
    }
    @Synchronized private fun publish(value: AudioClockReading) {
        if (!pending && !closed) reading = value
    }
    private val worker = Thread({ runWorker() }, "PcmAudioWorker").apply { start() }
    private fun runWorker() {
        var track: AudioTrack? = null
        var clock = AudioObservationClock(PcmSignal.sampleRate)
        var generation = 0L
        var playing = false
        var state = "STOPPED"
        var bufferBytes = 0
        var minBytes = 0
        var writtenSamples = 0L
        var chunkSamples = 0
        var chunkOffset = 0
        var nextPollNs = 0L
        var lastDisplayNs = 0L
        var lastLogNs = 0L
        var released = false
        var lastUnderruns = 0
        var actualBufferFrames = 0
        var appliedVolume = Float.NaN
        val chunk = ShortArray(960 * 2) // 20ms, reused. Short samples, not bytes.
        val timestamp = AudioTimestamp()
        var trackId = 0L
        var rawResult: Boolean? = null
        var rawFrame: Long? = null
        var rawNs: Long? = null
        fun stopTrack(why: String) {
            playing = false
            val old = track; track = null
            try {
                releaseAudioResource(
                    { if (old != null) { lastUnderruns = old.underrunCount; actualBufferFrames = old.bufferSizeInFrames } },
                    { old?.pause(); old?.flush() },
                    { old?.release() }
                )
            } finally {
                if (why == "ENDED_RELEASED") clock.finish(System.nanoTime(), SampleTime.us(source.totalFrames, PcmSignal.sampleRate))
                else clock.invalidate(System.nanoTime(), why)
                state = why; chunkSamples = 0; chunkOffset = 0; isPlaying = false
            }
        }
        try {
            while (!released) {
                val cmd = commands.poll(if (playing) 2 else 40)
                if (cmd != null) android.util.Log.i("LongNiangAudio", "begin id=${cmd.id} ns=${System.nanoTime()} thread=${Thread.currentThread().id} track=$playerId/$trackId g=$generation")
                when (cmd?.kind) {
                    AudioCommandKind.PLAY -> {
                        if (track == null) {
                            minBytes = AudioTrack.getMinBufferSize(PcmSignal.sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                            check(minBytes > 0) { "getMinBufferSize=$minBytes" }
                            bufferBytes = ((maxOf(minBytes * 2, 19_200) + 3) / 4) * 4 // >=100ms, frame-aligned
                            val newTrack = AudioTrack.Builder()
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(PcmSignal.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bufferBytes).build()
                            track = newTrack; trackId++
                            rawResult = null; rawFrame = null; rawNs = null; timestamp.framePosition = 0; timestamp.nanoTime = 0
                            check(newTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack init failed" }
                            check(newTrack.sampleRate == PcmSignal.sampleRate) { "Actual sample rate mismatch" }
                            appliedVolume = musicVolume
                            if (appliedVolume != 1f) newTrack.setVolume(appliedVolume)
                            clock = AudioObservationClock(newTrack.sampleRate, offsetUs, policy = if (gameMode)
                                ObservationPolicy(maxAgeNs = 2_000_000_000L) else ObservationPolicy())
                            generation = cmd.generation?.also { check(it > generation) } ?: (generation + 1)
                            clock.start(System.nanoTime(), generation)
                            writtenSamples = 0; chunkOffset = 0
                            val frames = loop?.chunkFrames(0, 960) ?: minOf(960L, source.totalFrames).toInt()
                            chunkSamples = frames * 2
                            source.fill(chunk, loop?.startFrame ?: 0, frames)
                            val prefilled = newTrack.write(chunk, 0, chunkSamples, AudioTrack.WRITE_NON_BLOCKING)
                            check(prefilled >= 0) { "Prefill write error=$prefilled" }
                            chunkOffset = prefilled; writtenSamples = prefilled.toLong()
                            lastUnderruns = 0; actualBufferFrames = newTrack.bufferSizeInFrames
                        } else if (!playing) clock.resume(System.nanoTime())
                        track!!.play(); playing = true; state = "PLAYING"; nextPollNs = 0
                    }
                    AudioCommandKind.PAUSE -> if (playing) {
                        track?.pause(); clock.pause(System.nanoTime()); playing = false; state = "PAUSED"
                    }
                    AudioCommandKind.STOP -> stopTrack("STOPPED_RELEASED")
                    AudioCommandKind.RELEASE -> { stopTrack("RELEASED"); released = true }
                    null -> Unit
                }
                if (cmd != null) {
                    acknowledge(cmd.id, playing, clock.reading(System.nanoTime(), state))
                    lastDisplayNs = 0
                    android.util.Log.i("LongNiangAudio", "end id=${cmd.id} ns=${System.nanoTime()} track=$playerId/$trackId g=$generation state=$state")
                }
                val active = track
                if (playing && active != null) {
                    val volume = musicVolume
                    if (appliedVolume != volume) {
                        active.setVolume(volume)
                        appliedVolume = volume
                    }
                    if (chunkOffset >= chunkSamples && (loop != null || writtenSamples / 2 < source.totalFrames)) {
                        val outputFrame = writtenSamples / 2
                        val frames = loop?.chunkFrames(outputFrame, 960) ?: minOf(960L, source.totalFrames - outputFrame).toInt()
                        source.fill(chunk, loop?.sourceFrame(outputFrame) ?: outputFrame, frames)
                        chunkSamples = frames * 2; chunkOffset = 0
                    }
                    if (chunkOffset < chunkSamples) {
                        val n = active.write(chunk, chunkOffset, chunkSamples - chunkOffset, AudioTrack.WRITE_NON_BLOCKING)
                        check(n >= 0) { "AudioTrack.write error=$n" }
                        chunkOffset += n; writtenSamples += n // Retain partial writes; no regeneration.
                    }
                    val now = System.nanoTime()
                    if (now >= nextPollNs) {
                        val token = clock.snapshot(now).token
                        val capture = timestampCapture?.takeIf { it.isRecording }
                        val phaseBefore = clock.phase
                        val previousFrame = if (capture != null) clock.lastFrame else null
                        val previousNs = if (capture != null) clock.lastTimestampNs else null
                        val queryStartNs = if (capture != null) System.nanoTime() else 0L
                        rawResult = active.getTimestamp(timestamp)
                        val queryEndNs = if (capture != null) System.nanoTime() else 0L
                        val accepted = if (rawResult == true) {
                            rawFrame = timestamp.framePosition and 0xffffffffL; rawNs = timestamp.nanoTime
                            clock.observe(AudioObservation(timestamp.framePosition and 0xffffffffL, timestamp.nanoTime, active.sampleRate, token), System.nanoTime())
                        } else {
                            clock.unavailable(System.nanoTime())
                            false
                        }
                        if (!clock.snapshot(System.nanoTime()).canJudge || now - lastLogNs >= 10_000_000_000L) {
                            android.util.Log.i("LongNiangAudio", "ns=${System.nanoTime()} thread=${Thread.currentThread().id} track=$playerId/$trackId g=$generation rawOk=$rawResult rawFrame=$rawFrame rawNs=$rawNs accepted=${clock.lastFrame} phase=${clock.phase} reject=${clock.lastRejection} state=${clock.snapshot(System.nanoTime()).state}")
                            lastLogNs = now
                        }
                        nextPollNs = now + if (gameMode || !clock.snapshot(System.nanoTime()).canJudge) 100_000_000L else 10_000_000_000L
                        if (capture != null) {
                            val route = try { active.routedDevice } catch (_: IllegalStateException) { null }
                            val head = try { active.playbackHeadPosition.toLong() and 0xffffffffL }
                                catch (_: IllegalStateException) { null }
                            capture.record(TimestampPoll(playerId, trackId, token.generation, token.revision,
                                active.sampleRate, queryStartNs, queryEndNs,
                                timestamp.framePosition.takeIf { rawResult == true },
                                timestamp.nanoTime.takeIf { rawResult == true }, head, route?.id, route?.type,
                                previousFrame, previousNs, phaseBefore, clock.phase, accepted,
                                clock.lastRejection.takeUnless { accepted }))
                        }
                    }
                    // playbackHead is used ONLY to detect finite test completion, never as a clock fallback.
                    if (loop == null && writtenSamples / 2 >= source.totalFrames &&
                        (active.playbackHeadPosition.toLong() and 0xffffffffL) >= source.totalFrames) stopTrack("ENDED_RELEASED")
                }
                val now = System.nanoTime()
                publish(clock.reading(now, state))
                if (now - lastDisplayNs >= 200_000_000L) {
                    if (timestampCapture != null) diagnosticRoute = try {
                        track?.routedDevice?.let { "${it.id}/${it.type}" }
                    } catch (_: IllegalStateException) { null }
                    if (track != null) lastUnderruns = track!!.underrunCount
                    val s = clock.snapshot(now)
                    val headFrame = track?.playbackHeadPosition?.toLong()?.and(0xffffffffL)
                    progress = AudioProgress(generation, trackId, now, rawResult, rawFrame, rawNs, headFrame)
                    isPlaying = playing
                    diagnostics = "PCM：${source.totalFrames} frames · 48kHz · PCM16 LE · Stereo\n" +
                        "AudioTrack: $state / ${track?.playState ?: "released"} · id=$playerId/$trackId · worker=${Thread.currentThread().id}\n" +
                        "minBuffer=$minBytes B / request=$bufferBytes B / actual=$actualBufferFrames frames\n" +
                        "written=${writtenSamples / 2} frames（非呈现时钟） · underrun=$lastUnderruns\n" +
                        "rawOk=$rawResult · rawFrame=$rawFrame · rawNs=$rawNs（false时为前次原始值）\n" +
                        "accepted/presentedFrame=${clock.lastFrame ?: "--"} · acceptedNs=${clock.lastTimestampNs ?: "--"}\n" +
                        "timestampAgeMs=${clock.lastTimestampNs?.let { (now - it) / 1_000_000 } ?: "--"}\n" +
                        "loop=${loop?.iteration(s.chartTimeUs) ?: "--"} · A/B=${loop?.startFrame}/${loop?.endFrame} frames\n" +
                        "output=${AudioOffset.audioUs(s.chartTimeUs, clock.audioOffsetUs)} us · chart=${s.chartTimeUs} us\n" +
                        "${s.state}/${clock.phase} · timestampValid=${s.canJudge}\n" +
                        "建立耗时ms=${clock.establishmentNs?.div(1_000_000) ?: "--"} · falseCount=${clock.falseCount}\n" +
                        "lastReject=${clock.lastRejection} · preTimeout=${clock.preTimeoutRejection} · playbackHead=${headFrame ?: "--"}（诊断）\n" +
                        "publishedNs=$now · pending=$pending\n" +
                        "g/r=${s.token.generation}/${s.token.revision} · residual=${clock.residualUs ?: "--"} us\n" +
                        "${s.reason ?: if (gameMode) "音频时钟模式" else "独立观测（未接管Tap）"}\n" +
                        "PCM output"
                    lastDisplayNs = now
                }
            }
        } catch (e: Exception) {
            try { stopTrack("ERROR") } catch (_: Exception) { }
            diagnostics = "音频错误：${e.javaClass.simpleName}: ${e.message}；请关闭并重新打开页面重建。"
        } finally {
            try { track?.release() } finally {
                synchronized(this) {
                    reading = clock.reading(System.nanoTime(), state).frozen(System.nanoTime(), state)
                    closed = true; isPlaying = false; pending = false
                }
            }
        }
    }
}






