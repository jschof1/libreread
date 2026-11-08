package com.github.libretube.ui.preferences

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.github.libretube.BuildConfig
import com.github.libretube.R
import com.github.libretube.databinding.DialogLibrereadTextInputBinding
import com.github.libretube.reader.ReaderGraph
import com.github.libretube.reader.ReaderNavigator
import com.github.libretube.reader.ReaderPreferences
import com.github.libretube.reader.api.LibreReadApi
import com.github.libretube.reader.model.StylePresets
import com.github.libretube.reader.ui.ArticleActivity
import com.github.libretube.ui.base.BasePreferenceFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * LibreRead settings. They live in [ReaderPreferences.FILE_NAME], which is excluded from backups
 * and from LibreTube's settings export. The device credential is never displayed.
 */
class ReaderSettings : BasePreferenceFragment() {
    private val readerPreferences by lazy { ReaderGraph.preferences(requireContext()) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = ReaderPreferences.FILE_NAME
        setPreferencesFromResource(R.xml.libreread_settings, rootKey)

        findPreference<EditTextPreference>(ReaderPreferences.KEY_API_URL)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString().trim()
                val valid = value.isEmpty() || isAllowedServiceUrl(value)
                if (!valid) {
                    Toast.makeText(context, R.string.libreread_settings_api_url_invalid, Toast.LENGTH_LONG).show()
                }
                valid.also { if (it) view?.post { updateSummaries() } }
            }
        }

        findPreference<Preference>(TOKEN_ENTRY)?.setOnPreferenceClickListener {
            showTokenDialog()
            true
        }

        findPreference<Preference>(RESET_SERVICE)?.setOnPreferenceClickListener {
            readerPreferences.resetServiceToBuildDefaults()
            findPreference<EditTextPreference>(ReaderPreferences.KEY_API_URL)?.text = null
            updateSummaries()
            Toast.makeText(context, R.string.libreread_settings_reset_done, Toast.LENGTH_SHORT).show()
            true
        }

        findPreference<Preference>(PRIVACY)?.setOnPreferenceClickListener {
            showPrivacyNotice()
            true
        }

        findPreference<EditTextPreference>(ReaderPreferences.KEY_CUSTOM_INSTRUCTIONS)
            ?.setOnPreferenceChangeListener { preference, newValue ->
                val ok = newValue.toString().length <= StylePresets.MAX_CUSTOM_INSTRUCTIONS
                if (!ok) {
                    Toast.makeText(
                        context,
                        getString(R.string.libreread_settings_custom_too_long, StylePresets.MAX_CUSTOM_INSTRUCTIONS),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    preference.summary = newValue.toString().trim().ifEmpty { null }
                }
                ok
            }

        findPreference<Preference>(SAVED_ARTICLES)?.setOnPreferenceClickListener {
            ReaderNavigator.openSavedArticles(requireContext())
            true
        }

        updateSummaries()
    }

    private fun updateSummaries() {
        findPreference<Preference>(ReaderPreferences.KEY_API_URL)?.summary =
            readerPreferences.apiUrl.takeIf { it.isNotBlank() }
                ?.let { getString(R.string.libreread_settings_api_url_summary, it) }
                ?: getString(R.string.libreread_settings_api_url_not_set)
        findPreference<Preference>(TOKEN_ENTRY)?.summary = getString(
            when (readerPreferences.credentialState) {
                ReaderPreferences.CredentialState.BUILD_DEFAULT -> R.string.libreread_settings_token_build
                ReaderPreferences.CredentialState.CUSTOM -> R.string.libreread_settings_token_custom
                ReaderPreferences.CredentialState.NONE -> R.string.libreread_settings_token_none
            }
        )
        findPreference<Preference>(PRIVACY)?.summary = getString(
            if (readerPreferences.isAccepted()) {
                R.string.libreread_privacy_settings_accepted
            } else {
                R.string.libreread_privacy_settings_pending
            }
        )
        findPreference<Preference>(ReaderPreferences.KEY_CUSTOM_INSTRUCTIONS)?.summary =
            readerPreferences.customInstructions.ifEmpty { null }
    }

    private fun showTokenDialog() {
        val binding = DialogLibrereadTextInputBinding.inflate(layoutInflater)
        binding.explanation.setText(R.string.libreread_settings_token_explanation)
        binding.inputLayout.hint = getString(R.string.libreread_settings_token_hint)
        binding.input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.libreread_settings_token)
            .setView(binding.root)
            .setPositiveButton(R.string.libreread_save) { _, _ ->
                readerPreferences.setDeviceToken(binding.input.text?.toString().orEmpty())
                updateSummaries()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showPrivacyNotice() {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.libreread_privacy_settings_title)
            .setMessage(ArticleActivity.privacyNotice(requireContext()))
            .setPositiveButton(R.string.libreread_close, null)
        if (readerPreferences.isAccepted()) {
            builder.setNeutralButton(R.string.libreread_privacy_withdraw) { _, _ ->
                readerPreferences.withdrawConsent()
                updateSummaries()
            }
        }
        builder.show()
    }

    companion object {
        private const val TOKEN_ENTRY = "libreread_device_token_entry"
        private const val RESET_SERVICE = "libreread_reset_service"
        private const val PRIVACY = "libreread_privacy"
        private const val SAVED_ARTICLES = "libreread_saved_articles"

        fun isAllowedServiceUrl(value: String): Boolean = LibreReadApi.normalizeBaseUrl(value) != null
    }
}
