package com.github.libretube.reader.ui

import android.content.Context
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.github.libretube.R
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.reader.model.ArticleDetail
import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.StylePresets
import com.github.libretube.reader.model.TranscriptRecord
import com.github.libretube.reader.model.TranscriptSegment
import com.github.libretube.reader.model.TranscriptSource
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.google.android.material.R as MR

/**
 * Renders articles and transcripts as native text views. Every child of the container is one
 * block; block indices are stable for a given article revision regardless of text size, which is
 * what reading positions refer to.
 */
class ArticleRenderer(private val context: Context) {

    fun renderArticle(container: LinearLayout, record: ArticleRecord, bodySp: Int) {
        container.removeAllViews()
        val article = record.article

        container += text(article.title, MR.attr.textAppearanceHeadlineMedium, bodySp * 1.55f, bold = true)
            .apply { tag = BLOCK_TITLE }

        val meta = buildList {
            if (record.creator.isNotBlank()) add(context.getString(R.string.libreread_by_creator, record.creator))
            add(sourceLabel(record.transcript))
            if (record.originalTitle.isNotBlank() && record.originalTitle != article.title) {
                add(context.getString(R.string.libreread_original_title, record.originalTitle))
            }
            add(record.sourceUrl)
        }.joinToString("\n")
        container += text(meta, MR.attr.textAppearanceBodyMedium, bodySp * 0.8f, secondary = true)
            .apply { setTextIsSelectable(true) }

        for (section in article.sections) {
            if (section.heading.isNotBlank()) {
                container += text(section.heading, MR.attr.textAppearanceTitleLarge, bodySp * 1.2f, bold = true)
                    .apply { setPadding(0, dp(20), 0, dp(4)) }
            }
            for (paragraph in section.paragraphs) {
                container += paragraph(paragraph, bodySp)
            }
        }

        if (article.visualNotes.isNotEmpty()) {
            container += text(
                context.getString(R.string.libreread_visual_notes),
                MR.attr.textAppearanceTitleMedium,
                bodySp * 1.05f,
                bold = true
            ).apply { setPadding(0, dp(24), 0, dp(4)) }
            for (note in article.visualNotes) container += paragraph("• $note", bodySp)
        }

        val date = DateFormat.getMediumDateFormat(context).format(Date(record.createdAtMs))
        container += text(
            context.getString(
                R.string.libreread_article_footer,
                styleLabel(record.settings.stylePreset),
                detailLabel(record.settings.detail),
                record.model,
                date
            ),
            MR.attr.textAppearanceBodySmall,
            bodySp * 0.7f,
            secondary = true
        ).apply { setPadding(0, dp(32), 0, 0) }
    }

    fun renderTranscript(container: LinearLayout, transcript: TranscriptRecord, bodySp: Int) {
        container.removeAllViews()
        container += text(
            context.getString(R.string.libreread_transcript_title),
            MR.attr.textAppearanceHeadlineSmall,
            bodySp * 1.35f,
            bold = true
        )
        val meta = listOfNotNull(
            sourceLabel(transcript),
            transcript.model?.let { context.getString(R.string.libreread_transcript_model, it) }
        ).joinToString("\n")
        container += text(meta, MR.attr.textAppearanceBodyMedium, bodySp * 0.8f, secondary = true)

        for (group in groupTranscript(transcript.segments)) {
            container += paragraph(group, bodySp).apply { setTextIsSelectable(true) }
        }
    }

    fun sourceLabel(transcript: TranscriptRecord): String {
        val label = context.getString(
            when (transcript.source) {
                TranscriptSource.CREATOR_CAPTIONS -> R.string.libreread_source_creator_captions
                TranscriptSource.AUTOMATIC_CAPTIONS -> R.string.libreread_source_automatic_captions
                TranscriptSource.AI_TRANSCRIPTION -> R.string.libreread_source_ai_transcription
                TranscriptSource.USER_SUPPLIED -> R.string.libreread_source_user_supplied
            }
        )
        val language = transcript.language?.let { Locale.forLanguageTag(it).displayName }
            ?.takeIf { it.isNotBlank() }
        return if (language != null) {
            context.getString(R.string.libreread_source_language, label, language)
        } else {
            label
        }
    }

    fun styleLabel(key: String): String {
        val values = context.resources.getStringArray(R.array.libreread_style_values)
        val entries = context.resources.getStringArray(R.array.libreread_style_entries)
        val index = values.indexOf(StylePresets.fromKey(key).key)
        return entries.getOrElse(index) { key }
    }

    fun detailLabel(detail: ArticleDetail): String = context.getString(
        when (detail) {
            ArticleDetail.FULL -> R.string.libreread_detail_full
            ArticleDetail.BALANCED -> R.string.libreread_detail_balanced
            ArticleDetail.BRIEF -> R.string.libreread_detail_brief
        }
    ).substringBefore(" (")

    private fun groupTranscript(segments: List<TranscriptSegment>): List<String> {
        if (segments.none { it.startMs != null }) return segments.map { it.text }
        val groups = mutableListOf<String>()
        var groupStart: Long? = null
        val current = StringBuilder()
        for (segment in segments) {
            val start = segment.startMs
            if (current.isNotEmpty() && start != null && groupStart != null &&
                start - groupStart >= TRANSCRIPT_GROUP_MS
            ) {
                groups += current.toString()
                current.clear()
                groupStart = null
            }
            if (current.isEmpty()) {
                groupStart = start
                if (start != null) current.append('[').append(formatTimestamp(start)).append("] ")
            } else {
                current.append(' ')
            }
            current.append(segment.text)
        }
        if (current.isNotEmpty()) groups += current.toString()
        return groups
    }

    private fun paragraph(value: String, bodySp: Int) =
        text(value, MR.attr.textAppearanceBodyLarge, bodySp.toFloat()).apply {
            setPadding(0, dp(6), 0, dp(6))
            setLineSpacing(0f, 1.45f)
        }

    private fun text(
        value: String,
        appearanceAttr: Int,
        sizeSp: Float,
        bold: Boolean = false,
        secondary: Boolean = false
    ) = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        val appearance = TypedValue()
        if (context.theme.resolveAttribute(appearanceAttr, appearance, true)) {
            TextViewCompat.setTextAppearance(this, appearance.resourceId)
        }
        // plain text only: service content is never interpreted as HTML
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (secondary) {
            setTextColor(ThemeHelper.getThemeColor(context, MR.attr.colorOnSurfaceVariant))
        }
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private operator fun LinearLayout.plusAssign(view: View) = addView(view)

    companion object {
        const val BLOCK_TITLE = "title"
        private const val TRANSCRIPT_GROUP_MS = 45_000L

        fun formatTimestamp(ms: Long): String {
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
            }
        }
    }
}
