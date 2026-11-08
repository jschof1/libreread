package com.github.libretube.reader

import android.content.Context
import com.github.libretube.api.MediaServiceRepository
import com.github.libretube.helpers.LocaleHelper
import com.github.libretube.helpers.PreferenceHelper
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.reader.api.LibreReadApi
import com.github.libretube.reader.source.DeviceCaptionSource
import com.github.libretube.reader.store.ArticleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
object ReaderGraph {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var store: ArticleStore? = null

    fun store(context: Context): ArticleStore = store ?: synchronized(this) {
        store ?: ArticleStore(storeDirectory(context)).also { store = it }
    }
    fun storeDirectory(context: Context) =
        File(context.applicationContext.noBackupFilesDir, "libreread")

    fun preferences(context: Context) = ReaderPreferences.get(context)
    private val serviceClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }

    private val captionClient: OkHttpClient by lazy {
        serviceClient.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun api(context: Context): LibreReadApi {
        val prefs = preferences(context)
        return LibreReadApi(serviceClient, prefs::serviceConfig)
    }

    fun captionSource(): DeviceCaptionSource = DeviceCaptionSource(
        loadStreams = { videoId -> MediaServiceRepository.instance.getStreams(videoId) },
        httpClient = captionClient,
        preferredLanguage = ::preferredCaptionLanguage
    )

    private fun preferredCaptionLanguage(): String? {
        val subtitlePref = PreferenceHelper.getString(PreferenceKeys.DEFAULT_SUBTITLE, "")
        if (subtitlePref.isNotBlank() && subtitlePref != "none") return subtitlePref
        return LocaleHelper.getAppLocale().toLanguageTag()
    }
}
