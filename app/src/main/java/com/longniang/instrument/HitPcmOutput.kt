package com.longniang.instrument

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.util.Log

/** All methods and the pump execute on the HitSounds worker. */
internal class HitPcmOutput(
    private val handler: Handler, private val mixer: HitPcmMixer, private val onFailure: () -> Unit
) {
    private val chunk = ShortArray(240 * 2)
    private var track: AudioTrack? = null
    private var limitFrames = 0
    private var offset = chunk.size
    private var written = 0L
    private var route: Int? = null
    private var nextReportNs = 0L
    private var lastUnderruns = -1
    private var pendingFirstSubmitNs = 0L
    private var pendingHits = 0
    private var chunkFirstSubmitNs = 0L
    private var chunkHits = 0

    private val pump = object : Runnable {
        override fun run() {
            val output = track ?: return
            try {
                val head = output.playbackHeadPosition.toLong() and 0xffffffffL
                var attempts = 0
                while (attempts++ < 4 &&
                    (offset < chunk.size || written / 2 - head + chunk.size / 2 <= limitFrames)) {
                    if (offset == chunk.size) {
                        mixer.renderStereo(chunk)
                        offset = 0
                        chunkFirstSubmitNs = pendingFirstSubmitNs
                        chunkHits = pendingHits
                        pendingHits = 0
                    }
                    val size = output.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(size >= 0) { "SFX PCM write returned $size" }
                    if (size == 0) break
                    if (chunkHits > 0) {
                        val acceptedNs = System.nanoTime()
                        Log.d(TAG, "hitChunk hits=$chunkHits firstSubmitNs=$chunkFirstSubmitNs " +
                            "writeAcceptedNs=$acceptedNs firstFrame=${written / 2} " +
                            "playbackHeadBeforeWrite=$head session=${output.audioSessionId}")
                        chunkHits = 0
                    }
                    offset += size
                    written += size
                }
                queuedFrames = (written / 2 - head).coerceAtLeast(0)
                val now = System.nanoTime()
                if (now >= nextReportNs) {
                    val device = output.routedDevice
                    val underruns = output.underrunCount
                    if (route != device?.id || lastUnderruns != underruns) {
                        Log.i(TAG, "session=${output.audioSessionId} route=${device?.id}/${device?.type} " +
                            "queuedFrames=${written / 2 - head} underruns=$underruns")
                        route = device?.id
                        lastUnderruns = underruns
                    }
                    nextReportNs = now + 1_000_000_000L
                }
                handler.postDelayed(this, 3)
            } catch (error: RuntimeException) {
                Log.e(TAG, "SFX output failed", error)
                close()
                onFailure()
            }
        }
    }

    private var queuedFrames = 0L
    val softwareQueuedFrames get() = queuedFrames + (chunk.size - offset) / 2
    val started get() = track != null

    fun start() {
        if (started) return
        val rate = PcmSignal.sampleRate
        val channel = AudioFormat.CHANNEL_OUT_STEREO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val min = AudioTrack.getMinBufferSize(rate, channel, encoding)
        check(min > 0) { "SFX buffer size unavailable: $min" }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(channel).setEncoding(encoding).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(min, 1_920)).build()
        try {
            check(output.state == AudioTrack.STATE_INITIALIZED) { "SFX AudioTrack init failed" }
            limitFrames = minOf(output.bufferSizeInFrames, maxOf(min / 4, 480)).coerceAtLeast(240)
            written = 0
            queuedFrames = 0
            offset = chunk.size
            chunk.fill(0)
            // Silence prefill opens the route before the first hit.
            repeat(limitFrames / (chunk.size / 2)) {
                val size = output.write(chunk, 0, chunk.size, AudioTrack.WRITE_NON_BLOCKING)
                check(size >= 0) { "SFX PCM prefill returned $size" }
                written += size
            }
            output.play()
            track = output
            Log.i(TAG, "started session=${output.audioSessionId} targetFrames=$limitFrames rate=$rate")
            handler.post(pump)
        } catch (error: RuntimeException) {
            output.release()
            throw error
        }
    }

    fun trigger(event: HitEvent, gain: Float, priority: Int, submittedNs: Long): Boolean {
        if (!started || !mixer.trigger(event.cue, gain, 1f, priority)) return false
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            if (pendingHits == 0) pendingFirstSubmitNs = submittedNs
            pendingHits++
        }
        return true
    }

    fun setSustain(id: String, active: Boolean): Boolean = started && mixer.setSustain(id, active)

    fun setSustainVolume(volume: Float) { mixer.sustainVolume = volume }

    fun resetVoices() {
        mixer.clear()
        pendingHits = 0
        chunkHits = 0
        if (offset < chunk.size) chunk.fill(0, offset)
    }

    fun close() {
        handler.removeCallbacks(pump)
        resetVoices()
        val output = track ?: return
        track = null
        route = null
        nextReportNs = 0
        lastUnderruns = -1
        val session = output.audioSessionId
        output.release()
        Log.i(TAG, "released session=$session")
    }

    private companion object { const val TAG = "LongNiangHitPcm" }
}
