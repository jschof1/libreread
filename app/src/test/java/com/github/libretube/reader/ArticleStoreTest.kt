package com.github.libretube.reader

import com.github.libretube.reader.model.ReadingPosition
import com.github.libretube.reader.store.ArticleStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArticleStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var store: ArticleStore

    @Before
    fun setUp() {
        store = ArticleStore(folder.root)
    }

    @Test
    fun `article and transcript round-trip in one record`() = runBlocking {
        val record = ReaderFixtures.record()
        store.writeArticle(record)
        assertEquals(record, store.readArticle(record.videoId))
        assertEquals(record, ArticleStore(folder.root).readArticle(record.videoId))
    }

    @Test
    fun `reading while rewriting cannot remove the active atomic write`() = runBlocking {
        val original = ReaderFixtures.record()
        store.writeArticle(original)
        coroutineScope {
            launch(Dispatchers.IO) {
                repeat(100) { revision ->
                    store.writeArticle(original.copy(revision = "revision-$revision"))
                }
            }
            repeat(3) {
                launch(Dispatchers.IO) {
                    repeat(100) {
                        assertEquals(original.videoId, store.readArticle(original.videoId)?.videoId)
                        assertEquals(original.videoId, store.listArticles().single().videoId)
                    }
                }
            }
        }
        assertEquals("revision-99", store.readArticle(original.videoId)?.revision)
    }

    @Test
    fun `an interrupted write leaves the previous record readable`() = runBlocking {
        val record = ReaderFixtures.record()
        store.writeArticle(record)
        // simulate a crash mid-write: AtomicFile writes to "<name>.new" before renaming
        File(folder.root, "articles/${record.videoId}.json.new").writeText("{\"truncated")
        assertEquals(record, store.readArticle(record.videoId))
    }

    @Test
    fun `corrupt or mismatched files read as absent`() = runBlocking {
        File(folder.root, "articles").mkdirs()
        File(folder.root, "articles/aaaaaaaaaaa.json").writeText("not json")
        assertNull(store.readArticle("aaaaaaaaaaa"))
        // a record stored under the wrong name is ignored
        store.writeArticle(ReaderFixtures.record(videoId = "bbbbbbbbbbb"))
        File(folder.root, "articles/bbbbbbbbbbb.json")
            .copyTo(File(folder.root, "articles/ccccccccccc.json"))
        assertNull(store.readArticle("ccccccccccc"))
    }

    @Test
    fun `positions are stored separately and tied to the revision in listings`() = runBlocking {
        val record = ReaderFixtures.record(revision = "r1")
        store.writeArticle(record)
        val articleBytes = File(folder.root, "articles/${record.videoId}.json").readBytes()

        store.writePosition(ReadingPosition(record.videoId, "r1", 3, 12, 0.5f, 5))
        assertEquals(3, store.readPosition(record.videoId)?.blockIndex)
        // writing the position never rewrites the article file
        assertEquals(articleBytes.toList(), File(folder.root, "articles/${record.videoId}.json").readBytes().toList())
        assertEquals(0.5f, store.listArticles().single().progress)

        store.writeArticle(record.copy(revision = "r2"))
        assertNull(store.listArticles().single().progress)
    }

    @Test
    fun `pending transcripts and deletion`() = runBlocking {
        val transcript = ReaderFixtures.transcript()
        store.writePendingTranscript(transcript)
        assertEquals(transcript, store.readPendingTranscript(transcript.videoId))
        store.writeArticle(ReaderFixtures.record())
        store.deleteArticle(transcript.videoId)
        assertNull(store.readArticle(transcript.videoId))
        assertNull(store.readPendingTranscript(transcript.videoId))
        assertEquals(emptyList<Any>(), store.listArticles())
    }

    @Test
    fun `a rewrite cannot recreate a deleted article or replace another revision`() = runBlocking {
        val original = ReaderFixtures.record(revision = "original")
        val rewrite = original.copy(revision = "rewrite")
        store.writeArticle(original)
        store.deleteArticle(original.videoId)
        assertFalse(store.replaceArticleIfRevision(rewrite, original.revision))
        assertNull(store.readArticle(original.videoId))

        val newer = original.copy(revision = "newer")
        store.writeArticle(newer)
        assertFalse(store.replaceArticleIfRevision(rewrite, original.revision))
        assertEquals(newer, store.readArticle(original.videoId))
    }

    @Test
    fun `listing is newest first`() = runBlocking {
        store.writeArticle(ReaderFixtures.record(videoId = "older000000").copy(createdAtMs = 1))
        store.writeArticle(ReaderFixtures.record(videoId = "newer000000").copy(createdAtMs = 2))
        assertEquals(listOf("newer000000", "older000000"), store.listArticles().map { it.videoId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal ids are refused`() {
        runBlocking { store.readArticle("../../etc") }
    }
}
