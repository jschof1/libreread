package com.github.libretube.reader

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.github.libretube.extensions.toID
import com.github.libretube.reader.ui.ArticleActivity
import com.github.libretube.reader.ui.SavedArticlesActivity
object ReaderNavigator {
    fun openArticle(
        context: Context,
        videoIdOrUrl: String?,
        title: String? = null,
        creator: String? = null
    ) {
        val videoId = videoIdOrUrl?.takeIf { it.isNotBlank() }?.toID()
        if (videoId.isNullOrBlank()) {
            openSavedArticles(context)
            return
        }
        start(context, ArticleActivity.intent(context, videoId, title, creator))
    }

    fun openSavedArticles(context: Context) {
        start(context, Intent(context, SavedArticlesActivity::class.java))
    }

    private fun start(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
