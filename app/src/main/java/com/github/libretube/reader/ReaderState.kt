package com.github.libretube.reader

import com.github.libretube.reader.model.ArticleRecord
import com.github.libretube.reader.model.ReaderError
import com.github.libretube.reader.model.ReadingPosition

/** Preparation steps shown to the reader. */
enum class PrepStage {
    CHECKING_SAVED,
    LOADING_VIDEO,
    FETCHING_CAPTIONS,
    TRANSCRIBING,
    WRITING_ARTICLE,
    SAVING
}

/** What a pending privacy confirmation is for. */
enum class AiPurpose { TRANSCRIPTION, ARTICLE }

sealed interface RewriteStatus {
    data object Idle : RewriteStatus
    data object InProgress : RewriteStatus
    data object Committing : RewriteStatus
    data object AwaitingConsent : RewriteStatus
    data class Failed(val error: ReaderError) : RewriteStatus
}

/** The single source of truth for the reader screen, owned by [ArticleCoordinator]. */
sealed interface ReaderState {
    data object Idle : ReaderState

    data class Preparing(val stage: PrepStage) : ReaderState

    /** Waiting for the user to accept the privacy notice before the first AI transfer. */
    data class AwaitingConsent(val purpose: AiPurpose) : ReaderState

    /**
     * A saved article is readable. During a rewrite the previous article stays here and
     * [rewrite] reports progress or failure.
     */
    data class Ready(
        val record: ArticleRecord,
        val savedPosition: ReadingPosition?,
        val rewrite: RewriteStatus = RewriteStatus.Idle
    ) : ReaderState

    data class Failed(
        val error: ReaderError,
        /** No usable captions were found on the device. */
        val captionsUnavailable: Boolean,
        /** A transcript is stored, so a retry skips straight to writing. */
        val hasTranscript: Boolean
    ) : ReaderState
}
