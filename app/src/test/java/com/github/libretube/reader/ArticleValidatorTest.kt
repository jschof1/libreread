package com.github.libretube.reader

import com.github.libretube.reader.api.ArticleResponseDto
import com.github.libretube.reader.api.ArticleValidator
import com.github.libretube.reader.api.LibreReadApi
import com.github.libretube.reader.api.TranscriptResponseDto
import com.github.libretube.reader.model.ReaderErrorKind
import com.github.libretube.reader.model.ReaderException
import com.github.libretube.reader.model.StylePresets
import com.github.libretube.reader.model.TranscriptSource
import com.github.libretube.reader.store.ReaderJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ArticleValidatorTest {
    private fun parse(json: String) = ReaderJson.decodeFromString(ArticleResponseDto.serializer(), json)

    private fun expectInvalid(json: String) {
        try {
            ArticleValidator.validateArticle(parse(json))
            fail("expected INVALID_RESPONSE")
        } catch (e: ReaderException) {
            assertEquals(ReaderErrorKind.INVALID_RESPONSE, e.error.kind)
        }
    }

    @Test
    fun `accepts the contract shape and trims text`() {
        val (content, model) = ArticleValidator.validateArticle(
            parse(
                """
                {"article":{"title":" A title ","sections":[
                  {"heading":"Intro","paragraphs":[" First. ",""," "]},
                  {"heading":"Empty","paragraphs":[]},
                  {"heading":" Detail ","paragraphs":["<b>not html</b>"]}
                 ],"visualNotes":[" The chart is not described. ",""]},
                 "model":"provider/model","extra":true}
                """.trimIndent()
            )
        )
        assertEquals("A title", content.title)
        assertEquals(listOf("Intro", "Detail"), content.sections.map { it.heading })
        assertEquals(listOf("First."), content.sections[0].paragraphs)
        // markup is kept as literal text, never interpreted
        assertEquals("<b>not html</b>", content.sections[1].paragraphs.single())
        assertEquals(listOf("The chart is not described."), content.visualNotes)
        assertEquals("provider/model", model)
    }

    @Test
    fun `rejects missing article, title or text`() {
        expectInvalid("""{"model":"m"}""")
        expectInvalid("""{"article":{"title":"","sections":[{"heading":"h","paragraphs":["p"]}]}}""")
        expectInvalid("""{"article":{"title":"t"}}""")
        expectInvalid("""{"article":{"title":"t","sections":[{"heading":"h","paragraphs":[" "]}]}}""")
    }

    @Test
    fun `transcription must come from the AI transcription route`() {
        val ok = ArticleValidator.validateTranscript(
            "abc",
            TranscriptResponseDto("T", "C", "Para one.\n\nPara two.", "en", "ai-transcription", "gemini"),
            42
        )
        assertEquals(TranscriptSource.AI_TRANSCRIPTION, ok.source)
        assertEquals(2, ok.segments.size)
        assertEquals("gemini", ok.model)

        try {
            ArticleValidator.validateTranscript(
                "abc", TranscriptResponseDto("T", "C", "text", "en", "metadata", "m"), 0
            )
            fail()
        } catch (e: ReaderException) {
            assertEquals(ReaderErrorKind.INVALID_RESPONSE, e.error.kind)
        }
        try {
            ArticleValidator.validateTranscript(
                "abc", TranscriptResponseDto("T", "C", "  ", "en", "ai-transcription", "m"), 0
            )
            fail()
        } catch (e: ReaderException) {
            assertEquals(ReaderErrorKind.NO_TRANSCRIPT, e.error.kind)
        }
    }

    @Test
    fun `service base URL is normalised and unsafe forms rejected`() {
        assertEquals(
            "https://reader.example.com/v1/articles",
            LibreReadApi.normalizeBaseUrl("https://reader.example.com")!!
                .newBuilder().addPathSegments("v1/articles").build().toString()
        )
        assertEquals(
            "https://example.com/api/v1/articles",
            LibreReadApi.normalizeBaseUrl(" https://example.com/api/ ")!!
                .newBuilder().addPathSegments("v1/articles").build().toString()
        )
        assertNull(LibreReadApi.normalizeBaseUrl("not a url"))
        assertNull(LibreReadApi.normalizeBaseUrl("http://example.com"))
        assertNull(LibreReadApi.normalizeBaseUrl("https://user:pw@example.com"))
        assertNull(LibreReadApi.normalizeBaseUrl("https://example.com/?x=1"))
    }

    @Test
    fun `every style preset plus maximum custom instructions fits the contract`() {
        val custom = "x".repeat(StylePresets.MAX_CUSTOM_INSTRUCTIONS)
        for (preset in StylePresets.entries) {
            assertTrue(StylePresets.compose(preset.key, custom).length <= StylePresets.MAX_STYLE_LENGTH)
        }
        assertEquals(StylePresets.CLEAR.instruction, StylePresets.compose("unknown", "  "))
        assertEquals("Be brief.", StylePresets.compose("custom", " Be brief. "))
    }
}
