package com.longniang.instrument

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One opted-in run. Producer never performs disk I/O or waits for the writer. */
internal class PlayCapture(private val directory: File, capacity: Int = 16_384) {
    private val queue = ArrayBlockingQueue<List<Any?>>(capacity)
    private val name = "play-${System.currentTimeMillis()}-${UUID.randomUUID()}.csv"
    val id = name.removeSuffix(".csv")
    @Volatile private var stopping = false
    @Volatile private var lost = 0L
    @Volatile private var statusText = "正在记录"
    val status get() = "$statusText · 丢失 $lost 行"
    val isRecording get() = !stopping

    private val worker = Thread(::write, "PlayCapture").apply { start() }

    fun row(kind: String, generation: Long?, revision: Long?, chartUs: Long?, monoNs: Long?,
            id: String?, pointer: Int?, vararg fields: Any?) {
        if (stopping) return
        val row = listOf(kind, this.id, generation, revision, chartUs, monoNs, id, pointer) + fields
        if (!queue.offer(row)) lost++
    }

    fun stop() { stopping = true }
    fun await() = worker.join()

    private fun write() {
        val partial = File(directory, "$name.partial")
        try {
            check(directory.isDirectory || directory.mkdirs()) { "无法创建诊断目录" }
            partial.outputStream().buffered(65_536).use { output ->
                output.write("kind,session,generation,revision,chart_us,mono_ns,id,pointer,fields\n".toByteArray())
                var rows = 0
                var bytes = 0L
                var capped = 0L
                while (!stopping || queue.isNotEmpty()) {
                    val row = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    val line = row.joinToString(",", postfix = "\n", transform = ::csv).toByteArray()
                    if (bytes + line.size > 32L * 1024 * 1024) capped++
                    else {
                        output.write(line)
                        bytes += line.size
                        if (++rows % 512 == 0) output.flush()
                    }
                }
                output.write("SUMMARY,${csv(id)},,,,,,,written=$rows,queueLost=$lost,fileLimitLost=$capped\n".toByteArray())
                lost += capped
            }
            check(partial.renameTo(File(directory, name))) { "无法提交诊断文件" }
            directory.listFiles { file -> file.extension == "csv" && file.name.startsWith("play-") }
                ?.sortedByDescending { it.name }?.drop(4)?.forEach { it.delete() }
            statusText = "已保存"
        } catch (error: IOException) {
            statusText = "保存失败：${error.message}"
        } catch (error: IllegalStateException) {
            statusText = "保存失败：${error.message}"
        } finally {
            stopping = true
        }
    }

    private fun csv(value: Any?): String = when (value) {
        null -> ""
        is Number -> value.toString()
        else -> "\"${value.toString().replace("\"", "\"\"")}\""
    }
}
