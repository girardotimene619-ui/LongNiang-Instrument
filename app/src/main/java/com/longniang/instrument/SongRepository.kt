package com.longniang.instrument

import com.longniang.instrument.sus.NoteResolver
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipFile

internal class SongRepository(private val root: File) {
    private val committed = File(root, "songs")
    private val staging = File(root, "staging")

    fun songs(): List<File> = committed.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{64}")) }
        ?.sortedBy { it.name }.orEmpty()

    fun importZip(input: InputStream, stage: (String) -> Unit = {}): File {
        stage("prepare")
        check(staging.mkdirs() || staging.isDirectory)
        check(committed.mkdirs() || committed.isDirectory)
        val work = java.nio.file.Files.createTempDirectory(staging.toPath(), "import-").toFile()
        try {
            val archive = File(work, "source.zip")
            stage("copy")
            archive.outputStream().use { copyLimited(input, it, 128L * 1024 * 1024) }
            val content = File(work, "content").apply { check(mkdir()) }
            stage("extract")
            val id = ZipImporter.extract(archive, content)
            require(content.walkTopDown().any { it.isFile && it.extension.equals("sus", true) }) {
                "ZIP 内没有 SUS 谱面"
            }
            val destination = File(committed, id)
            synchronized(commitLock) {
                if (!destination.isDirectory) {
                    stage("commit:new")
                    java.nio.file.Files.move(content.toPath(), destination.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } else stage("commit:duplicate")
            }
            return destination
        } finally {
            stage("cleanup")
            if (!work.deleteRecursively()) stage("cleanup:incomplete")
        }
    }

    companion object { private val commitLock = Any() }
}

internal object ZipImporter {
    private const val expandedLimit = 256L * 1024 * 1024
    private val extensions = setOf("sus", "wav", "mp3", "ogg", "png", "jpg", "jpeg", "webp")

    fun extract(archive: File, root: File): String {
        val digests = sortedMapOf<String, ByteArray>()
        val paths = mutableMapOf<String, String>()
        val entries = mutableSetOf<String>()
        var expanded = 0L
        ZipFile(archive, Charsets.UTF_8).use { zip ->
            require(zip.size() <= 512) { "ZIP 条目超过 512" }
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                check(!Thread.currentThread().isInterrupted) { "已取消导入" }
                val entry = iterator.nextElement()
                val path = entry.name.removeSuffix("/")
                val parts = path.split('/')
                require(path.length <= 1024 && parts.size <= 12 && parts.all { part ->
                    part.isNotEmpty() && part != "." && part != ".." &&
                        part.none { it == '\\' || it == ':' || it.isISOControl() }
                }) { "ZIP 路径无效：${entry.name}" }
                require(entries.add(path)) { "ZIP 条目重复：$path" }
                for (index in parts.indices) {
                    val prefix = parts.take(index + 1).joinToString("/")
                    val key = Normalizer.normalize(prefix, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                    require(paths.putIfAbsent(key, prefix).let { it == null || it == prefix }) {
                        "ZIP 路径编码或大小写冲突：$prefix"
                    }
                }
                if (entry.isDirectory) continue
                val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
                val limit = when (ext) {
                    "sus" -> 4L * 1024 * 1024
                    "wav", "mp3", "ogg" -> 128L * 1024 * 1024
                    else -> 16L * 1024 * 1024
                }
                val file = File(root, path)
                require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "ZIP 路径越界" }
                val digest = MessageDigest.getInstance("SHA-256")
                val output = if (ext in extensions) {
                    check(file.parentFile?.let { it.mkdirs() || it.isDirectory } == true)
                    java.security.DigestOutputStream(file.outputStream(), digest)
                } else object : java.io.OutputStream() {
                    override fun write(value: Int) = Unit
                    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                }
                val size = output.use { sink ->
                    zip.getInputStream(entry).use { input ->
                        copyLimited(input, sink, minOf(limit, expandedLimit - expanded))
                    }
                }
                expanded += size
                if (ext in extensions) digests[path] = digest.digest()
            }
        }
        val pack = MessageDigest.getInstance("SHA-256")
        for ((path, digest) in digests) {
            pack.update(path.toByteArray(Charsets.UTF_8))
            pack.update(0.toByte())
            pack.update(digest)
        }
        return pack.digest().joinToString("") { "%02x".format(it) }
    }
}

internal fun copyLimited(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        check(!Thread.currentThread().isInterrupted) { "已取消" }
        val count = input.read(buffer)
        if (count < 0) return total
        total += count
        require(total <= limit) { "文件或解压体积超过上限" }
        output.write(buffer, 0, count)
    }
}

internal data class SongDifficulty(
    val title: String, val label: String, val chart: File,
    val audio: List<File>, val covers: List<File>, val problem: String?, val searchNames: List<String> = emptyList()
)

internal object ResourceMatcher {
    private val audioExtensions = setOf("wav", "mp3", "ogg")
    private val imageExtensions = setOf("png", "jpg", "jpeg", "webp")
    private val difficulty = Regex("(?i)(?:^|[^a-z])(easy|normal|hard|expert|master)(?:$|[^a-z])")

    fun difficulties(root: File): List<SongDifficulty> {
        val files = root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val audio = files.filter { it.extension.lowercase(Locale.ROOT) in audioExtensions }
        val images = files.filter { it.extension.lowercase(Locale.ROOT) in imageExtensions }
        return files.filter { it.extension.equals("sus", true) }.map { file ->
            val chart = NoteResolver.resolve(file.readBytes())
            fun meta(key: String) = chart.metadata.firstOrNull { it.key == key }
                ?.value?.trim()?.removeSurrounding("\"").orEmpty()
            val rawDifficulty = meta("DIFFICULTY")
            val name = difficulty.find(file.nameWithoutExtension)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            val label = if (rawDifficulty.lowercase(Locale.ROOT) in setOf("easy","normal","hard","expert","master"))
                rawDifficulty else name ?: rawDifficulty.takeIf { it.isNotBlank() }?.let { "难度 $it" } ?: "难度未标注"
            val level = meta("PLAYLEVEL").takeIf { it.isNotBlank() }?.let { " Lv.$it" }.orEmpty()
            val adapted = MixedChart.prepare(chart)
            val matches = candidates(root, file, meta("WAVE"), audio)
            val error = adapted.diagnostics.firstOrNull { it.error }
            val problems = listOfNotNull(
                if (matches.isEmpty()) "缺少音频" else null,
                if (!adapted.playable) "${error?.code ?: "不可玩"}${error?.line?.let { " 第 ${it} 行" }.orEmpty()}" else null
            )
            SongDifficulty(meta("TITLE").ifBlank { file.nameWithoutExtension }, label + level, file,
                matches, candidates(root, file, meta("JACKET"), images), problems.joinToString(" · ").ifEmpty { null },
                listOf("SUBTITLE", "TITLEEN", "TITLE_EN", "ALIAS", "CHARTNAME").map(::meta).filter { it.isNotBlank() })
        }
    }

    private fun candidates(root: File, chart: File, reference: String, files: List<File>): List<File> {
        if (reference.isNotBlank()) {
            val referenced = listOf(File(chart.parentFile, reference), File(root, reference))
                .map { it.canonicalFile }
            val matches = files.filter { it.canonicalFile in referenced }
            if (matches.isNotEmpty()) return matches
        }
        val local = files.filter { it.parentFile == chart.parentFile }
        val sameName = local.filter { it.nameWithoutExtension.equals(chart.nameWithoutExtension, true) }
        return sameName.ifEmpty { local.ifEmpty { files } }
    }
}
