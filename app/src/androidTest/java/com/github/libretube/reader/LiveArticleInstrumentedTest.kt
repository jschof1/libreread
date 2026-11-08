package com.github.libretube.reader

import android.content.Context
import android.os.Bundle
import androidx.core.content.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.libretube.reader.ReaderTestSupport.context
import com.github.libretube.reader.ui.ArticleActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in end-to-end test against the real service and a real video. Skipped unless the runner is
 * given `-e libreread.live true`. Optional `-e libreread.videoId <id>` picks the video.
 *
 * The service comes from `-e libreread.serviceUrl <https url>` and `-e libreread.deviceToken
 * <credential>` when given (both or neither), otherwise from the build's configured defaults.
 * Runtime values are written only to the app's private reader preferences for this run, are
 * removed afterwards, and are never logged or included in assertion messages. The test accepts
 * the AI notice as the user.
 */
@RunWith(AndroidJUnit4::class)
class LiveArticleInstrumentedTest {

    @Test
    fun realVideoBecomesASavedArticle() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("libreread.live") == "true")
        val videoId = args.getString("libreread.videoId") ?: DEFAULT_VIDEO_ID

        ReaderGraph.storeDirectory(context).deleteRecursively()
        val prefs = context.getSharedPreferences(ReaderPreferences.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }

        val installed = installRuntimeService(args)
        try {
            assertTrue(
                if (installed) {
                    "$ARG_SERVICE_URL is not a usable HTTPS service URL"
                } else {
                    "no service configured: pass $ARG_SERVICE_URL and $ARG_DEVICE_TOKEN, " +
                        "or use a build with service defaults"
                },
                ReaderGraph.preferences(context).isServiceConfigured()
            )
            ReaderGraph.preferences(context).accept()

            ActivityScenario.launch<ArticleActivity>(ArticleActivity.intent(context, videoId)).use {
                val saved = ReaderTestSupport.waitFor(timeoutMs = 6 * 60_000) {
                    runBlocking { ReaderGraph.store(context).readArticle(videoId) } != null
                }
                assertTrue("article for $videoId was not saved", saved)
                val title = runBlocking { ReaderGraph.store(context).readArticle(videoId) }!!.article.title
                assertTrue("saved article was not displayed", ReaderTestSupport.waitFor {
                    runCatching { onView(withText(title)).check(matches(isDisplayed())) }.isSuccess
                })
                ReaderTestSupport.screenshot("live-article-$videoId")
            }
        } finally {
            if (installed) {
                prefs.edit(commit = true) {
                    remove(ReaderPreferences.KEY_API_URL)
                    remove(ReaderPreferences.KEY_DEVICE_TOKEN)
                }
            }
        }
    }

    /**
     * Writes the runner-supplied service URL and device credential into reader preferences.
     * Returns false when neither was supplied. Fails, without echoing either value, when only one
     * was supplied, the URL is not HTTPS, or the credential is blank. The full URL check is the
     * app's own ([ReaderPreferences.isServiceConfigured]), so the test APK does not link against
     * OkHttp types that R8 may rename in a release app.
     */
    private fun installRuntimeService(args: Bundle): Boolean {
        val serviceUrl = args.getString(ARG_SERVICE_URL)
        val deviceToken = args.getString(ARG_DEVICE_TOKEN)
        if (serviceUrl.isNullOrEmpty() && deviceToken.isNullOrEmpty()) return false

        assertTrue(
            "$ARG_SERVICE_URL and $ARG_DEVICE_TOKEN must be given together",
            !serviceUrl.isNullOrEmpty() && !deviceToken.isNullOrEmpty()
        )
        assertTrue(
            "$ARG_SERVICE_URL is not an HTTPS URL",
            serviceUrl!!.trim().startsWith("https://", ignoreCase = true)
        )
        assertTrue("$ARG_DEVICE_TOKEN is blank", deviceToken!!.isNotBlank())

        context.getSharedPreferences(ReaderPreferences.FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) {
            putString(ReaderPreferences.KEY_API_URL, serviceUrl.trim())
            putString(ReaderPreferences.KEY_DEVICE_TOKEN, deviceToken.trim())
        }
        return true
    }

    companion object {
        /** A long-lived public talk with creator captions. */
        private const val DEFAULT_VIDEO_ID = "arj7oStGLkU"

        private const val ARG_SERVICE_URL = "libreread.serviceUrl"
        private const val ARG_DEVICE_TOKEN = "libreread.deviceToken"
    }
}
