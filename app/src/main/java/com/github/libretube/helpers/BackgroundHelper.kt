package com.github.libretube.helpers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.session.MediaController
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.reader.ReaderNavigator
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.util.PlayingQueue
import com.github.libretube.util.PlayingQueueMode

/**
 * Upstream helper for player services. In LibreRead every entry point here is inert or
 * redirects to the reader.
 */
object BackgroundHelper {
    private const val TAG = "BackgroundHelper"

    /**
     * LibreRead: background playback does not exist. Opens the reader for the video instead.
     */
    fun playOnBackground(
        context: Context,
        playerData: PlayerData,
    ) {
        ReaderNavigator.openArticle(context, playerData.videoId)
    }

    /**
     * Stop the [OnlinePlayerService] or [OfflinePlayerService] service if it is running.
     * Both are disabled in the manifest, so this is only a safety net.
     */
    fun stopBackgroundPlay(context: Context) {
        arrayOf(
            OnlinePlayerService::class.java,
            OfflinePlayerService::class.java
        ).forEach {
            runCatching { context.stopService(Intent(context, it)) }
        }
    }

    /**
     * LibreRead: deliberately a no-op. Upstream code (player fragments, sleep timer, volume
     * control) calls this to bind or start a media service. Nothing is built, bound or retried,
     * so no media session or player service can start from any of those paths.
     */
    @Suppress("UNUSED_PARAMETER")
    fun startMediaService(
        context: Context,
        serviceClass: Class<*>,
        arguments: Bundle = Bundle.EMPTY,
        onController: (MediaController) -> Unit = {}
    ) {
        Log.d(TAG, "Ignored media service request: playback is disabled in LibreRead")
    }

    /**
     * Get the service class of the currently active player based on the playing queue mode
     */
    fun getCurrentPlayerServiceClass(): Class<*> {
        return if (PlayingQueue.queueMode == PlayingQueueMode.OFFLINE) {
            OfflinePlayerService::class.java
        } else {
            OnlinePlayerService::class.java
        }
    }

    /**
     * Start a media service for the currently active player (online or offline)
     * and pass the MediaController to the callback
     */
    fun startCurrentMediaService(
        context: Context,
        arguments: Bundle = Bundle.EMPTY,
        onController: (MediaController) -> Unit = {}
    ) {
        startMediaService(context, getCurrentPlayerServiceClass(), arguments, onController)
    }


    /**
     * Set the volume of the currently active player
     *
     * @param context the current context
     * @param volume the volume level to set (0.0 to 1.0)
     */
    fun setVolume(context: Context, volume: Float) {
        startCurrentMediaService(context, Bundle.EMPTY) { controller ->
            try {
                controller.volume = volume.coerceIn(0f, 1f)
            } finally {
                controller.release()
            }
        }
    }
}
