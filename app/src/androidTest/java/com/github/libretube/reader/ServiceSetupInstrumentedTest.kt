package com.github.libretube.reader

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.libretube.R
import com.github.libretube.reader.ReaderTestSupport.context
import com.github.libretube.reader.ui.ArticleActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceSetupInstrumentedTest {

    @Before
    fun setUp() = ReaderTestSupport.resetReader()

    @After
    fun tearDown() {
        ReaderTestSupport.finishAllActivities()
        ReaderTestSupport.resetReader()
    }

    @Test
    fun missingServiceShowsSetupAndContinuesAfterConfiguring() {
        val notConfigured = context.getString(R.string.libreread_error_not_configured)
        ActivityScenario.launch<ArticleActivity>(ArticleActivity.intent(context, UNSAVED_VIDEO_ID)).use { scenario ->
            assertTrue("setup message shown", ReaderTestSupport.waitFor {
                showsError(scenario, notConfigured)
            })
            var settingsShown = false
            scenario.onActivity { settingsShown = it.findViewById<View>(R.id.error_settings).isShown }
            assertTrue("reader settings route offered", settingsShown)
            ReaderTestSupport.screenshot("reader-service-setup")

            context.getSharedPreferences(ReaderPreferences.FILE_NAME, Context.MODE_PRIVATE).edit(commit = true) {
                putString(ReaderPreferences.KEY_API_URL, "https://libreread-test.invalid")
                putString(ReaderPreferences.KEY_DEVICE_TOKEN, "test-device-credential")
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            assertTrue("preparation resumed after setup", ReaderTestSupport.waitFor {
                !showsError(scenario, notConfigured)
            })
        }
    }

    private fun showsError(scenario: ActivityScenario<ArticleActivity>, message: String): Boolean {
        var shown = false
        scenario.onActivity {
            shown = it.findViewById<View>(R.id.error_panel).isShown &&
                it.findViewById<TextView>(R.id.error_message).text.contains(message)
        }
        return shown
    }

    companion object {
        private const val UNSAVED_VIDEO_ID = "lrTest00002"
    }
}
