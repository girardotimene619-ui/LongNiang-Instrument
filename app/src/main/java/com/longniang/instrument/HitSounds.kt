package com.longniang.instrument

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicLong

private const val HIT_LOG_TAG = "LongNiangHit"

internal data class SoundTiming(val inputNs: Long?, val engineReturnedNs: Long)
internal enum class HitSoundBackend(val label: String) {
    PCM("PCM Mixer（单轨 PCM）"), SOUND_POOL("SoundPool（旧方案）")
}

internal data class HitBackendStatus(val backend: HitSoundBackend? = null, val phase: String = "IDLE") {
    val actual: HitSoundBackend? get() = backend.takeIf { phase == "READY" }
    val description: String get() = actual?.label ?: when (phase) {
        "LOADING" -> "正在准备 ${backend?.label.orEmpty()}"
        "MUTED" -> "未运行（静音）"
        "IDLE" -> "未运行"
        else -> "未运行（$phase）"
    }
}

/** Assets and native playback are owned by a worker. Timestamps are software measurements only. */
internal class HitSounds(context: Context) {
    private val app = context.applicationContext
    private val worker = HandlerThread("HitSounds", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    private var pcm: HitPcmOutput? = null
    private val ids = IntArray(HitCue.entries.size)
    private val streams = IntArray(12)
    private var cursor = 0
    private var completedLoads = 0
    private var failedLoads = 0
    private var preloadWaiter: (() -> Unit)? = null
    @Volatile var backendStatus = HitBackendStatus()
        private set
    @Volatile var requestedBackend = HitSoundBackend.PCM
    @Volatile var group = HitSoundGroup.ALL
    @Volatile var volume = .65f
        private set
    private val epoch = AtomicLong()
    @Volatile private var closed = false
    private val seen = hashSetOf<String>()
    private var seenEpoch = -1L
    private var duplicates = 0
    private var late = 0
    private var slowQueue = 0
    private var rejected = 0
    @Volatile private var last: Submission? = null

    private data class Submission(
        val event: HitEvent, val backend: HitSoundBackend, val timing: SoundTiming, val requested: Long, val submitted: Long,
        val returned: Long, val status: String, val queuedFrames: Long?, val duplicates: Int, val late: Int, val slowQueue: Int, val rejected: Int
    )

    val wantsSustain: Boolean get() = !closed && volume > 0f && backendStatus.actual == HitSoundBackend.PCM &&
        (group == HitSoundGroup.ALL || group == HitSoundGroup.TICK)

    private val resources = intArrayOf(R.raw.hit_tap, R.raw.hit_critical, R.raw.hit_flick,
        R.raw.hit_head, R.raw.hit_tick, R.raw.hit_tail, R.raw.hit_critical_flick,
        R.raw.hit_critical_head, R.raw.hit_critical_tick, R.raw.hit_critical_tail, R.raw.hit_flick_tail,
        R.raw.hit_trace, R.raw.hit_critical_trace)

    private fun prepare(backend: HitSoundBackend) {
        backendStatus = HitBackendStatus(backend, "LOADING")
        releaseOutput()
        try {
            if (backend == HitSoundBackend.PCM) {
                val output = pcm ?: run {
                    val recordings = intArrayOf(R.raw.sample_tap, R.raw.sample_flick, R.raw.sample_trace, R.raw.sample_long)
                    val samples = Array(recordings.size) { index ->
                        app.resources.openRawResource(recordings[index]).use { readHitWave(it.readBytes()) }
                    }
                    val gains = FloatArray(samples.size) { HitPcmLevels.sampleGain(HitSample.entries[it], samples[it]) }
                    val silence = shortArrayOf(0)
                    val clips = Array(HitCue.entries.size) { i ->
                        HitSampleMix.sample(HitCue.entries[i])?.let { samples[it.ordinal] } ?: silence
                    }
                    val cueGains = FloatArray(clips.size) { i ->
                        val cue = HitCue.entries[i]
                        HitSampleMix.sample(cue)?.let { gains[it.ordinal] * HitSampleMix.cueGain(cue) } ?: 0f
                    }
                    val mixer = HitPcmMixer(clips, capacity = 48, clipGains = cueGains,
                        sustain = samples[HitSample.LONG.ordinal], sustainGain = gains[HitSample.LONG.ordinal] * HitSampleMix.SUSTAIN_GAIN)
                    HitPcmOutput(handler, mixer) { finishLoad("FAILED_OUTPUT") }.also { pcm = it }
                }
                output.setSustainVolume(volume)
                output.start()
                finishLoad("READY")
            } else {
                completedLoads = 0
                failedLoads = 0
                val sounds = SoundPool.Builder().setMaxStreams(streams.size).setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
                pool = sounds
                sounds.setOnLoadCompleteListener { owner, _, status ->
                    if (pool === owner) loadComplete(status == 0)
                }
                resources.forEachIndexed { i, res ->
                    ids[i] = sounds.load(app, res, 1)
                    if (ids[i] == 0) loadComplete(false)
                }
                handler.postDelayed({
                    if (pool === sounds && backendStatus.phase == "LOADING") finishLoad("FAILED_TIMEOUT")
                }, 5_000)
            }
        } catch (e: Exception) {
            android.util.Log.w("LongNiangHit", "SFX preload failed: $backend", e)
            finishLoad("FAILED_INIT")
        }
    }

    private fun loadComplete(ok: Boolean) {
        if (backendStatus.phase != "LOADING") return
        completedLoads++
        if (!ok) failedLoads++
        if (completedLoads == ids.size) finishLoad(if (failedLoads == 0) "READY" else "FAILED_ASSET")
    }

    private fun finishLoad(state: String) {
        val status = backendStatus.copy(phase = state)
        backendStatus = status
        android.util.Log.i("LongNiangHit", "actualBackend=${status.actual ?: "NONE"} " +
            "preparedBackend=${status.backend} group=$group preload=$state")
        val waiter = preloadWaiter
        preloadWaiter = null
        if (waiter != null && !closed) main.post { if (!closed) waiter() }
    }

    fun awaitPreload(onReady: () -> Unit): Boolean {
        val status = backendStatus
        if (volume <= 0f && status.backend == null) return false
        if (volume > 0f && status.actual == requestedBackend) return false
        handler.post {
            if (!closed) {
                preloadWaiter = onReady
                val backend = requestedBackend
                val current = backendStatus
                when {
                    volume <= 0f -> suspendOutput()
                    current.backend != backend || current.phase.startsWith("FAILED") -> prepare(backend)
                    current.actual != null -> finishLoad("READY")
                }
            }
        }
        return true
    }

    fun setVolume(value: Float, resumeSound: Boolean) {
        val wasMuted = volume <= 0f
        if (closed || volume == value) return
        volume = value
        if (wasMuted == (value <= 0f)) {
            handler.post { pcm?.setSustainVolume(volume) }
            return
        }
        if (value <= 0f) {
            epoch.incrementAndGet()
            handler.post { if (!closed && volume <= 0f) suspendOutput() }
        } else if (resumeSound) {
            handler.post {
                if (!closed && volume > 0f && backendStatus.actual != requestedBackend) prepare(requestedBackend)
                pcm?.setSustainVolume(volume)
            }
        }
    }

    fun sustain(id: String, active: Boolean) {
        if (closed) return
        val token = epoch.get()
        val requested = System.nanoTime()
        handler.post {
            if (closed || epoch.get() != token || (active && !wantsSustain)) return@post
            val submitted = System.nanoTime()
            val output = pcm ?: return@post
            val accepted = output.setSustain(id, active)
            if (!accepted) android.util.Log.w("LongNiangHit", "sustain=$active path=$id rejected")
            if (android.util.Log.isLoggable(HIT_LOG_TAG, android.util.Log.DEBUG))
                android.util.Log.d(HIT_LOG_TAG, "sustain=$active path=$id accepted=$accepted " +
                    "requestNs=$requested submitNs=$submitted queuedFrames=${output.softwareQueuedFrames}")
        }
    }

    private fun suspendOutput() {
        releaseOutput()
        backendStatus = HitBackendStatus()
        finishLoad("MUTED")
    }

    fun play(event: HitEvent, timing: SoundTiming) {
        if (closed || volume <= 0f || backendStatus.actual == null || !group.includes(event.cue)) return
        if (backendStatus.actual == HitSoundBackend.PCM && HitSampleMix.sample(event.cue) == null) return
        val token = epoch.get()
        val requested = System.nanoTime()
        handler.post {
            val gain = volume
            if (closed || epoch.get() != token || gain <= 0f || !group.includes(event.cue)) return@post
            val backend = backendStatus.actual ?: return@post
            val submitted = System.nanoTime()
            if (submitted - requested > 8_000_000L) slowQueue++
            if (seenEpoch != token) {
                seen.clear()
                seenEpoch = token
            }
            val status = when {
                !seen.add(event.id) -> { duplicates++; "DUPLICATE" }
                submitted - requested > 100_000_000 -> { late++; "EXPIRED" }
                else -> {
                    val tick = event.cue == HitCue.TICK || event.cue == HitCue.CRITICAL_TICK
                    val priority = if (tick) 0 else 1
                    val submittedHit = if (backend == HitSoundBackend.PCM)
                        pcm?.trigger(event, gain, priority, submitted) == true
                    else {
                        val comparisonGain = gain * .52f
                        val stream = pool?.play(ids[event.cue.ordinal], comparisonGain, comparisonGain,
                            priority, 0, event.rate) ?: 0
                        if (stream != 0) {
                            streams[cursor] = stream
                            cursor = (cursor + 1) % streams.size
                        }
                        stream != 0
                    }
                    if (submittedHit) {
                        "ACCEPTED"
                    } else {
                        rejected++
                        "REJECTED"
                    }
                }
            }
            last = Submission(event, backend, timing, requested, submitted, System.nanoTime(), status,
                pcm?.softwareQueuedFrames.takeIf { backend == HitSoundBackend.PCM },
                duplicates, late, slowQueue, rejected)
            if (android.util.Log.isLoggable(HIT_LOG_TAG, android.util.Log.DEBUG))
                android.util.Log.d(HIT_LOG_TAG, diagnostics().replace('\n', ' '))
        }
    }

    fun diagnostics(): String {
        val status = backendStatus
        val prefix = "SFX actualBackend=${status.actual ?: "NONE"} group=$group bank=${if (status.actual == HitSoundBackend.SOUND_POOL) "legacy" else "recordings"} preload=${status.phase}"
        val s = last ?: return "$prefix · 尚无提交"
        fun us(a: Long, b: Long) = (a - b) / 1_000
        return "$prefix · submissionBackend=${s.backend} · ${s.event.cue} · g=${s.event.generation} · ${s.event.id} · ${s.status}" +
            " · targetChartUs=${s.event.targetUs} feedbackChartUs=${s.event.observedChartUs}" +
            " · chartDelta=${s.event.observedChartUs - s.event.targetUs}us\n" +
            "sampleNs=${s.timing.inputNs ?: "--"} engineReturnNs=${s.timing.engineReturnedNs} requestNs=${s.requested} submitNs=${s.submitted}\n" +
            "sample→engine=${s.timing.inputNs?.let { us(s.timing.engineReturnedNs, it) } ?: "--"}us · engine→request=${us(s.requested, s.timing.engineReturnedNs)}us" +
            " · queue=${us(s.submitted, s.requested)}us · submitCall=${us(s.returned, s.submitted)}us" +
            " · lastPumpQueuedFrames=${s.queuedFrames ?: "--"}" +
            " · duplicate=${s.duplicates} queueOver8ms=${s.slowQueue} expired=${s.late} rejected=${s.rejected}"
    }

    fun reset() {
        epoch.incrementAndGet()
        handler.post {
            last = null
            pcm?.resetVoices()
            streams.forEach { if (it != 0) pool?.stop(it) }
            streams.fill(0)
        }
    }

    fun end() {
        epoch.incrementAndGet()
        backendStatus = HitBackendStatus()
        handler.post {
            preloadWaiter = null
            releaseOutput()
            backendStatus = HitBackendStatus()
            android.util.Log.i("LongNiangHit", "actualBackend=NONE preload=IDLE · released")
        }
    }

    private fun releaseOutput() {
        pcm?.takeIf { it.started }?.let {
            it.close()
            android.util.Log.i("LongNiangHit", "releasedBackend=PCM")
        }
        pool?.let {
            it.release()
            android.util.Log.i("LongNiangHit", "releasedBackend=SOUND_POOL")
        }
        pool = null
        streams.fill(0)
    }

    fun release() {
        closed = true
        handler.post {
            preloadWaiter = null
            releaseOutput()
            worker.quitSafely()
        }
    }
}
