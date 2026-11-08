package com.github.libretube.reader.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.databinding.ActivitySavedArticlesBinding
import com.github.libretube.databinding.SavedArticleRowBinding
import com.github.libretube.reader.ReaderGraph
import com.github.libretube.reader.ReaderNavigator
import com.github.libretube.reader.model.ArticleSummary
import com.github.libretube.reader.model.TranscriptSource
import com.github.libretube.ui.base.BaseActivity
import com.github.libretube.ui.extensions.onSystemInsets
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.roundToInt

/** Saved articles, read only from local storage, so the list works offline. */
class SavedArticlesActivity : BaseActivity() {
    private lateinit var binding: ActivitySavedArticlesBinding
    private val adapter = SavedArticlesAdapter(
        onOpen = { ReaderNavigator.openArticle(this, it.videoId, it.originalTitle, it.creator) },
        onDelete = ::confirmDelete
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySavedArticlesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.contentFrame.onSystemInsets { _, insets ->
            binding.savedList.updatePadding(bottom = insets.bottom)
        }
        binding.savedList.layoutManager = LinearLayoutManager(this)
        binding.savedList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val articles = ReaderGraph.store(this@SavedArticlesActivity).listArticles()
            adapter.submitList(articles)
            binding.savedEmpty.isVisible = articles.isEmpty()
        }
    }

    private fun confirmDelete(summary: ArticleSummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.libreread_delete_article)
            .setMessage(R.string.libreread_delete_article_confirm)
            .setPositiveButton(R.string.libreread_delete) { _, _ ->
                lifecycleScope.launch {
                    ReaderGraph.store(this@SavedArticlesActivity).deleteArticle(summary.videoId)
                    load()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

private class SavedArticlesAdapter(
    private val onOpen: (ArticleSummary) -> Unit,
    private val onDelete: (ArticleSummary) -> Unit
) : ListAdapter<ArticleSummary, SavedArticlesAdapter.Holder>(Diff) {

    class Holder(val binding: SavedArticleRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        SavedArticleRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        holder.binding.savedTitle.text = item.title
        holder.binding.savedCreator.text = item.creator
        holder.binding.savedCreator.isVisible = item.creator.isNotBlank()
        val date = DateFormat.getMediumDateFormat(context).format(Date(item.createdAtMs))
        val source = context.getString(
            when (item.source) {
                TranscriptSource.CREATOR_CAPTIONS -> R.string.libreread_source_creator_captions
                TranscriptSource.AUTOMATIC_CAPTIONS -> R.string.libreread_source_automatic_captions
                TranscriptSource.AI_TRANSCRIPTION -> R.string.libreread_source_ai_transcription
                TranscriptSource.USER_SUPPLIED -> R.string.libreread_source_user_supplied
            }
        )
        holder.binding.savedMeta.text = listOfNotNull(
            context.getString(R.string.libreread_saved_on, date),
            source,
            item.progress?.let { context.getString(R.string.libreread_read_percent, (it * 100).roundToInt()) }
        ).joinToString(" · ")
        holder.binding.root.setOnClickListener { onOpen(item) }
        holder.binding.root.setOnLongClickListener {
            onDelete(item)
            true
        }
    }

    private object Diff : DiffUtil.ItemCallback<ArticleSummary>() {
        override fun areItemsTheSame(oldItem: ArticleSummary, newItem: ArticleSummary) =
            oldItem.videoId == newItem.videoId

        override fun areContentsTheSame(oldItem: ArticleSummary, newItem: ArticleSummary) =
            oldItem == newItem
    }
}
