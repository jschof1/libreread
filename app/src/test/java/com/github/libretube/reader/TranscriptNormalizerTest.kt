package com.github.libretube.reader

import com.github.libretube.reader.model.TranscriptSegment
import com.github.libretube.reader.model.TranscriptText
import com.github.libretube.reader.source.RawCue
import com.github.libretube.reader.source.TranscriptNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptNormalizerTest {

    private fun text(cues: List<RawCue>, rolling: Boolean = false) =
        TranscriptNormalizer.normalize(cues, rollingCaptions = rolling).joinToString(" ") { it.text }

    @Test
    fun `removes YouTube style rolling overlap`() {
        val cues = listOf(
            RawCue(0, 2000, "hello there"),
            RawCue(2000, 2010, "hello there"),
            RawCue(2010, 4000, "hello there\nhow are you"),
            RawCue(4000, 4010, "how are you"),
            RawCue(4010, 6000, "how are you\ndoing today")
        )
        assertEquals("hello there how are you doing today", text(cues, rolling = true))
    }

    @Test
    fun `creator captions preserve adjacent genuine repetitions`() {
        val cues = listOf(RawCue(0, 1200, "very"), RawCue(1000, 2000, "very important"))
        assertEquals("very very important", text(cues))
        assertEquals("very very important", text(cues, rolling = true))
    }

    @Test
    fun `adjacent automatic cues alone do not prove rolling duplication`() {
        val cues = listOf(RawCue(0, 1000, "one two"), RawCue(1000, 1500, "one two"))
        assertEquals("one two one two", text(cues, rolling = true))
    }

    @Test
    fun `keeps a phrase that genuinely repeats later in the video`() {
        val cues = listOf(
            RawCue(0, 2000, "this is important"),
            RawCue(2000, 4000, "so listen carefully"),
            RawCue(4000, 6000, "this is important")
        )
        assertEquals("this is important so listen carefully this is important", text(cues))
    }

    @Test
    fun `keeps a repeat that follows a pause`() {
        val cues = listOf(
            RawCue(0, 1000, "again"),
            RawCue(5000, 6000, "again")
        )
        assertEquals("again again", text(cues))
    }

    @Test
    fun `does not strip a single shared word between unrelated lines`() {
        val cues = listOf(
            RawCue(0, 2000, "we need to go"),
            RawCue(2000, 4000, "go team go")
        )
        assertEquals("we need to go go team go", text(cues))
    }

    @Test
    fun `overlap matching ignores case and punctuation`() {
        val cues = listOf(
            RawCue(0, 2000, "Right, so the key point"),
            RawCue(1000, 4000, "so the key point is timing.")
        )
        assertEquals("Right, so the key point is timing.", text(cues, rolling = true))
    }

    @Test
    fun `drops blank cues and collapses whitespace`() {
        val cues = listOf(
            RawCue(0, 1000, "  first​   line "),
            RawCue(1000, 2000, "   "),
            RawCue(2000, 3000, "second")
        )
        val segments = TranscriptNormalizer.normalize(cues)
        assertEquals(listOf("first line", "second"), segments.map { it.text })
    }

    @Test
    fun `orders cues by start time and keeps timestamps`() {
        val cues = listOf(RawCue(3000, 4000, "later"), RawCue(0, 1000, "earlier"))
        val segments = TranscriptNormalizer.normalize(cues)
        assertEquals(listOf("earlier", "later"), segments.map { it.text })
        assertEquals(0L, segments.first().startMs)
    }

    @Test
    fun `same-time automatic duplicate extends the previous segment`() {
        val segments = TranscriptNormalizer.normalize(
            listOf(RawCue(0, 1000, "one two"), RawCue(0, 1500, "one two")),
            rollingCaptions = true
        )
        assertEquals(listOf(TranscriptSegment(0, 1500, "one two")), segments)
    }

    @Test
    fun `plain text starts paragraphs at long pauses`() {
        val joined = TranscriptText.join(
            listOf(
                TranscriptSegment(0, 1000, "first"),
                TranscriptSegment(1200, 2000, "second"),
                TranscriptSegment(9000, 10000, "third")
            )
        )
        assertEquals("first second\n\nthird", joined)
    }

    @Test
    fun `untimed segments split on blank lines`() {
        val segments = TranscriptText.untimedSegments("one\ntwo\n\n\nthree  \r\n\r\n")
        assertEquals(listOf("one two", "three"), segments.map { it.text })
    }
}
