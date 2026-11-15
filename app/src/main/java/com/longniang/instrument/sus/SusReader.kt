package com.longniang.instrument.sus

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class SusRecord(val line: Int, val text: String, val key: String?, val value: String?)
data class SusDiagnostic(val line: Int?, val code: String, val message: String, val error: Boolean)
data class RawSusDocument(val records: List<SusRecord>, val diagnostics: List<SusDiagnostic>)

object SusReader {
    fun read(bytes: ByteArray): RawSusDocument {
        if (bytes.size > 4 * 1024 * 1024) return failure("RESOURCE_LIMIT", "SUS exceeds 4 MiB")
        val buffer = ByteBuffer.wrap(bytes)
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return failure("INVALID_UTF8", "Invalid UTF-8 at byte ${buffer.position()}")
        }
        return SusParser.parse(decoded.removePrefix("\uFEFF"))
    }

    private fun failure(code: String, message: String) =
        RawSusDocument(emptyList(), listOf(SusDiagnostic(null, code, message, true)))
}

object SusParser {
    private val directive = Regex("""^#([A-Za-z0-9_]+)(?:\s*:\s*|\s+)(.*)$""")
    fun parse(text: String): RawSusDocument {
        val records = mutableListOf<SusRecord>()
        val diagnostics = mutableListOf<SusDiagnostic>()
        for ((index, line) in text.lineSequence().withIndex()) {
            if (index >= 100_000 || line.length > 65_536) {
                diagnostics += SusDiagnostic(index + 1, "RESOURCE_LIMIT", "Too many lines or line too long", true)
                break
            }
            val trimmed = line.trim()
            val match = directive.matchEntire(trimmed)
            if (trimmed.startsWith("#") && match == null)
                diagnostics += SusDiagnostic(index + 1, "BAD_DIRECTIVE", "Expected directive and value", true)
            records += SusRecord(index + 1, line, match?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT),
                match?.groupValues?.get(2))
        }
        return RawSusDocument(records.toList(), diagnostics.toList())
    }
}
