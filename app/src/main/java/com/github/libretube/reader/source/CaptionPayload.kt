package com.github.libretube.reader.source

import java.util.Locale

/**
 * Checks on downloaded caption bytes before parsing. An HTTP 200 response can still be empty or
 * an HTML error/consent page, and those must count as unavailable captions.
 */
object CaptionPayload {
    const val MIME_VTT = "text/vtt"
    const val MIME_TTML = "application/ttml+xml"
    const val MIME_SUBRIP = "application/x-subrip"

    enum class Problem { EMPTY, HTML }

    fun problem(bytes: ByteArray, contentType: String?): Problem? {
        val head = head(bytes)
        if (head.isBlank()) return Problem.EMPTY
        val type = contentType?.lowercase(Locale.ROOT).orEmpty()
        val lower = head.lowercase(Locale.ROOT)
        if (type.startsWith("text/html") || lower.startsWith("<!doctype html") ||
            lower.startsWith("<html")
        ) {
            return Problem.HTML
        }
        return null
    }

    /**
     * The MIME type to parse with. The declared type wins when it is one Media3 understands,
     * otherwise the content is sniffed.
     */
    fun resolveMimeType(declared: String?, bytes: ByteArray): String? {
        val normalized = declared?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (normalized in setOf(MIME_VTT, MIME_TTML, MIME_SUBRIP)) return normalized
        val head = head(bytes)
        return when {
            head.startsWith("WEBVTT") -> MIME_VTT
            head.contains("<tt") && head.contains("ttml") -> MIME_TTML
            SRT_START.containsMatchIn(head) -> MIME_SUBRIP
            else -> normalized
        }
    }

    private val SRT_START = Regex("^\\d+\\s*\\r?\\n\\d{2}:\\d{2}:\\d{2},\\d{3} --> ")

    private fun head(bytes: ByteArray): String = bytes
        .copyOfRange(0, minOf(bytes.size, 1024))
        .decodeToString()
        .removePrefix("﻿")
        .trimStart()
}
