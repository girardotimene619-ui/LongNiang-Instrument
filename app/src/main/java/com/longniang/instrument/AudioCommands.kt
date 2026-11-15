package com.longniang.instrument

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

enum class AudioCommandKind { PLAY, PAUSE, STOP, RELEASE }
data class AudioCommand(val id: Long, val kind: AudioCommandKind, val requestedNs: Long, val generation: Long? = null)
/** FIFO actually used by the owner thread; release closes admission, not earlier queued commands. */
class AudioCommandQueue {
    private val queue = LinkedBlockingQueue<AudioCommand>()
    private var nextId = 0L
    private var closed = false
    @Synchronized fun submit(kind: AudioCommandKind, nowNs: Long, generation: Long? = null): AudioCommand? {
        if (closed) return null
        if (kind == AudioCommandKind.RELEASE) closed = true
        return AudioCommand(++nextId, kind, nowNs, generation).also { queue.offer(it) }
    }
    fun poll(timeoutMs: Long): AudioCommand? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
}

/** Cleanup ordering shared by the real owner path and JVM exception-path tests. */
internal fun releaseAudioResource(readStats: () -> Unit, pauseAndFlush: () -> Unit, release: () -> Unit) {
    try { readStats() } finally { try { pauseAndFlush() } finally { release() } }
}
