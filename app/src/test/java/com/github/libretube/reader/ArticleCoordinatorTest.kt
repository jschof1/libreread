package com.github.libretube.reader

import com.github.libretube.reader.api.ArticleService
import com.github.libretube.reader.api.GeneratedArticle
import com.github.libretube.reader.model.ArticleContent
import com.github.libretube.reader.model.ArticleDetail
import com.github.libretube.reader.model.ArticleSection
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.ReaderErrorKind
import com.github.libretube.reader.model.ReaderException
import com.github.libretube.reader.model.TranscriptRecord
import com.github.libretube.reader.model.TranscriptSource
import com.github.libretube.reader.source.CaptionSource
import com.github.libretube.reader.source.DeviceCaptionResult
import com.github.libretube.reader.store.ArticleStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArticleCoordinatorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val videoId = "dQw4w9WgXcQ"
    private lateinit var scope: CoroutineScope
    private lateinit var store: ArticleStore
    private val consent = FakeConsent(accepted = true)
    private val service = FakeService()
    private var captionResult: DeviceCaptionResult =
        DeviceCaptionResult.Found(ReaderFixtures.transcript())
    private val captionCalls = AtomicInteger()
    private val captions = CaptionSource { _, _ ->
        captionCalls.incrementAndGet()
        captionResult
    }
    private var revisions = 0
    @Volatile
    private var serviceReady = true

    private fun coordinator() = ArticleCoordinator(
        scope = scope,
        store = store,
        captions = captions,
        service = service,
        consent = consent,
        defaultSettings = { ArticleSettings() },
        clock = { 5000 },
        newRevision = { "rev-${++revisions}" },
        serviceReady = { serviceReady }
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = ArticleStore(folder.root)
    }

    @After
    fun tearDown() = scope.cancel()

    private suspend inline fun <reified T : ReaderState> ArticleCoordinator.awaitState(
        crossinline predicate: (T) -> Boolean = { true }
    ): T = withTimeout(5_000) { state.first { it is T && predicate(it as T) } as T }

    @Test
    fun `saved record opens without captions or AI calls`() = runBlocking {
        store.writeArticle(ReaderFixtures.record(videoId, "saved"))
        val c = coordinator()
        c.open(videoId)
        val ready = c.awaitState<ReaderState.Ready>()
        assertEquals("saved", ready.record.revision)
        assertEquals(0, captionCalls.get())
        assertEquals(0, service.articleCalls.get())
        assertEquals(0, service.transcribeCalls.get())
    }

    @Test
    fun `saved record opens when no service is set up`() = runBlocking {
        serviceReady = false
        store.writeArticle(ReaderFixtures.record(videoId, "saved"))
        val c = coordinator()
        c.open(videoId)
        val ready = c.awaitState<ReaderState.Ready>()
        assertEquals("saved", ready.record.revision)
        assertEquals(0, captionCalls.get())
    }

    @Test
    fun `missing service setup fails before captions or requests and recovers on retry`() = runBlocking {
        serviceReady = false
        val c = coordinator()
        c.open(videoId)
        val failed = c.awaitState<ReaderState.Failed>()
        assertEquals(ReaderErrorKind.NOT_CONFIGURED, failed.error.kind)
        assertFalse(failed.captionsUnavailable)
        assertEquals(0, captionCalls.get())
        assertEquals(0, service.transcribeCalls.get())
        assertEquals(0, service.articleCalls.get())

        serviceReady = true
        c.retry()
        val ready = c.awaitState<ReaderState.Ready>()
        assertEquals(ready.record, store.readArticle(videoId))
        assertEquals(1, captionCalls.get())
    }

    @Test
    fun `captions produce a saved article and the pending transcript is cleared`() = runBlocking {
        val c = coordinator()
        c.open(videoId)
        val ready = c.awaitState<ReaderState.Ready>()
        assertEquals("rev-1", ready.record.revision)
        assertEquals(TranscriptSource.CREATOR_CAPTIONS, ready.record.transcript.source)
        assertEquals(ready.record, store.readArticle(videoId))
        assertNull(store.readPendingTranscript(videoId))
        assertEquals(0, service.transcribeCalls.get())
        assertEquals("Original title", service.lastTitle)
    }

    @Test
    fun `missing captions use AI transcription only after consent`() = runBlocking {
        consent.accepted = false
        captionResult = DeviceCaptionResult.Unavailable(
            DeviceCaptionResult.Reason.NO_TRACKS, "Device title", "Device creator"
        )
        val c = coordinator()
        c.open(videoId)
        val prompt = c.awaitState<ReaderState.AwaitingConsent>()
        assertEquals(AiPurpose.TRANSCRIPTION, prompt.purpose)
        assertEquals(0, service.transcribeCalls.get())

        c.answerConsent(true)
        val ready = c.awaitState<ReaderState.Ready>()
        assertTrue(consent.accepted)
        assertEquals(TranscriptSource.AI_TRANSCRIPTION, ready.record.transcript.source)
        assertEquals("Device title", ready.record.originalTitle)
        assertEquals(1, service.transcribeCalls.get())
    }

    @Test
    fun `declining consent sends nothing and is recoverable`() = runBlocking {
        consent.accepted = false
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.AwaitingConsent>()
        c.answerConsent(false)
        val failed = c.awaitState<ReaderState.Failed>()
        assertEquals(ReaderErrorKind.CONSENT_DECLINED, failed.error.kind)
        assertTrue(failed.hasTranscript)
        assertEquals(0, service.articleCalls.get())
    }

    @Test
    fun `no captions and failed transcription never generate from metadata`() = runBlocking {
        captionResult = DeviceCaptionResult.Unavailable(DeviceCaptionResult.Reason.SOURCE_FAILED, "T", "C")
        service.transcribeError = ReaderException(ReaderErrorKind.SERVER, "unavailable", "transcription_unavailable")
        val c = coordinator()
        c.open(videoId)
        val failed = c.awaitState<ReaderState.Failed>()
        assertTrue(failed.captionsUnavailable)
        assertFalse(failed.hasTranscript)
        assertEquals("transcription_unavailable", failed.error.code)
        assertEquals(0, service.articleCalls.get())
        assertNull(store.readArticle(videoId))
    }

    @Test
    fun `retry after a failed article reuses the stored transcript`() = runBlocking {
        service.articleError = ReaderException(ReaderErrorKind.NETWORK)
        val c = coordinator()
        c.open(videoId)
        val failed = c.awaitState<ReaderState.Failed>()
        assertTrue(failed.hasTranscript)

        service.articleError = null
        c.retry()
        c.awaitState<ReaderState.Ready>()
        assertEquals(1, captionCalls.get())
    }

    @Test
    fun `failed rewrite keeps the saved article on screen and on disk`() = runBlocking {
        val saved = ReaderFixtures.record(videoId, "saved")
        store.writeArticle(saved)
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.Ready>()

        service.articleError = ReaderException(ReaderErrorKind.INVALID_RESPONSE)
        c.rewrite(ArticleSettings("technical", "", ArticleDetail.BRIEF))
        val ready = c.awaitState<ReaderState.Ready> { it.rewrite is RewriteStatus.Failed }
        assertEquals(saved, ready.record)
        assertEquals(saved, store.readArticle(videoId))
    }

    @Test
    fun `deleting an article while its rewrite is pending does not recreate it`() = runBlocking {
        val saved = ReaderFixtures.record(videoId, "saved")
        store.writeArticle(saved)
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.Ready>()
        service.gate = CompletableDeferred()
        c.rewrite(ArticleSettings())
        c.awaitState<ReaderState.Ready> { it.rewrite == RewriteStatus.InProgress }
        store.deleteArticle(videoId)
        service.gate!!.complete(Unit)
        c.awaitState<ReaderState.Ready> { it.rewrite is RewriteStatus.Failed }
        assertNull(store.readArticle(videoId))
    }

    @Test
    fun `cancel after the save boundary keeps the displayed and durable revision equal`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val release = CountDownLatch(1)
        try {
            store = ArticleStore(folder.root, ioDispatcher = dispatcher)
            val saved = ReaderFixtures.record(videoId, "saved")
            store.writeArticle(saved)
            val c = coordinator()
            c.open(videoId)
            c.awaitState<ReaderState.Ready>()
            service.gate = CompletableDeferred()
            c.rewrite(ArticleSettings())
            c.awaitState<ReaderState.Ready> { it.rewrite == RewriteStatus.InProgress }
            val blocked = CountDownLatch(1)
            executor.submit {
                blocked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            service.gate!!.complete(Unit)
            c.awaitState<ReaderState.Ready> { it.rewrite == RewriteStatus.Committing }
            c.cancel()
            assertEquals(RewriteStatus.Committing, (c.state.value as ReaderState.Ready).rewrite)
            release.countDown()
            val ready = c.awaitState<ReaderState.Ready> { it.record.revision != saved.revision }
            assertEquals(ready.record, store.readArticle(videoId))
        } finally {
            release.countDown()
            dispatcher.close()
        }
    }

    @Test
    fun `successful rewrite uses the saved transcript and creates a new revision`() = runBlocking {
        val saved = ReaderFixtures.record(videoId, "saved")
        store.writeArticle(saved)
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.Ready>()

        val settings = ArticleSettings("narrative", "Explain jargon.", ArticleDetail.BALANCED)
        c.rewrite(settings)
        val ready = c.awaitState<ReaderState.Ready> { it.record.revision != "saved" }
        assertEquals(settings, ready.record.settings)
        assertEquals(saved.transcript, ready.record.transcript)
        assertNull(ready.savedPosition)
        assertEquals(settings, service.lastSettings)
        assertEquals(0, captionCalls.get())
        assertEquals(ready.record, store.readArticle(videoId))
    }

    @Test
    fun `cancel during writing stops and later results are discarded`() = runBlocking {
        service.gate = CompletableDeferred()
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.Preparing> { it.stage == PrepStage.WRITING_ARTICLE }
        c.cancel()
        val failed = c.awaitState<ReaderState.Failed>()
        assertEquals(ReaderErrorKind.CANCELLED, failed.error.kind)
        service.gate!!.complete(Unit)
        kotlinx.coroutines.delay(200)
        assertTrue(c.state.value is ReaderState.Failed)
        assertNull(store.readArticle(videoId))
    }

    @Test
    fun `oversized transcript fails explicitly without truncation`() = runBlocking {
        captionResult = DeviceCaptionResult.Found(
            ReaderFixtures.transcript(text = "a".repeat(180_001))
        )
        val c = coordinator()
        c.open(videoId)
        val failed = c.awaitState<ReaderState.Failed>()
        assertEquals(ReaderErrorKind.TRANSCRIPT_TOO_LONG, failed.error.kind)
        assertEquals("180001", failed.error.detail)
        assertEquals(0, service.articleCalls.get())
        assertFalse(failed.hasTranscript)
        assertNull(store.readPendingTranscript(videoId))
        c.useSuppliedTranscript("A shorter source that fits the limit.")
        val recovered = c.awaitState<ReaderState.Ready>()
        assertEquals(TranscriptSource.USER_SUPPLIED, recovered.record.transcript.source)
    }

    @Test
    fun `pasted transcript is labelled as user supplied`() = runBlocking {
        captionResult = DeviceCaptionResult.Unavailable(DeviceCaptionResult.Reason.NO_TRACKS, "T", "C")
        service.transcribeError = ReaderException(ReaderErrorKind.SERVER)
        val c = coordinator()
        c.open(videoId)
        c.awaitState<ReaderState.Failed>()
        c.useSuppliedTranscript("First paragraph.\n\nSecond paragraph.")
        val ready = c.awaitState<ReaderState.Ready>()
        assertEquals(TranscriptSource.USER_SUPPLIED, ready.record.transcript.source)
        assertEquals("T", ready.record.originalTitle)
    }

    @Test
    fun `invalid video id is reported without touching storage`() = runBlocking {
        val c = coordinator()
        c.open("../bad")
        val failed = c.awaitState<ReaderState.Failed>()
        assertEquals(ReaderErrorKind.INVALID_VIDEO, failed.error.kind)
    }

    class FakeConsent(@Volatile var accepted: Boolean) : ConsentStore {
        override fun isAccepted() = accepted
        override fun accept() {
            accepted = true
        }
    }

    class FakeService : ArticleService {
        val articleCalls = AtomicInteger()
        val transcribeCalls = AtomicInteger()

        @Volatile var articleError: ReaderException? = null
        @Volatile var transcribeError: ReaderException? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var lastSettings: ArticleSettings? = null
        @Volatile var lastTitle: String? = null

        override suspend fun transcribe(videoId: String): TranscriptRecord {
            transcribeCalls.incrementAndGet()
            transcribeError?.let { throw it }
            return ReaderFixtures.transcript(videoId).copy(
                source = TranscriptSource.AI_TRANSCRIPTION,
                autoGenerated = true,
                title = "",
                creator = "",
                model = "gemini"
            )
        }

        override suspend fun writeArticle(
            transcript: TranscriptRecord,
            title: String,
            creator: String,
            settings: ArticleSettings
        ): GeneratedArticle {
            articleCalls.incrementAndGet()
            gate?.await()
            articleError?.let { throw it }
            lastSettings = settings
            lastTitle = title
            return GeneratedArticle(
                ArticleContent("Generated", listOf(ArticleSection("H", listOf("P")))),
                "model",
                settings.styleInstructions()
            )
        }
    }
}
