package com.github.libretube.reader.api

import kotlinx.serialization.Serializable

@Serializable
data class ArticleRequestDto(
    val videoId: String,
    val title: String,
    val creator: String,
    val transcript: String,
    val style: String,
    val detail: String
)

@Serializable
data class TranscriptRequestDto(val videoId: String)

/** Fields are nullable so that malformed responses reach [ArticleValidator] instead of crashing. */
@Serializable
data class ArticleSectionDto(
    val heading: String? = null,
    val paragraphs: List<String>? = null
)

@Serializable
data class ArticleBodyDto(
    val title: String? = null,
    val sections: List<ArticleSectionDto>? = null,
    val visualNotes: List<String>? = null
)

@Serializable
data class ArticleResponseDto(
    val article: ArticleBodyDto? = null,
    val model: String? = null
)

@Serializable
data class TranscriptResponseDto(
    val title: String? = null,
    val creator: String? = null,
    val transcript: String? = null,
    val language: String? = null,
    val source: String? = null,
    val model: String? = null
)

@Serializable
data class ErrorDto(
    val error: String? = null,
    val code: String? = null
)
