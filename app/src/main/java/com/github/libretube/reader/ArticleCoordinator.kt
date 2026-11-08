package com.github.libretube.reader

import com.github.libretube.reader.api.ArticleService
import com.github.libretube.reader.api.LibreReadApi
import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.ReaderError
import com.github.libretube.reader.model.ReaderErrorKind
import com.github.libretube.reader.model.ReaderException
import com.github.libretube.reader.model.TranscriptRecord
import com.github.libretube.reader.model.TranscriptSource
import com.github.libretube.reader.model.TranscriptText
import com.github.libretube.reader.source.CaptionSource
import com.github.libretube.reader.source.CaptionStage
import com.github.libretube.reader.source.DeviceCaptionResult
import com.github.libretube.reader.store.ArticleStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Whether the user has accepted the AI processing notice. */
interface ConsentStore {
    fun isAccepted(): Boolean
    fun accept()
}

/** Title and creator known before loading, for example from the list item that was tapped. */
data class VideoHint(val title: String? = null, val creator: String? = null)

/**
 * Owns the reader workflow for one video: saved record, device captions, AI transcription
 * fallback, article generation, rewrite and cancellation.
 *
 * Saved records are always shown without any network or AI call. Each run carries a generation
 * number, and results from a superseded run are discarded.
 *
 * [serviceReady] reports whether an article service is set up. When it is not, anything that
 * needs the service fails with [ReaderErrorKind.NOT_CONFIGURED] before captions are fetched or a
 * request is made, while saved records still open.
 */
class ArticleCoordinator(
    private val scope: CoroutineScope,
    private val store: ArticleStore,
    private val captions: CaptionSource,
    private val service: ArticleService,
    private val consent: ConsentStore,
    private val defaultSettings: () -> ArticleSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newRevision: () -> String = { UUID.randomUUID().toString() },
    private val serviceReady: () -> Boolean = { true }
) {
    private val _state = MutableStateFlow<ReaderState>(ReaderState.Idle)
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val generation = AtomicInteger(0)
    private var job: Job? = null
    private val transitions = Any()

    @Volatile
    private var pendingConsent: CompletableDeferred<Boolean>? = null

    var videoId: String? = null
        private set
    private var hint = VideoHint()

    /** Metadata learned while looking for captions, reused for a pasted transcript. */
    @Volatile
    private var knownTitle: String? = null

    @Volatile
    private var knownCreator: String? = null

    /** Starts loading [videoId]. Calling again for the same video keeps the current state. */
    fun open(videoId: String, hint: VideoHint = VideoHint()) {
        if (this.videoId == videoId && _state.value != ReaderState.Idle) return
        this.videoId = videoId
        this.hint = hint
        knownTitle = hint.title?.takeIf { it.isNotBlank() }
        knownCreator = hint.creator?.takeIf { it.isNotBlank() }
        launchRun { gen -> load(gen, videoId) }
    }

    fun retry() {
        val id = videoId ?: return
        launchRun { gen -> load(gen, id) }
    }

    /** Stops the current preparation or rewrite. A saved article stays visible. */
    fun cancel() = synchronized(transitions) {
        val currentState = _state.value
        if (currentState is ReaderState.Preparing && currentState.stage == PrepStage.SAVING ||
            currentState is ReaderState.Ready && currentState.rewrite == RewriteStatus.Committing
        ) return@synchronized
        job?.cancel()
        generation.incrementAndGet()
        pendingConsent?.complete(false)
        pendingConsent = null
        _state.value = when (val current = _state.value) {
            is ReaderState.Ready -> current.copy(rewrite = RewriteStatus.Idle)
            is ReaderState.Failed -> current
            else -> ReaderState.Failed(
                ReaderError(ReaderErrorKind.CANCELLED),
                captionsUnavailable = false,
                hasTranscript = false
            )
        }
    }

    suspend fun stopForDeletion() {
        job?.cancelAndJoin()
        generation.incrementAndGet()
        pendingConsent?.complete(false)
        pendingConsent = null
    }

    fun answerConsent(accepted: Boolean) {
        if (accepted) consent.accept()
        pendingConsent?.complete(accepted)
    }

    /** Writes a new article from the saved transcript. The old article stays until success. */
    fun rewrite(settings: ArticleSettings) {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.rewrite != RewriteStatus.Idle && ready.rewrite !is RewriteStatus.Failed) return
        val previous = ready.record
        launchRun { gen ->
            try {
                requireService()
                requireConsent(gen) { publish(gen, ready.copy(rewrite = RewriteStatus.AwaitingConsent)) }
                publish(gen, ready.copy(rewrite = RewriteStatus.InProgress))
                val record = generate(previous.transcript, settings, previous)
                currentCoroutineContext().ensureActive()
                beginCommit(gen, ready.copy(rewrite = RewriteStatus.Committing))
                withContext(NonCancellable) {
                    if (store.replaceArticleIfRevision(record, previous.revision)) {
                        publish(gen, ReaderState.Ready(record, savedPosition = null))
                    } else {
                        publish(gen, ready.copy(rewrite = RewriteStatus.Failed(ReaderError(
                            ReaderErrorKind.STORAGE,
                            "The saved article was removed or changed. The rewrite was not saved."
                        ))))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderException) {
                publish(gen, ready.copy(rewrite = RewriteStatus.Failed(e.error)))
            } catch (e: IOException) {
                publish(gen, ready.copy(rewrite = RewriteStatus.Failed(ReaderError(ReaderErrorKind.STORAGE))))
            } catch (e: Exception) {
                publish(gen, ready.copy(rewrite = RewriteStatus.Failed(ReaderError(ReaderErrorKind.UNKNOWN))))
            }
        }
    }

    fun dismissRewriteError() {
        val ready = _state.value as? ReaderState.Ready ?: return
        if (ready.rewrite is RewriteStatus.Failed) _state.value = ready.copy(rewrite = RewriteStatus.Idle)
    }

    /** Uses text the user pasted as the transcript. It is labelled as user-supplied. */
    fun useSuppliedTranscript(text: String) {
        val id = videoId ?: return
        val segments = TranscriptText.untimedSegments(text)
        if (segments.isEmpty()) return
        launchRun { gen ->
            val transcript = TranscriptRecord(
                videoId = id,
                title = knownTitle.orEmpty(),
                creator = knownCreator.orEmpty(),
                language = null,
                source = TranscriptSource.USER_SUPPLIED,
                autoGenerated = false,
                segments = segments,
                retrievedAtMs = clock()
            )
            savePendingTranscript(transcript)
            writeFirstArticle(gen, transcript)
        }
    }

    /** Notifies the coordinator that the saved record was deleted elsewhere. */
    fun reset() {
        job?.cancel()
        generation.incrementAndGet()
        videoId = null
        _state.value = ReaderState.Idle
    }

    private fun launchRun(block: suspend (Int) -> Unit) = synchronized(transitions) {
        job?.cancel()
        pendingConsent?.complete(false)
        pendingConsent = null
        val gen = generation.incrementAndGet()
        job = scope.launch {
            try {
                block(gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReaderException) {
                fail(gen, e.error)
            } catch (e: IOException) {
                fail(gen, ReaderError(ReaderErrorKind.STORAGE))
            } catch (e: Exception) {
                fail(gen, ReaderError(ReaderErrorKind.UNKNOWN))
            }
        }
    }

    @Volatile
    private var captionsUnavailable = false

    private suspend fun fail(gen: Int, error: ReaderError) {
        val id = videoId
        val hasTranscript = try {
            id != null && ArticleStore.isValidVideoId(id) && store.readPendingTranscript(id) != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        publish(gen, ReaderState.Failed(error, captionsUnavailable, hasTranscript))
    }

    private fun publish(gen: Int, state: ReaderState) = synchronized(transitions) {
        if (gen == generation.get()) _state.value = state
    }

    private fun beginCommit(gen: Int, state: ReaderState) = synchronized(transitions) {
        if (gen != generation.get()) throw CancellationException("superseded")
        _state.value = state
    }

    private suspend fun load(gen: Int, videoId: String) {
        captionsUnavailable = false
        if (!ArticleStore.isValidVideoId(videoId)) {
            throw ReaderException(ReaderErrorKind.INVALID_VIDEO)
        }
        publish(gen, ReaderState.Preparing(PrepStage.CHECKING_SAVED))
        store.readArticle(videoId)?.let { record ->
            val position = store.readPosition(videoId)?.takeIf { it.revision == record.revision }
            publish(gen, ReaderState.Ready(record, position))
            return
        }

        requireService()
        val transcript = store.readPendingTranscript(videoId) ?: acquireTranscript(gen, videoId)
        if (transcript.plainText().length > LibreReadApi.TRANSCRIPT_LIMIT) {
            store.deletePendingTranscript(videoId)
            throw ReaderException(ReaderErrorKind.TRANSCRIPT_TOO_LONG, transcript.plainText().length.toString())
        }
        writeFirstArticle(gen, transcript)
    }

    private suspend fun acquireTranscript(gen: Int, videoId: String): TranscriptRecord {
        publish(gen, ReaderState.Preparing(PrepStage.LOADING_VIDEO))
        val result = captions.obtain(videoId) { stage ->
            publish(
                gen,
                ReaderState.Preparing(
                    when (stage) {
                        CaptionStage.LOADING_VIDEO -> PrepStage.LOADING_VIDEO
                        CaptionStage.FETCHING_CAPTIONS -> PrepStage.FETCHING_CAPTIONS
                    }
                )
            )
        }
        val transcript = when (result) {
            is DeviceCaptionResult.Found -> result.transcript

            is DeviceCaptionResult.Unavailable -> {
                captionsUnavailable = true
                result.title?.takeIf { it.isNotBlank() }?.let { knownTitle = it }
                result.creator?.takeIf { it.isNotBlank() }?.let { knownCreator = it }
                requireConsent(gen) { publish(gen, ReaderState.AwaitingConsent(AiPurpose.TRANSCRIPTION)) }
                publish(gen, ReaderState.Preparing(PrepStage.TRANSCRIBING))
                val remote = service.transcribe(videoId)
                remote.copy(
                    title = remote.title.ifBlank { knownTitle.orEmpty() },
                    creator = remote.creator.ifBlank { knownCreator.orEmpty() }
                )
            }
        }
        savePendingTranscript(transcript)
        return transcript
    }

    private suspend fun savePendingTranscript(transcript: TranscriptRecord) {
        val length = transcript.plainText().length
        if (length > LibreReadApi.TRANSCRIPT_LIMIT) {
            throw ReaderException(ReaderErrorKind.TRANSCRIPT_TOO_LONG, length.toString())
        }
        store.writePendingTranscript(transcript)
    }

    private suspend fun writeFirstArticle(gen: Int, transcript: TranscriptRecord) {
        requireService()
        requireConsent(gen) { publish(gen, ReaderState.AwaitingConsent(AiPurpose.ARTICLE)) }
        publish(gen, ReaderState.Preparing(PrepStage.WRITING_ARTICLE))
        val record = generate(transcript, defaultSettings(), previous = null)
        currentCoroutineContext().ensureActive()
        beginCommit(gen, ReaderState.Preparing(PrepStage.SAVING))
        withContext(NonCancellable) {
            store.writeArticle(record)
            store.deletePendingTranscript(transcript.videoId)
            publish(gen, ReaderState.Ready(record, savedPosition = null))
        }
    }

    /** Calls the service and builds a validated record. Nothing is saved here. */
    private suspend fun generate(
        transcript: TranscriptRecord,
        settings: ArticleSettings,
        previous: ArticleRecord?
    ): ArticleRecord {
        val text = transcript.plainText()
        if (text.isBlank()) throw ReaderException(ReaderErrorKind.NO_TRANSCRIPT)
        if (text.length > LibreReadApi.TRANSCRIPT_LIMIT) {
            throw ReaderException(ReaderErrorKind.TRANSCRIPT_TOO_LONG, text.length.toString())
        }
        val title = previous?.originalTitle ?: transcript.title.ifBlank { knownTitle.orEmpty() }
        val creator = previous?.creator ?: transcript.creator.ifBlank { knownCreator.orEmpty() }
        val generated = service.writeArticle(
            transcript = transcript,
            title = title.ifBlank { UNKNOWN_TITLE },
            creator = creator.ifBlank { UNKNOWN_CREATOR },
            settings = settings
        )
        return ArticleRecord(
            videoId = transcript.videoId,
            revision = newRevision(),
            sourceUrl = ArticleRecord.sourceUrlFor(transcript.videoId),
            originalTitle = title,
            creator = creator,
            article = generated.content,
            settings = settings,
            style = generated.style,
            model = generated.model,
            createdAtMs = clock(),
            transcript = transcript
        )
    }

    private fun requireService() {
        if (!serviceReady()) throw ReaderException(ReaderErrorKind.NOT_CONFIGURED)
    }

    private suspend fun requireConsent(gen: Int, showPrompt: () -> Unit) {
        if (consent.isAccepted()) return
        val answer = CompletableDeferred<Boolean>()
        pendingConsent = answer
        showPrompt()
        val accepted = try {
            answer.await()
        } finally {
            if (pendingConsent === answer) pendingConsent = null
        }
        if (!accepted) throw ReaderException(ReaderErrorKind.CONSENT_DECLINED)
        if (gen != generation.get()) throw CancellationException("superseded")
    }

    companion object {
        const val UNKNOWN_TITLE = "Untitled video"
        const val UNKNOWN_CREATOR = "Unknown creator"
    }
}
