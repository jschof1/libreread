package com.github.libretube.reader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.github.libretube.reader.ArticleCoordinator
import com.github.libretube.reader.ReaderGraph
import com.github.libretube.reader.ReaderState
import com.github.libretube.reader.VideoHint
import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.ReadingPosition
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The single owner of reader state for [ArticleActivity]. It survives configuration changes, so
 * work in progress and the latest reading position are not lost on rotation.
 */
class ArticleViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val preferences = ReaderGraph.preferences(application)
    private val store = ReaderGraph.store(application)

    val coordinator = ArticleCoordinator(
        scope = viewModelScope,
        store = store,
        captions = ReaderGraph.captionSource(),
        service = ReaderGraph.api(application),
        consent = preferences,
        defaultSettings = preferences::articleSettings,
        serviceReady = preferences::isServiceConfigured
    )

    val state: StateFlow<ReaderState> get() = coordinator.state

    val showTranscript: StateFlow<Boolean> = savedState.getStateFlow(KEY_SHOW_TRANSCRIPT, false)

    private var latestPosition: ReadingPosition? = null
    private var pendingWrite: Job? = null

    fun open(videoId: String, hint: VideoHint) = coordinator.open(videoId, hint)

    fun setShowTranscript(show: Boolean) {
        savedState[KEY_SHOW_TRANSCRIPT] = show
    }

    fun rewrite(settings: ArticleSettings, saveAsDefault: Boolean) {
        if (saveAsDefault) preferences.saveDefaults(settings)
        coordinator.rewrite(settings)
    }

    /** Records a scroll position and writes it after a short pause in scrolling. */
    fun onPositionChanged(position: ReadingPosition) {
        if (position == latestPosition) return
        latestPosition = position
        pendingWrite?.cancel()
        pendingWrite = viewModelScope.launch {
            delay(POSITION_DEBOUNCE_MS)
            persist(position)
        }
    }

    /** Writes the latest position now, for example when the screen is paused. */
    fun flushPosition() {
        pendingWrite?.cancel()
        pendingWrite = null
        latestPosition?.let(::persist)
    }

    /** The position to restore for [record], preferring the in-memory one after recreation. */
    fun positionFor(record: ArticleRecord): ReadingPosition? {
        latestPosition?.takeIf { it.videoId == record.videoId && it.revision == record.revision }
            ?.let { return it }
        val ready = state.value as? ReaderState.Ready ?: return null
        return ready.savedPosition?.takeIf {
            ready.record.revision == record.revision && it.revision == record.revision
        }
    }

    fun deleteArticle(onDone: () -> Unit) {
        val videoId = coordinator.videoId ?: return
        pendingWrite?.cancel()
        latestPosition = null
        viewModelScope.launch {
            coordinator.stopForDeletion()
            store.deleteArticle(videoId)
            coordinator.reset()
            onDone()
        }
    }

    private fun persist(position: ReadingPosition) {
        // outlives the screen so a position recorded on exit is not lost
        ReaderGraph.appScope.launch { store.writePosition(position) }
    }

    override fun onCleared() {
        flushPosition()
        super.onCleared()
    }

    companion object {
        private const val KEY_SHOW_TRANSCRIPT = "show_transcript"
        const val POSITION_DEBOUNCE_MS = 600L
    }
}
