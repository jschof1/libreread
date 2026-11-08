package com.github.libretube.reader

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.libretube.BuildConfig
import com.github.libretube.reader.api.LibreReadApi
import com.github.libretube.reader.api.ServiceConfig
import com.github.libretube.reader.model.ArticleDetail
import com.github.libretube.reader.model.ArticleSettings
import com.github.libretube.reader.model.StylePresets

/** Readable column widths. */
enum class ReadableWidth(val key: String, val maxWidthDp: Int?) {
    NARROW("narrow", 560),
    MEDIUM("medium", 720),
    FULL("full", null);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/**
 * Reader settings, kept in their own preferences file ([FILE_NAME]). That file is excluded from
 * Android backups and is not part of LibreTube's settings export, because it can hold the device
 * credential.
 */
class ReaderPreferences(private val prefs: SharedPreferences) : ConsentStore {

    /** The service URL in use: the user's override, else the build default (empty unless configured). */
    val apiUrl: String
        get() = prefs.getString(KEY_API_URL, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.LIBREREAD_API_URL

    /**
     * The device credential in use: the user's value if one was saved (an empty saved value
     * means "none"), else the build-provided one.
     */
    val deviceToken: String
        get() = if (prefs.contains(KEY_DEVICE_TOKEN)) {
            prefs.getString(KEY_DEVICE_TOKEN, "").orEmpty()
        } else {
            BuildConfig.LIBREREAD_DEVICE_TOKEN
        }

    val credentialState: CredentialState
        get() = when {
            prefs.contains(KEY_DEVICE_TOKEN) && deviceToken.isNotEmpty() -> CredentialState.CUSTOM
            prefs.contains(KEY_DEVICE_TOKEN) -> CredentialState.NONE
            BuildConfig.LIBREREAD_DEVICE_TOKEN.isNotEmpty() -> CredentialState.BUILD_DEFAULT
            else -> CredentialState.NONE
        }

    fun setDeviceToken(token: String) = prefs.edit { putString(KEY_DEVICE_TOKEN, token.trim()) }

    fun resetServiceToBuildDefaults() = prefs.edit {
        remove(KEY_API_URL)
        remove(KEY_DEVICE_TOKEN)
    }

    fun serviceConfig() = ServiceConfig(apiUrl, deviceToken)

    /** Whether a usable service URL and a device credential are both set. Values are not exposed. */
    fun isServiceConfigured(): Boolean =
        LibreReadApi.normalizeBaseUrl(apiUrl) != null && deviceToken.isNotBlank()

    var stylePreset: String
        get() = StylePresets.fromKey(prefs.getString(KEY_STYLE_PRESET, null)).key
        set(value) = prefs.edit { putString(KEY_STYLE_PRESET, value) }

    var customInstructions: String
        get() = prefs.getString(KEY_CUSTOM_INSTRUCTIONS, "").orEmpty()
            .take(StylePresets.MAX_CUSTOM_INSTRUCTIONS)
        set(value) = prefs.edit {
            putString(KEY_CUSTOM_INSTRUCTIONS, value.take(StylePresets.MAX_CUSTOM_INSTRUCTIONS))
        }

    var detail: ArticleDetail
        get() = ArticleDetail.fromWire(prefs.getString(KEY_DETAIL, null))
        set(value) = prefs.edit { putString(KEY_DETAIL, value.wireName) }

    fun articleSettings() = ArticleSettings(stylePreset, customInstructions, detail)

    fun saveDefaults(settings: ArticleSettings) {
        stylePreset = settings.stylePreset
        customInstructions = settings.customInstructions
        detail = settings.detail
    }

    var fontSizeSp: Int
        get() = (prefs.getString(KEY_FONT_SIZE, null)?.toIntOrNull() ?: DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        set(value) = prefs.edit {
            putString(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE).toString())
        }

    var readableWidth: ReadableWidth
        get() = ReadableWidth.fromKey(prefs.getString(KEY_READABLE_WIDTH, null))
        set(value) = prefs.edit { putString(KEY_READABLE_WIDTH, value.key) }

    override fun isAccepted(): Boolean =
        prefs.getInt(KEY_PRIVACY_ACCEPTED, 0) >= PRIVACY_NOTICE_VERSION

    override fun accept() = prefs.edit { putInt(KEY_PRIVACY_ACCEPTED, PRIVACY_NOTICE_VERSION) }

    fun withdrawConsent() = prefs.edit { remove(KEY_PRIVACY_ACCEPTED) }

    enum class CredentialState { BUILD_DEFAULT, CUSTOM, NONE }

    companion object {
        const val FILE_NAME = "libreread"

        const val KEY_API_URL = "libreread_api_url"
        const val KEY_DEVICE_TOKEN = "libreread_device_token"
        const val KEY_STYLE_PRESET = "libreread_style_preset"
        const val KEY_CUSTOM_INSTRUCTIONS = "libreread_custom_instructions"
        const val KEY_DETAIL = "libreread_detail"
        const val KEY_FONT_SIZE = "libreread_font_size"
        const val KEY_READABLE_WIDTH = "libreread_readable_width"
        const val KEY_PRIVACY_ACCEPTED = "libreread_privacy_notice_accepted"

        /** Increase when the notice changes materially, so it is shown again. */
        const val PRIVACY_NOTICE_VERSION = 1

        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 14
        const val MAX_FONT_SIZE = 28

        fun get(context: Context) = ReaderPreferences(
            context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        )
    }
}
