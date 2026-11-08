package com.github.libretube.reader.model

/** Recoverable failure categories. The UI maps each to a user-facing message. */
enum class ReaderErrorKind {
    /** No captions on the device and no AI transcription was obtained. */
    NO_TRANSCRIPT,
    NETWORK,
    TIMEOUT,
    /** The device credential was rejected. */
    AUTH,
    /** Service URL or device credential missing or invalid. */
    NOT_CONFIGURED,
    SERVER,
    INVALID_RESPONSE,
    TRANSCRIPT_TOO_LONG,
    CONSENT_DECLINED,
    CANCELLED,
    STORAGE,
    /** The link or selection did not contain a usable video id. */
    INVALID_VIDEO,
    UNKNOWN
}

data class ReaderError(
    val kind: ReaderErrorKind,
    /** User-safe detail, such as the service's `error` string. Never contains secrets. */
    val detail: String? = null,
    /** Stable service error `code`, when provided. */
    val code: String? = null
)

class ReaderException(val error: ReaderError, cause: Throwable? = null) :
    Exception(error.kind.name + (error.code?.let { " ($it)" } ?: ""), cause) {
    constructor(kind: ReaderErrorKind, detail: String? = null, code: String? = null) :
        this(ReaderError(kind, detail, code))
}
