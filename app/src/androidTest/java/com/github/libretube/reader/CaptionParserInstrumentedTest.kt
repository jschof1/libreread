package com.github.libretube.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.libretube.reader.source.CaptionParser
import com.github.libretube.reader.source.CaptionPayload
import com.github.libretube.reader.source.TranscriptNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Media3's subtitle parsers need Android text classes, so these run on a device. */
@RunWith(AndroidJUnit4::class)
class CaptionParserInstrumentedTest {

    @Test
    fun parsesRollingAutomaticWebVtt() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            00:00:00.000 --> 00:00:02.000 align:start position:0%
            hello<00:00:00.500><c> there</c>

            00:00:02.000 --> 00:00:02.010 align:start position:0%
            hello there

            00:00:02.010 --> 00:00:04.000 align:start position:0%
            hello there
            how<00:00:02.500><c> are</c><00:00:03.000><c> you</c>

            00:00:04.000 --> 00:00:04.010 align:start position:0%
            how are you

            00:00:04.010 --> 00:00:06.000 align:start position:0%
            how are you
            doing today
        """.trimIndent().toByteArray()
        val mime = CaptionPayload.resolveMimeType(null, vtt)
        assertEquals(CaptionPayload.MIME_VTT, mime)
        val cues = CaptionParser.parse(vtt, mime!!)
        assertNotNull(cues)
        val text = TranscriptNormalizer.normalize(cues!!, rollingCaptions = true).joinToString(" ") { it.text }
        assertEquals("hello there how are you doing today", text)
    }

    @Test
    fun parsesTtml() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <tt xml:lang="en" xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:00.000" end="00:00:01.500">First caption.</p>
            <p begin="00:00:01.500" end="00:00:03.000">Second caption.</p>
            </div></body></tt>
        """.trimIndent().toByteArray()
        val cues = CaptionParser.parse(ttml, CaptionPayload.MIME_TTML)!!
        val segments = TranscriptNormalizer.normalize(cues)
        assertEquals(listOf("First caption.", "Second caption."), segments.map { it.text })
        assertEquals(0L, segments.first().startMs)
    }

    @Test
    fun cuelessWebVttHasNoText() {
        val cues = CaptionParser.parse("WEBVTT\n\n".toByteArray(), CaptionPayload.MIME_VTT)
        assertTrue(cues.orEmpty().none { it.text.isNotBlank() })
    }

    @Test
    fun unsupportedFormatIsRejectedWithoutParsing() {
        assertNull(CaptionParser.parse("<timedtext/>".toByteArray(), "text/xml"))
    }
}
