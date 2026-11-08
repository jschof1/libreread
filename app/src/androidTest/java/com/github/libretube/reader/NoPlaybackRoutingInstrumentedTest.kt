package com.github.libretube.reader

import android.app.Activity
import android.app.ActivityManager
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.reader.ReaderTestSupport.VIDEO_ID
import com.github.libretube.reader.ReaderTestSupport.context
import com.github.libretube.reader.ui.ArticleActivity
import com.github.libretube.reader.ui.SavedArticlesActivity
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.RouterActivity
import com.github.libretube.util.PlayingQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every upstream selection path must reach the reader, and no playback service may exist or run.
 */
@RunWith(AndroidJUnit4::class)
class NoPlaybackRoutingInstrumentedTest {
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val packageName get() = context.packageName

    @Before
    fun setUp() {
        ReaderTestSupport.resetReader()
        ReaderTestSupport.seed()
        PlayingQueue.clear()
    }

    @After
    fun tearDown() {
        ReaderTestSupport.finishAllActivities()
        PlayingQueue.clear()
        ReaderTestSupport.resetReader()
    }

    /** Runs [action] from a real activity context and returns the activity it starts. */
    private fun <T : Activity> routeFrom(target: Class<T>, action: (Activity) -> Unit): Activity {
        val monitor = instrumentation.addMonitor(target.name, null, false)
        try {
            ActivityScenario.launch(SavedArticlesActivity::class.java).use { host ->
                host.onActivity(action)
                val started = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                assertNotNull("${target.simpleName} was not opened", started)
                return started
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun assertOpensReader(action: (Activity) -> Unit) {
        val reader = routeFrom(ArticleActivity::class.java, action)
        assertEquals(VIDEO_ID, reader.intent.getStringExtra(ArticleActivity.EXTRA_VIDEO_ID))
        assertNoPlayerServiceRunning()
    }

    @Test
    fun navigateVideoOpensReader() = assertOpensReader {
        NavigationHelper.navigateVideo(it, PlayerData(VIDEO_ID))
    }

    @Test
    fun audioOnlyRequestOpensReader() = assertOpensReader {
        NavigationHelper.navigateVideo(it, PlayerData(VIDEO_ID), audioOnlyPlayerRequested = true)
    }

    @Test
    fun youtubeUrlSelectionOpensReader() = assertOpensReader {
        NavigationHelper.navigateVideo(it, PlayerData("https://www.youtube.com/watch?v=$VIDEO_ID"))
    }

    @Test
    fun directVideoPlayerFragmentCallOpensReader() = assertOpensReader {
        NavigationHelper.openVideoPlayerFragment(it, PlayerData(VIDEO_ID))
    }

    @Test
    fun playOnBackgroundOpensReader() = assertOpensReader {
        BackgroundHelper.playOnBackground(it, PlayerData(VIDEO_ID, isOffline = true))
    }

    @Test
    fun audioPlayerFragmentCallOpensReaderForQueueItem() {
        PlayingQueue.updateCurrent(StreamItem(url = "/watch?v=$VIDEO_ID", title = "Queued"))
        assertOpensReader { NavigationHelper.openAudioPlayerFragment(it, offlinePlayer = false) }
    }

    @Test
    fun audioPlayerFragmentCallWithoutQueueOpensSavedArticles() {
        val monitor = instrumentation.addMonitor(SavedArticlesActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<ArticleActivity>(ArticleActivity.intent(context, VIDEO_ID)).use { host ->
                host.onActivity {
                    PlayingQueue.clear()
                    NavigationHelper.openAudioPlayerFragment(it)
                }
                assertNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 10_000))
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertNoPlayerServiceRunning()
    }

    @Test
    fun mediaServiceRequestsAreInert() {
        instrumentation.runOnMainSync {
            BackgroundHelper.startMediaService(
                context, OnlinePlayerService::class.java, bundleOf("x" to 1)
            ) { throw AssertionError("a media controller must never be created") }
            BackgroundHelper.startCurrentMediaService(context) {
                throw AssertionError("a media controller must never be created")
            }
            BackgroundHelper.setVolume(context, 0.5f)
        }
        Thread.sleep(1_000)
        instrumentation.waitForIdleSync()
        assertNoPlayerServiceRunning()
    }

    @Test
    fun playerServicesAreDisabledInTheManifest() {
        val pm = context.packageManager
        for (service in listOf(OnlinePlayerService::class.java, OfflinePlayerService::class.java)) {
            val info = pm.getServiceInfo(
                ComponentName(context, service),
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
            assertFalse("${service.simpleName} must be disabled", info.enabled)
        }
        val sessionServices = pm.queryIntentServices(
            Intent("androidx.media3.session.MediaSessionService").setPackage(packageName), 0
        )
        assertTrue("no media session service is exposed", sessionServices.isEmpty())
    }

    @Test
    fun appIsNotAMediaOrQueueTarget() {
        val pm = context.packageManager
        fun ours(intent: Intent) = pm.queryIntentActivities(intent.setPackage(packageName), 0)
            .map { it.activityInfo.name }

        assertTrue(ours(Intent("android.intent.action.MUSIC_PLAYER")).isEmpty())
        assertTrue(
            ours(Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")).isEmpty()
        )
        val shareTargets = ours(Intent(Intent.ACTION_SEND).setType("text/plain"))
        assertFalse(shareTargets.any { it.endsWith("AddToQueueActivity") })
        assertFalse(shareTargets.any { it.endsWith("DownloadActivity") })
    }

    @Test
    fun youtubeLinksStillResolveToTheRouter() {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$VIDEO_ID"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(packageName)
        val targets = pm.queryIntentActivities(view, 0).map { it.activityInfo.name }
        assertEquals(listOf(RouterActivity::class.java.name), targets)
    }

    @Test
    fun incomingYoutubeLinkOpensReader() {
        val monitor = instrumentation.addMonitor(ArticleActivity::class.java.name, null, false)
        try {
            val link = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/$VIDEO_ID"))
                .setClassName(context, RouterActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(link)
            val reader = instrumentation.waitForMonitorWithTimeout(monitor, 20_000)
            assertNotNull("incoming link did not reach the reader", reader)
            assertEquals(VIDEO_ID, reader.intent.getStringExtra(ArticleActivity.EXTRA_VIDEO_ID))
            ReaderTestSupport.screenshot("incoming-link-reader")
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        assertNoPlayerServiceRunning()
    }

    @Suppress("DEPRECATION")
    private fun assertNoPlayerServiceRunning() {
        val running = context.getSystemService<ActivityManager>()!!
            .getRunningServices(Int.MAX_VALUE)
            .map { it.service.className }
        assertFalse(running.contains(OnlinePlayerService::class.java.name))
        assertFalse(running.contains(OfflinePlayerService::class.java.name))
    }
}
