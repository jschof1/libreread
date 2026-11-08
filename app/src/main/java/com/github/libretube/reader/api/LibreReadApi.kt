package com.github.libretube.reader.api

import com.github.libretube.reader.model.ArticleContent
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.ReaderErrorKind
import com.github.libretube.reader.model.ReaderException
import com.github.libretube.reader.model.StylePresets
import com.github.libretube.reader.model.TranscriptRecord
import com.github.libretube.reader.store.ReaderJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException

data class ServiceConfig(val baseUrl: String, val deviceToken: String)

data class GeneratedArticle(
    val content: ArticleContent,
    val model: String,
    val style: String
)

/** The AI-backed operations the reader needs. */
interface ArticleService {
    suspend fun transcribe(videoId: String): TranscriptRecord

    suspend fun writeArticle(
        transcript: TranscriptRecord,
        title: String,
        creator: String,
        settings: ArticleSettings
    ): GeneratedArticle
}

/**
 * Client for the private LibreRead article service. Requests and responses are never logged.
 */
class LibreReadApi(
    private val client: OkHttpClient,
    private val config: () -> ServiceConfig,
    private val json: Json = ReaderJson,
    private val clock: () -> Long = System::currentTimeMillis
) : ArticleService {

    override suspend fun transcribe(videoId: String): TranscriptRecord = withContext(Dispatchers.Default) {
        val response = post(
            "v1/transcripts",
            json.encodeToString(TranscriptRequestDto.serializer(), TranscriptRequestDto(videoId)),
            TranscriptResponseDto.serializer()
        )
        ArticleValidator.validateTranscript(videoId, response, clock())
    }

    override suspend fun writeArticle(
        transcript: TranscriptRecord,
        title: String,
        creator: String,
        settings: ArticleSettings
    ): GeneratedArticle = withContext(Dispatchers.Default) {
        val text = transcript.plainText()
        if (text.length > TRANSCRIPT_LIMIT) {
            throw ReaderException(ReaderErrorKind.TRANSCRIPT_TOO_LONG, text.length.toString())
        }
        val style = settings.styleInstructions()
        if (style.length > StylePresets.MAX_STYLE_LENGTH) {
            throw ReaderException(ReaderErrorKind.INVALID_RESPONSE, "style instructions too long")
        }
        val request = ArticleRequestDto(
            videoId = transcript.videoId,
            title = title,
            creator = creator,
            transcript = text,
            style = style,
            detail = settings.detail.wireName
        )
        val response = post(
            "v1/articles",
            json.encodeToString(ArticleRequestDto.serializer(), request),
            ArticleResponseDto.serializer()
        )
        val (content, model) = ArticleValidator.validateArticle(response)
        GeneratedArticle(content, model, style)
    }

    private suspend fun <T> post(path: String, body: String, serializer: KSerializer<T>): T {
        val current = config()
        val base = normalizeBaseUrl(current.baseUrl)
            ?: throw ReaderException(ReaderErrorKind.NOT_CONFIGURED, "service URL")
        val token = current.deviceToken.trim()
        if (token.isEmpty()) throw ReaderException(ReaderErrorKind.NOT_CONFIGURED, "device credential")

        val request = Request.Builder()
            .url(base.newBuilder().addPathSegments(path).build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val result = try {
            client.newCall(request).executeBounded(MAX_RESPONSE_BYTES)
        } catch (e: ResponseTooLargeException) {
            throw ReaderException(ReaderErrorKind.INVALID_RESPONSE, "response too large")
        } catch (e: UnknownHostException) {
            throw ReaderException(ReaderErrorKind.NETWORK)
        } catch (e: InterruptedIOException) {
            throw ReaderException(ReaderErrorKind.TIMEOUT)
        } catch (e: IOException) {
            throw ReaderException(ReaderErrorKind.NETWORK)
        }

        if (!result.isSuccessful) throw errorFor(result)

        return try {
            json.decodeFromString(serializer, result.body.decodeToString())
        } catch (e: SerializationException) {
            throw ReaderException(ReaderErrorKind.INVALID_RESPONSE, "malformed JSON")
        } catch (e: IllegalArgumentException) {
            throw ReaderException(ReaderErrorKind.INVALID_RESPONSE, "malformed JSON")
        }
    }

    private fun errorFor(result: HttpResult): ReaderException {
        val error = try {
            json.decodeFromString(ErrorDto.serializer(), result.body.decodeToString())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val detail = error?.error?.trim()?.take(MAX_ERROR_DETAIL)?.takeIf { it.isNotEmpty() }
        val code = error?.code?.trim()?.take(64)?.takeIf { it.isNotEmpty() }
        val kind = when {
            code?.uppercase() in setOf("TRANSCRIPT_TOO_LONG", "TRANSCRIPT_TOO_LARGE") || result.code == 413 -> ReaderErrorKind.TRANSCRIPT_TOO_LONG
            result.code == 401 || result.code == 403 -> ReaderErrorKind.AUTH
            result.code == 408 || result.code == 504 || result.code in 520..524 -> ReaderErrorKind.TIMEOUT
            else -> ReaderErrorKind.SERVER
        }
        return ReaderException(kind, detail, code ?: "http_${result.code}")
    }

    companion object {
        const val TRANSCRIPT_LIMIT = 180_000
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private const val MAX_ERROR_DETAIL = 300
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Accepts an HTTPS service URL without embedded credentials, query, or fragment. */
        fun normalizeBaseUrl(raw: String): HttpUrl? {
            val url = raw.trim().toHttpUrlOrNull() ?: return null
            if (url.scheme != "https") return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.query != null || url.fragment != null) return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            return url.newBuilder()
                .encodedPath("/")
                .apply { segments.forEach { addPathSegment(it) } }
                .build()
        }
    }
}
