package com.github.libretube.reader.source

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Decodes caption files with Media3's subtitle parsers (the same factory the upstream player
 * uses), without creating a player.
 */
@OptIn(UnstableApi::class)
object CaptionParser {
    private val factory = DefaultSubtitleParserFactory()

    fun supports(mimeType: String): Boolean = factory.supportsFormat(format(mimeType))

    /**
     * @return the decoded cues, or null when the format is not supported
     * @throws RuntimeException when the data is malformed
     */
    fun parse(bytes: ByteArray, mimeType: String): List<RawCue>? {
        val format = format(mimeType)
        if (!factory.supportsFormat(format)) return null
        val parser = factory.create(format)
        val cues = mutableListOf<RawCue>()
        try {
            parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { cuesWithTiming ->
                val text = cuesWithTiming.cues
                    .mapNotNull { it.text?.toString() }
                    .joinToString("\n")
                val start = cuesWithTiming.startTimeUs.takeIf { it != C.TIME_UNSET }
                val end = cuesWithTiming.endTimeUs.takeIf { it != C.TIME_UNSET }
                cues += RawCue(start?.div(1000), end?.div(1000), text)
            }
        } finally {
            parser.reset()
        }
        return cues
    }

    private fun format(mimeType: String) = Format.Builder()
        .setSampleMimeType(mimeType)
        .build()
}
