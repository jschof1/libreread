package com.github.libretube.helpers

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.content.getSystemService
import com.github.libretube.NavDirections
import com.github.libretube.R
import com.github.libretube.constants.IntentData
import com.github.libretube.enums.PlaylistType
import com.github.libretube.extensions.toID
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.reader.ReaderNavigator
import com.github.libretube.ui.activities.MainActivity
import com.github.libretube.ui.activities.ZoomableImageActivity
import com.github.libretube.util.PlayingQueue

object NavigationHelper {
    fun navigateChannel(context: Context, channelUrlOrId: String?) {
        if (channelUrlOrId == null) return

        // navigating to channels is only supported in the main activity, not in the no internet activity
        val activity = ContextHelper.tryUnwrapActivity<MainActivity>(context) ?: return
        activity.navController.navigate(NavDirections.openChannel(channelUrlOrId.toID()))
        try {
            // minimize player if currently expanded
            activity.runOnPlayerFragment {
                binding.playerMotionLayout.transitionToEnd()
                true
            }
            activity.minimizePlayerContainerLayout()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * LibreRead: every video selection opens the reader. No player fragment, media session or
     * service is created. The playback-related parameters are kept for source compatibility with
     * upstream callers and are ignored.
     */
    @Suppress("UNUSED_PARAMETER")
    fun navigateVideo(
        context: Context,
        playerData: PlayerData,
        alreadyStarted: Boolean = false,
        forceVideo: Boolean = false,
        audioOnlyPlayerRequested: Boolean = false,
    ) {
        ReaderNavigator.openArticle(context, playerData.videoId)
    }

    fun navigatePlaylist(context: Context, playlistUrlOrId: String?, playlistType: PlaylistType) {
        if (playlistUrlOrId == null) return

        val activity = ContextHelper.unwrapActivity<MainActivity>(context)
        activity.navController.navigate(
            NavDirections.openPlaylist(playlistUrlOrId.toID(), playlistType)
        )
    }

    /**
     * LibreRead: there is no audio player. Upstream callers reach the reader for the current
     * queue item instead, or the saved articles list when there is none.
     */
    @Suppress("UNUSED_PARAMETER")
    fun openAudioPlayerFragment(
        context: Context,
        offlinePlayer: Boolean = false,
        minimizeByDefault: Boolean = false
    ) {
        ReaderNavigator.openArticle(context, PlayingQueue.getCurrent()?.url)
    }

    /**
     * LibreRead: there is no video player. Opens the reader for [playerData] instead.
     */
    @Suppress("UNUSED_PARAMETER")
    fun openVideoPlayerFragment(
        context: Context,
        playerData: PlayerData,
        alreadyStarted: Boolean = false,
    ) {
        ReaderNavigator.openArticle(context, playerData.videoId)
    }

    /**
     * Open a large, zoomable image preview
     */
    fun openImagePreview(context: Context, url: String) {
        val intent = Intent(context, ZoomableImageActivity::class.java)
        intent.putExtra(IntentData.bitmapUrl, url)
        context.startActivity(intent)
    }

    /**
     * Needed due to different MainActivity Aliases because of the app icons
     */
    fun restartMainActivity(context: Context) {
        // kill player notification
        context.getSystemService<NotificationManager>()!!.cancelAll()
        // start a new Intent of the app
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(context.packageName)
        intent?.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.startActivity(intent)
        // kill the old application
        Process.killProcess(Process.myPid())
    }
}
