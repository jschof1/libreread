package com.github.libretube.reader.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully read and closed HTTP response. */
class HttpResult(
    val code: Int,
    val contentType: String?,
    val body: ByteArray
) {
    val isSuccessful get() = code in 200..299
}

class ResponseTooLargeException : IOException("Response exceeded the size limit")

/**
 * Executes the call asynchronously and reads at most [maxBytes] of the body.
 *
 * Coroutine cancellation cancels the OkHttp call, which aborts both the request and any body
 * read in progress. The response is always closed before this returns.
 */
suspend fun Call.executeBounded(maxBytes: Long): HttpResult =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // ignored by the continuation if it was already cancelled
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        HttpResult(
                            code = it.code,
                            contentType = it.header("Content-Type"),
                            body = it.body?.source()?.readBounded(maxBytes) ?: ByteArray(0)
                        )
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                    return
                }
                continuation.resume(result)
            }
        })
    }

private fun BufferedSource.readBounded(maxBytes: Long): ByteArray {
    val buffer = Buffer()
    while (true) {
        val read = read(buffer, 8192)
        if (read == -1L) break
        if (buffer.size > maxBytes) throw ResponseTooLargeException()
    }
    return buffer.readByteArray()
}
