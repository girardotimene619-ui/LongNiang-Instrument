package com.longniang.instrument

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal interface PcmSource {
    val totalFrames: Long
    fun fill(output: ShortArray, firstFrame: Long, frames: Int)
}

internal object TestPcmSource : PcmSource {
    override val totalFrames = PcmSignal.totalFrames.toLong()
    override fun fill(output: ShortArray, firstFrame: Long, frames: Int) =
        PcmSignal.fill(output, firstFrame, frames)
}

internal class FilePcmSource(private val file: File, override val totalFrames: Long) : PcmSource {
    private val scratch = ByteArray(960 * 4)
    @Synchronized override fun fill(output: ShortArray, firstFrame: Long, frames: Int) {
        require(firstFrame >= 0 && frames >= 0 && frames <= output.size / 2 && firstFrame <= totalFrames - frames)
        RandomAccessFile(file, "r").use { input ->
            input.seek(firstFrame * 4)
            require(frames * 4 <= scratch.size)
            input.readFully(scratch, 0, frames * 4)
            ByteBuffer.wrap(scratch, 0, frames * 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(output, 0, frames * 2)
        }
    }
}

/** Streaming rational-rate linear interpolation. Input and output use signed PCM16. */
internal class PcmNormalizer(private val rate: Int, private val channels: Int, private val emit: (Int, Int) -> Unit) {
    init { require(rate in 8000..192000 && channels in 1..2) }
    private var sourceFrames = 0L
    var frames = 0L; private set
    private var lastL = 0
    private var lastR = 0

    fun accept(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.remaining() % (channels * 2) == 0) { "Unaligned PCM block" }
        while (buffer.hasRemaining()) {
            val left = buffer.short.toInt()
            val right = if (channels == 2) buffer.short.toInt() else left
            if (sourceFrames == 0L) {
                lastL = left; lastR = right
            }
            while (frames * rate <= sourceFrames * 48_000) {
                val weight = if (sourceFrames == 0L) 48_000 else
                    (frames * rate - (sourceFrames - 1) * 48_000).toInt()
                emit(lastL + ((left - lastL).toLong() * weight / 48_000).toInt(),
                    lastR + ((right - lastR).toLong() * weight / 48_000).toInt())
                frames++
            }
            lastL = left; lastR = right
            sourceFrames++
        }
    }

    fun finish() {
        while (frames * rate < sourceFrames * 48_000) {
            emit(lastL, lastR)
            frames++
        }
    }
}

internal object LocalAudioDecoder {
    fun decode(input: File, output: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.path)
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            require(tracks.size == 1) { "需要且仅允许一条音频轨" }
            val track = tracks.single()
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            return FileOutputStream(output).buffered().use { sink ->
                var normalizer: PcmNormalizer? = null
                fun consume(pcm: ByteBuffer, decoded: MediaFormat) {
                    if (normalizer == null) {
                        val encoding = if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2
                        require(encoding == 2) { "仅支持 PCM16 解码输出" }
                        var count = 0
                        normalizer = PcmNormalizer(decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) { left, right ->
                            require(++count <= 48_000 * 600) { "PCM 超过 10 分钟上限" }
                            sink.write(left and 255); sink.write((left shr 8) and 255)
                            sink.write(right and 255); sink.write((right shr 8) and 255)
                        }
                    }
                    normalizer!!.accept(pcm)
                }
                if (mime == "audio/raw") {
                    val block = ByteBuffer.allocate(256 * 1024)
                    while (true) {
                        block.clear()
                        val size = extractor.readSampleData(block, 0)
                        if (size < 0) break
                        block.position(0); block.limit(size)
                        consume(block, format)
                        extractor.advance()
                    }
                } else {
                    val codec = MediaCodec.createDecoderByType(mime)
                    try {
                        codec.configure(format, null, null, 0)
                        codec.start()
                        val info = MediaCodec.BufferInfo()
                        var eos = false
                        var idle = 0
                        var decodedFormat: MediaFormat? = null
                        while (true) {
                            if (!eos) {
                                val index = codec.dequeueInputBuffer(10_000)
                                if (index >= 0) {
                                    val buffer = requireNotNull(codec.getInputBuffer(index))
                                    val size = extractor.readSampleData(buffer, 0)
                                    eos = size < 0
                                    codec.queueInputBuffer(index, 0, size.coerceAtLeast(0),
                                        if (eos) 0 else extractor.sampleTime,
                                        if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                    if (!eos) extractor.advance()
                                }
                            }
                            val index = codec.dequeueOutputBuffer(info, 10_000)
                            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                check(decodedFormat == null) { "解码输出格式中途变化" }
                                decodedFormat = codec.outputFormat
                            }
                            if (index >= 0) {
                                idle = 0
                                try {
                                    if (info.size > 0) {
                                        val buffer = requireNotNull(codec.getOutputBuffer(index)).duplicate()
                                        buffer.limit(info.offset + info.size); buffer.position(info.offset)
                                        consume(buffer, decodedFormat ?: codec.outputFormat)
                                    }
                                } finally { codec.releaseOutputBuffer(index, false) }
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            } else check(++idle < 500) { "音频解码超时" }
                        }
                    } finally { codec.release() }
                }
                val decoded = requireNotNull(normalizer) { "没有解码出 PCM" }
                decoded.finish()
                decoded.frames
            }
        } finally { extractor.release() }
    }
}
