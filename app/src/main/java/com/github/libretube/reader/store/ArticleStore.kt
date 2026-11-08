package com.github.libretube.reader.store

import androidx.core.util.AtomicFile
import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.ArticleSummary
import com.github.libretube.reader.model.ReadingPosition
import com.github.libretube.reader.model.TranscriptRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal persistent store for the reader, one JSON file per video.
 *
 * - `articles/<id>.json`: the completed [ArticleRecord], article and transcript together.
 * - `transcripts/<id>.json`: a transcript waiting for its first article, so that a retry does not
 *   fetch or transcribe the source again.
 * - `positions/<id>.json`: the [ReadingPosition], kept apart so frequent writes never touch the
 *   article file.
 *
 * Every write goes through [AtomicFile], so a crash or failure mid-write leaves the previous file
 * intact. Reads and writes for the same video are serialised with a per-video mutex.
 *
 * Callers should place [root] in `Context.noBackupFilesDir`: transcripts are not backed up.
 */
class ArticleStore(
    private val root: File,
    private val json: Json = ReaderJson,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val articlesDir get() = File(root, "articles")
    private val transcriptsDir get() = File(root, "transcripts")
    private val positionsDir get() = File(root, "positions")

    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(videoId: String) = locks.getOrPut(videoId) { Mutex() }

    suspend fun readArticle(videoId: String): ArticleRecord? = withContext(ioDispatcher) {
        lockFor(videoId).withLock {
            readJson(articleFile(videoId), ArticleRecord.serializer())
                ?.takeIf { it.videoId == videoId && it.schemaVersion <= ArticleRecord.SCHEMA_VERSION }
        }
    }

    suspend fun writeArticle(record: ArticleRecord) = lockFor(record.videoId).withLock {
        withContext(ioDispatcher) {
            writeJson(articleFile(record.videoId), ArticleRecord.serializer(), record)
        }
    }

    suspend fun replaceArticleIfRevision(record: ArticleRecord, expectedRevision: String): Boolean =
        lockFor(record.videoId).withLock {
            withContext(ioDispatcher) {
                val current = readJson(articleFile(record.videoId), ArticleRecord.serializer())
                if (current?.revision != expectedRevision) return@withContext false
                writeJson(articleFile(record.videoId), ArticleRecord.serializer(), record)
                true
            }
        }

    suspend fun readPendingTranscript(videoId: String): TranscriptRecord? =
        withContext(ioDispatcher) {
            lockFor(videoId).withLock {
                readJson(transcriptFile(videoId), TranscriptRecord.serializer())
                    ?.takeIf { it.videoId == videoId }
            }
        }

    suspend fun writePendingTranscript(transcript: TranscriptRecord) =
        lockFor(transcript.videoId).withLock {
            withContext(ioDispatcher) {
                writeJson(transcriptFile(transcript.videoId), TranscriptRecord.serializer(), transcript)
            }
        }

    suspend fun deletePendingTranscript(videoId: String) = lockFor(videoId).withLock {
        withContext(ioDispatcher) { AtomicFile(transcriptFile(videoId)).delete() }
    }

    suspend fun readPosition(videoId: String): ReadingPosition? = withContext(ioDispatcher) {
        lockFor(videoId).withLock {
            readJson(positionFile(videoId), ReadingPosition.serializer())
                ?.takeIf { it.videoId == videoId }
        }
    }

    suspend fun writePosition(position: ReadingPosition) = lockFor(position.videoId).withLock {
        withContext(ioDispatcher) {
            writeJson(positionFile(position.videoId), ReadingPosition.serializer(), position)
        }
    }

    suspend fun deleteArticle(videoId: String) = lockFor(videoId).withLock {
        withContext(ioDispatcher) {
            AtomicFile(articleFile(videoId)).delete()
            AtomicFile(positionFile(videoId)).delete()
            AtomicFile(transcriptFile(videoId)).delete()
        }
    }

    /** All saved articles, newest first. Unreadable files are skipped. */
    suspend fun listArticles(): List<ArticleSummary> = withContext(ioDispatcher) {
        val files = articlesDir.listFiles { file -> file.name.endsWith(JSON_SUFFIX) }.orEmpty()
        files.mapNotNull { file ->
            val videoId = file.name.removeSuffix(JSON_SUFFIX)
            if (!isValidVideoId(videoId)) return@mapNotNull null
            lockFor(videoId).withLock {
                val record = readJson(file, ArticleRecord.serializer())
                    ?.takeIf { it.videoId == videoId } ?: return@withLock null
                val position = readJson(positionFile(videoId), ReadingPosition.serializer())
                    ?.takeIf { it.revision == record.revision }
                ArticleSummary(
                    videoId = record.videoId,
                    title = record.article.title,
                    originalTitle = record.originalTitle,
                    creator = record.creator,
                    source = record.transcript.source,
                    createdAtMs = record.createdAtMs,
                    progress = position?.progress
                )
            }
        }.sortedByDescending { it.createdAtMs }
    }

    private fun articleFile(videoId: String) = File(articlesDir, fileName(videoId))
    private fun transcriptFile(videoId: String) = File(transcriptsDir, fileName(videoId))
    private fun positionFile(videoId: String) = File(positionsDir, fileName(videoId))

    private fun fileName(videoId: String): String {
        require(isValidVideoId(videoId)) { "Invalid video id" }
        return videoId + JSON_SUFFIX
    }

    private fun <T> readJson(file: File, serializer: KSerializer<T>): T? {
        val atomicFile = AtomicFile(file)
        // AtomicFile.openRead discards an unfinished ".new" file and recovers a legacy backup
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return try {
            json.decodeFromString(serializer, atomicFile.readFully().decodeToString())
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun <T> writeJson(file: File, serializer: KSerializer<T>, value: T) {
        val bytes = json.encodeToString(serializer, value).encodeToByteArray()
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Throwable) {
            atomicFile.failWrite(stream)
            throw e
        }
    }

    companion object {
        private const val JSON_SUFFIX = ".json"
        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidVideoId(videoId: String) = VIDEO_ID.matches(videoId)
    }
}

val ReaderJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
