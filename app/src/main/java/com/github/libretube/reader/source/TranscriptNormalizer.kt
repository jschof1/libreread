package com.github.libretube.reader.source

import com.github.libretube.reader.model.TranscriptSegment

/** A cue as decoded from a caption file, before clean-up. */
data class RawCue(
    val startMs: Long?,
    val endMs: Long?,
    val text: String
)

/** Preserves source cues; automatic tracks can remove duplication supported by roll-over cue structure. */
object TranscriptNormalizer {
    private const val MIN_ROLLING_WORDS = 2

    private val whitespace = Regex("\\s+")
    private val invisible = Regex("[\\u200B-\\u200D\\uFEFF]")

    fun normalize(cues: List<RawCue>, rollingCaptions: Boolean = false): List<TranscriptSegment> {
        val ordered = cues.withIndex()
            .sortedWith(compareBy<IndexedValue<RawCue>, Long?>(nullsLast()) { it.value.startMs }
                .thenBy { it.index })
            .map { it.value }

        val segments = mutableListOf<TranscriptSegment>()
        var previousKeys: List<String> = emptyList()
        var previousCue: RawCue? = null

        for (cue in ordered) {
            val words = clean(cue.text).takeIf { it.isNotEmpty() }?.split(' ') ?: continue
            val keys = words.map(::matchKey)
            val previous = previousCue

            val overlap = if (rollingCaptions && previous != null && hasRollingEvidence(previous, cue)) {
                rollingOverlap(previousKeys, keys).takeUnless {
                    it == keys.size && previous.startMs != cue.startMs && !isFlashCue(cue)
                } ?: 0
            } else {
                0
            }
            val fresh = words.drop(overlap)

            previousKeys = keys
            previousCue = cue

            if (fresh.isEmpty()) {
                // the cue only repeated text already shown; extend the last segment's time span
                val last = segments.lastOrNull() ?: continue
                val end = listOfNotNull(last.endMs, cue.endMs).maxOrNull()
                segments[segments.lastIndex] = last.copy(endMs = end)
                continue
            }
            segments += TranscriptSegment(cue.startMs, cue.endMs, fresh.joinToString(" "))
        }
        return segments
    }

    /**
     * Number of leading words of [next] that repeat the trailing words of [previous].
     */
    internal fun rollingOverlap(previous: List<String>, next: List<String>): Int {
        val max = minOf(previous.size, next.size)
        for (k in max downTo 1) {
            val tail = previous.subList(previous.size - k, previous.size)
            val head = next.subList(0, k)
            if (tail != head) continue
            if (k >= MIN_ROLLING_WORDS) return k
        }
        return 0
    }

    private fun hasRollingEvidence(previous: RawCue, next: RawCue): Boolean {
        val start = next.startMs ?: return false
        val end = previous.endMs ?: return false
        if (start > end + 250L) return false
        return start < end || isFlashCue(previous) || isFlashCue(next) || next.text.contains('\n')
    }

    private fun isFlashCue(cue: RawCue): Boolean {
        val start = cue.startMs ?: return false
        val end = cue.endMs ?: return false
        return end - start in 1L..40L
    }

    internal fun clean(text: String): String = text
        .replace(invisible, "")
        .replace(whitespace, " ")
        .trim()

    private fun matchKey(word: String): String {
        val stripped = word.lowercase().trim { !it.isLetterOrDigit() }
        return stripped.ifEmpty { word }
    }
}
