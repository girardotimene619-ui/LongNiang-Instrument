package com.longniang.instrument

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/** Reads only immutable/volatile publications. Never takes the game lock. */
internal class FreezeWatchdog(
    private val directory: File, private val trace: FreezeTrace,
    private val sample: (Long) -> FreezeSample?, private val detail: () -> String
) {
    private val worker = HandlerThread("FreezeWatchdog").apply { start() }
    private val handler = Handler(worker.looper)
    private val detector = FreezeDetector()
    private val poll = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val current = sample(now)
            if (current == null) detector.clear()
            else detector.observe(current)?.let { save(it) }
            handler.postDelayed(this, 50)
        }
    }

    init { handler.post(poll) }

    fun capture() { handler.post { save("MANUAL") } }

    private fun save(reason: String) {
        try {
            directory.mkdirs()
            val file = File(directory, "freeze-${System.currentTimeMillis()}-${System.nanoTime()}.txt")
            val header = detail()
            val lines = trace.lines()
            file.bufferedWriter().use { out ->
                out.appendLine(reason)
                out.appendLine(header)
                for (line in lines) out.appendLine(line)
            }
            Log.w("LongNiangFreeze", "$reason ${file.name}\n$header")
        } catch (error: java.io.IOException) {
            Log.e("LongNiangFreeze", "Unable to write freeze capture", error)
        }
    }

    fun close() {
        handler.removeCallbacks(poll)
        worker.quitSafely()
    }
}
