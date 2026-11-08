package com.github.libretube.reader.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.FrameLayout
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.libretube.R
import com.github.libretube.databinding.ActivityArticleBinding
import com.github.libretube.databinding.DialogLibrereadRewriteBinding
import com.github.libretube.databinding.DialogLibrereadTextInputBinding
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.reader.AiPurpose
import com.github.libretube.reader.PrepStage
import com.github.libretube.reader.ReadableWidth
import com.github.libretube.reader.ReaderGraph
import com.github.libretube.reader.ReaderNavigator
import com.github.libretube.reader.ReaderState
import com.github.libretube.reader.RewriteStatus
import com.github.libretube.reader.VideoHint
import com.github.libretube.reader.model.ArticleDetail
import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.ReaderError
import com.github.libretube.reader.model.ReaderErrorKind
import com.github.libretube.reader.model.ReadingPosition
import com.github.libretube.reader.model.StylePresets
import com.github.libretube.ui.activities.SettingsActivity
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.onSystemInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * Shows one video as an article. This is a plain reader activity, not a player host: it has no
 * player fragments, media session or service connection.
 */
class ArticleActivity : BaseActivity() {
    private lateinit var binding: ActivityArticleBinding
    private val viewModel: ArticleViewModel by viewModels()
    private val preferences by lazy { ReaderGraph.preferences(this) }
    private val renderer by lazy { ArticleRenderer(this) }

    private var renderedKey: String? = null
    private var renderedRecord: ArticleRecord? = null
    private var trackingPosition = false
    private var consentDialog: AlertDialog? = null
    private var lastStateClass: Class<*>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArticleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.contentFrame.onSystemInsets { _, insets ->
            binding.articleScroll.updatePadding(
                left = insets.left,
                right = insets.right,
                bottom = insets.bottom
            )
            binding.errorPanel.updatePadding(bottom = insets.bottom)
        }

        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId.isNullOrBlank()) {
            ReaderNavigator.openSavedArticles(this)
            finish()
            return
        }

        binding.progressCancel.setOnClickListener { viewModel.coordinator.cancel() }
        binding.errorRetry.setOnClickListener { viewModel.coordinator.retry() }
        binding.errorPaste.setOnClickListener { showPasteDialog() }
        binding.errorSettings.setOnClickListener { openReaderSettings() }
        binding.errorSaved.setOnClickListener { ReaderNavigator.openSavedArticles(this) }

        binding.articleScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (trackingPosition) currentPosition()?.let(viewModel::onPositionChanged)
        }

        viewModel.open(
            videoId,
            VideoHint(intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_CREATOR))
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.combine(viewModel.showTranscript) { state, transcript ->
                    state to transcript
                }.collect { (state, transcript) -> render(state, transcript) }
            }
        }
    }

    override fun onPause() {
        if (trackingPosition) currentPosition()?.let(viewModel::onPositionChanged)
        viewModel.flushPosition()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        val current = viewModel.state.value
        if (current is ReaderState.Failed && current.error.kind == ReaderErrorKind.NOT_CONFIGURED &&
            preferences.isServiceConfigured()
        ) {
            viewModel.coordinator.retry()
            return
        }
        // text size or width may have changed in settings
        val ready = current as? ReaderState.Ready ?: return
        render(ready, viewModel.showTranscript.value)
    }

    override fun onDestroy() {
        consentDialog?.setOnCancelListener(null)
        consentDialog?.dismiss()
        consentDialog = null
        super.onDestroy()
    }

    private fun render(state: ReaderState, showTranscript: Boolean) {
        if (state.javaClass != lastStateClass) {
            lastStateClass = state.javaClass
            invalidateOptionsMenu()
        }
        binding.progressPanel.isVisible =
            state is ReaderState.Preparing || state is ReaderState.Idle || state is ReaderState.AwaitingConsent
        binding.errorPanel.isVisible = state is ReaderState.Failed
        binding.articleScroll.isVisible = state is ReaderState.Ready
        binding.rewriteBanner.isVisible = false
        binding.rewriteAction.isVisible = true
        if (state !is ReaderState.Ready) {
            trackingPosition = false
            renderedKey = null
            renderedRecord = null
        }

        val awaitingConsent = state is ReaderState.AwaitingConsent ||
            (state is ReaderState.Ready && state.rewrite == RewriteStatus.AwaitingConsent)
        if (awaitingConsent) showConsentDialog() else dismissConsentDialog()

        when (state) {
            ReaderState.Idle -> showStage(PrepStage.CHECKING_SAVED)
            is ReaderState.Preparing -> showStage(state.stage)
            is ReaderState.AwaitingConsent -> showStage(
                if (state.purpose == AiPurpose.TRANSCRIPTION) PrepStage.TRANSCRIBING else PrepStage.WRITING_ARTICLE
            )
            is ReaderState.Failed -> showFailure(state)
            is ReaderState.Ready -> showReady(state, showTranscript)
        }
    }

    private fun showStage(stage: PrepStage) {
        binding.progressStage.setText(
            when (stage) {
                PrepStage.CHECKING_SAVED -> R.string.libreread_stage_checking_saved
                PrepStage.LOADING_VIDEO -> R.string.libreread_stage_loading_video
                PrepStage.FETCHING_CAPTIONS -> R.string.libreread_stage_fetching_captions
                PrepStage.TRANSCRIBING -> R.string.libreread_stage_transcribing
                PrepStage.WRITING_ARTICLE -> R.string.libreread_stage_writing
                PrepStage.SAVING -> R.string.libreread_stage_saving
            }
        )
        binding.progressIndicator.setProgressCompat(stage.ordinal + 1, true)
        binding.progressHint.isVisible =
            stage == PrepStage.WRITING_ARTICLE || stage == PrepStage.TRANSCRIBING
        binding.progressCancel.isVisible = stage != PrepStage.CHECKING_SAVED && stage != PrepStage.SAVING
    }

    private fun showFailure(state: ReaderState.Failed) {
        val lines = mutableListOf<String>()
        if (state.captionsUnavailable) lines += getString(R.string.libreread_error_captions_unavailable)
        lines += errorMessage(state.error)
        state.error.detail?.takeIf { shouldShowDetail(state.error) }?.let {
            lines += getString(R.string.libreread_error_detail, it)
        }
        if (state.hasTranscript) lines += getString(R.string.libreread_transcript_kept)
        binding.errorMessage.text = lines.joinToString("\n\n")

        binding.errorRetry.isVisible = state.error.kind != ReaderErrorKind.INVALID_VIDEO
        binding.errorPaste.isVisible = !state.hasTranscript && state.error.kind != ReaderErrorKind.INVALID_VIDEO
        binding.errorSettings.isVisible = state.error.kind in setOf(
            ReaderErrorKind.AUTH, ReaderErrorKind.NOT_CONFIGURED, ReaderErrorKind.CONSENT_DECLINED
        )
    }

    private fun shouldShowDetail(error: ReaderError) =
        error.kind == ReaderErrorKind.SERVER || error.kind == ReaderErrorKind.NO_TRANSCRIPT ||
            error.kind == ReaderErrorKind.AUTH

    private fun errorMessage(error: ReaderError): String = when (error.kind) {
        ReaderErrorKind.NO_TRANSCRIPT -> getString(R.string.libreread_error_no_transcript)
        ReaderErrorKind.NETWORK -> getString(R.string.libreread_error_network)
        ReaderErrorKind.TIMEOUT -> getString(R.string.libreread_error_timeout)
        ReaderErrorKind.AUTH -> getString(R.string.libreread_error_auth)
        ReaderErrorKind.NOT_CONFIGURED -> getString(R.string.libreread_error_not_configured)
        ReaderErrorKind.SERVER -> getString(R.string.libreread_error_server)
        ReaderErrorKind.INVALID_RESPONSE -> getString(R.string.libreread_error_invalid_response)
        ReaderErrorKind.TRANSCRIPT_TOO_LONG -> error.detail?.toIntOrNull()?.let {
            getString(R.string.libreread_error_too_long, NumberFormat.getIntegerInstance().format(it))
        } ?: getString(R.string.libreread_error_too_long_generic)
        ReaderErrorKind.CONSENT_DECLINED -> getString(R.string.libreread_error_consent_declined)
        ReaderErrorKind.CANCELLED -> getString(R.string.libreread_error_cancelled)
        ReaderErrorKind.STORAGE -> getString(R.string.libreread_error_storage)
        ReaderErrorKind.INVALID_VIDEO -> getString(R.string.libreread_error_invalid_video)
        ReaderErrorKind.UNKNOWN -> getString(R.string.libreread_error_unknown)
    }

    private fun showReady(state: ReaderState.Ready, showTranscript: Boolean) {
        val record = state.record
        supportActionBar?.title = record.creator.ifBlank { getString(R.string.libreread_article) }
        applyReadableWidth()

        val fontSize = preferences.fontSizeSp
        val key = listOf(record.revision, showTranscript, fontSize, preferences.readableWidth).joinToString("|")
        if (key != renderedKey) {
            // keep the position of the article being replaced before re-rendering it
            if (trackingPosition) currentPosition()?.let(viewModel::onPositionChanged)
            trackingPosition = false
            renderedKey = key
            renderedRecord = record
            if (showTranscript) {
                renderer.renderTranscript(binding.articleContainer, record.transcript, fontSize)
                binding.articleScroll.scrollTo(0, 0)
            } else {
                renderer.renderArticle(binding.articleContainer, record, fontSize)
                restorePosition(viewModel.positionFor(record))
            }
        }

        when (val rewrite = state.rewrite) {
            RewriteStatus.Idle, RewriteStatus.AwaitingConsent -> Unit
            RewriteStatus.InProgress -> {
                binding.rewriteBanner.isVisible = true
                binding.rewriteProgress.isVisible = true
                binding.rewriteText.setText(R.string.libreread_rewriting)
                binding.rewriteAction.setText(R.string.libreread_cancel)
                binding.rewriteAction.setOnClickListener { viewModel.coordinator.cancel() }
            }
            RewriteStatus.Committing -> {
                binding.rewriteBanner.isVisible = true
                binding.rewriteProgress.isVisible = true
                binding.rewriteText.setText(R.string.libreread_stage_saving)
                binding.rewriteAction.isVisible = false
            }
            is RewriteStatus.Failed -> {
                binding.rewriteBanner.isVisible = true
                binding.rewriteProgress.isVisible = false
                binding.rewriteText.text = listOf(
                    getString(R.string.libreread_rewrite_failed),
                    errorMessage(rewrite.error)
                ).joinToString(" ")
                binding.rewriteAction.setText(R.string.libreread_dismiss)
                binding.rewriteAction.setOnClickListener { viewModel.coordinator.dismissRewriteError() }
            }
        }
    }

    private fun applyReadableWidth() {
        val maxDp = preferences.readableWidth.maxWidthDp
        val screenPx = resources.displayMetrics.widthPixels
        val width = maxDp?.let { (it * resources.displayMetrics.density).roundToInt() }
            ?.takeIf { it < screenPx } ?: FrameLayout.LayoutParams.MATCH_PARENT
        val params = binding.articleContainer.layoutParams as FrameLayout.LayoutParams
        if (params.width != width) {
            params.width = width
            binding.articleContainer.layoutParams = params
        }
    }

    private fun restorePosition(position: ReadingPosition?) {
        val container = binding.articleContainer
        container.doOnPreDraw {
            val child = position?.let { container.getChildAt(it.blockIndex) }
            val y = if (child != null) container.top + child.top + position.offsetPx else 0
            binding.articleScroll.scrollTo(0, y)
            trackingPosition = true
        }
    }

    /** The current reading position, or null when an article is not shown. */
    private fun currentPosition(): ReadingPosition? {
        val record = renderedRecord ?: return null
        if (viewModel.showTranscript.value) return null
        val container = binding.articleContainer
        val scroll = binding.articleScroll
        if (container.childCount == 0) return null
        val y = scroll.scrollY - container.top
        var index = 0
        for (i in 0 until container.childCount) {
            if (container.getChildAt(i).bottom > y) {
                index = i
                break
            }
            index = i
        }
        val child = container.getChildAt(index)
        val range = (container.height + container.top - scroll.height).coerceAtLeast(1)
        return ReadingPosition(
            videoId = record.videoId,
            revision = record.revision,
            blockIndex = index,
            offsetPx = (y - child.top).coerceAtLeast(0),
            progress = (scroll.scrollY.toFloat() / range).coerceIn(0f, 1f),
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun showConsentDialog() {
        if (consentDialog?.isShowing == true) return
        consentDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_privacy_title)
            .setMessage(privacyNotice(this))
            .setPositiveButton(R.string.libreread_privacy_continue) { _, _ ->
                viewModel.coordinator.answerConsent(true)
            }
            .setNegativeButton(R.string.libreread_privacy_not_now) { _, _ ->
                viewModel.coordinator.answerConsent(false)
            }
            .setOnCancelListener { viewModel.coordinator.answerConsent(false) }
            .show()
    }

    private fun dismissConsentDialog() {
        consentDialog?.setOnCancelListener(null)
        consentDialog?.dismiss()
        consentDialog = null
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.article_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val ready = viewModel.state.value is ReaderState.Ready
        listOf(
            R.id.action_transcript, R.id.action_rewrite, R.id.action_text_size,
            R.id.action_width, R.id.action_delete
        ).forEach { menu.findItem(it)?.isVisible = ready }
        menu.findItem(R.id.action_transcript)?.isChecked = viewModel.showTranscript.value
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_transcript -> {
                val show = !viewModel.showTranscript.value
                item.isChecked = show
                viewModel.setShowTranscript(show)
            }
            R.id.action_rewrite -> showRewriteDialog()
            R.id.action_text_size -> showTextSizeDialog()
            R.id.action_width -> showWidthDialog()
            R.id.action_copy_link -> {
                val videoId = viewModel.coordinator.videoId ?: return true
                ClipboardHelper.save(this, text = ArticleRecord.sourceUrlFor(videoId), notify = true)
            }
            R.id.action_saved_articles -> ReaderNavigator.openSavedArticles(this)
            R.id.action_reader_settings -> openReaderSettings()
            R.id.action_delete -> confirmDelete()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showRewriteDialog() {
        val ready = viewModel.state.value as? ReaderState.Ready ?: return
        val current = ready.record.settings
        val dialogBinding = DialogLibrereadRewriteBinding.inflate(layoutInflater)

        val styleValues = resources.getStringArray(R.array.libreread_style_values)
        val styleEntries = resources.getStringArray(R.array.libreread_style_entries)
        styleValues.forEachIndexed { index, value ->
            dialogBinding.styleGroup.addView(RadioButton(this).apply {
                id = STYLE_ID_BASE + index
                text = styleEntries[index]
                isChecked = value == StylePresets.fromKey(current.stylePreset).key
            })
        }
        val detailValues = resources.getStringArray(R.array.libreread_detail_values)
        val detailEntries = resources.getStringArray(R.array.libreread_detail_entries)
        detailValues.forEachIndexed { index, value ->
            dialogBinding.detailGroup.addView(RadioButton(this).apply {
                id = DETAIL_ID_BASE + index
                text = detailEntries[index]
                isChecked = value == current.detail.wireName
            })
        }
        dialogBinding.customInput.setText(current.customInstructions)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_rewrite)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.libreread_rewrite_action) { _, _ ->
                val styleIndex = (dialogBinding.styleGroup.checkedRadioButtonId - STYLE_ID_BASE)
                    .coerceIn(styleValues.indices)
                val detailIndex = (dialogBinding.detailGroup.checkedRadioButtonId - DETAIL_ID_BASE)
                    .coerceIn(detailValues.indices)
                val settings = ArticleSettings(
                    stylePreset = styleValues[styleIndex],
                    customInstructions = dialogBinding.customInput.text?.toString().orEmpty()
                        .trim().take(StylePresets.MAX_CUSTOM_INSTRUCTIONS),
                    detail = ArticleDetail.fromWire(detailValues[detailIndex])
                )
                viewModel.rewrite(settings, dialogBinding.saveDefault.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTextSizeDialog() {
        val sizes = resources.getStringArray(R.array.libreread_font_entries)
        val current = sizes.indexOf(preferences.fontSizeSp.toString())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_text_size)
            .setSingleChoiceItems(sizes, current) { dialog, which ->
                preferences.fontSizeSp = sizes[which].toInt()
                dialog.dismiss()
                rerenderReady()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWidthDialog() {
        val entries = resources.getStringArray(R.array.libreread_width_entries)
        val current = ReadableWidth.entries.indexOf(preferences.readableWidth)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_readable_width)
            .setSingleChoiceItems(entries, current) { dialog, which ->
                preferences.readableWidth = ReadableWidth.entries[which]
                dialog.dismiss()
                rerenderReady()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rerenderReady() {
        val ready = viewModel.state.value as? ReaderState.Ready ?: return
        render(ready, viewModel.showTranscript.value)
    }

    private fun showPasteDialog() {
        val dialogBinding = DialogLibrereadTextInputBinding.inflate(layoutInflater)
        dialogBinding.explanation.setText(R.string.libreread_paste_explanation)
        dialogBinding.inputLayout.hint = getString(R.string.libreread_paste_hint)
        dialogBinding.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        dialogBinding.input.minLines = 6
        dialogBinding.input.maxLines = 12
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_paste_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.libreread_use_transcript) { _, _ ->
                val text = dialogBinding.input.text?.toString().orEmpty()
                if (text.isNotBlank()) viewModel.coordinator.useSuppliedTranscript(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_delete_article)
            .setMessage(R.string.libreread_delete_article_confirm)
            .setPositiveButton(R.string.libreread_delete) { _, _ ->
                trackingPosition = false
                viewModel.deleteArticle {
                    Toast.makeText(this, R.string.libreread_delete, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openReaderSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.REDIRECT_KEY, SettingsActivity.REDIRECT_TO_READER_SETTINGS)
        )
    }

    companion object {
        const val EXTRA_VIDEO_ID = "libreread.videoId"
        const val EXTRA_TITLE = "libreread.title"
        const val EXTRA_CREATOR = "libreread.creator"

        private const val STYLE_ID_BASE = 0x5100
        private const val DETAIL_ID_BASE = 0x5200

        fun intent(context: Context, videoId: String, title: String? = null, creator: String? = null) =
            Intent(context, ArticleActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CREATOR, creator)

        fun privacyNotice(context: Context): String {
            val apiUrl = ReaderGraph.preferences(context).apiUrl
            val host = com.github.libretube.reader.api.LibreReadApi.normalizeBaseUrl(apiUrl)?.host
                ?: apiUrl.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.libreread_privacy_service_not_set)
            return context.getString(R.string.libreread_privacy_notice, host)
        }
    }
}
