package com.github.libretube.reader.model

/**
 * Built-in writing styles. The chosen preset and the user's custom instructions are combined
 * into the `style` field (maximum [MAX_STYLE_LENGTH] characters) of the article request.
 */
enum class StylePresets(val key: String, val instruction: String) {
    CLEAR(
        "clear",
        "Write in clear, plain English with British spelling. Use descriptive headings and " +
            "well-formed paragraphs."
    ),
    CONVERSATIONAL(
        "conversational",
        "Write in a warm, conversational tone, as if explaining the video to a curious friend, " +
            "while keeping every substantive point."
    ),
    ANALYTICAL(
        "analytical",
        "Write in a precise, analytical register. Make the reasoning, evidence and caveats explicit."
    ),
    TECHNICAL(
        "technical",
        "Write for a technically literate reader. Keep exact terminology, figures, steps and " +
            "technical detail."
    ),
    NARRATIVE(
        "narrative",
        "Write as an engaging long-form feature with a natural narrative flow."
    ),
    CUSTOM_ONLY("custom", "");

    companion object {
        const val MAX_STYLE_LENGTH = 2000

        /** Custom instructions are capped so that any preset plus them fits the contract limit. */
        const val MAX_CUSTOM_INSTRUCTIONS = 1600

        val DEFAULT = CLEAR

        fun fromKey(key: String?): StylePresets = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun compose(presetKey: String, customInstructions: String): String {
            val preset = fromKey(presetKey)
            return listOf(preset.instruction, customInstructions.trim())
                .filter { it.isNotEmpty() }
                .joinToString("\n\n")
        }
    }
}
