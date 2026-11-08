package com.github.libretube.reader

import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.libretube.R
import com.github.libretube.reader.ReaderTestSupport.ARTICLE_TITLE
import com.github.libretube.reader.ReaderTestSupport.TRANSCRIPT_LINE
import com.github.libretube.reader.ReaderTestSupport.VIDEO_ID
import com.github.libretube.reader.ReaderTestSupport.context
import com.github.libretube.reader.ui.ArticleActivity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders a seeded saved article with the real store and renderer. The service URL points at an
 * unusable host, so passing these tests shows that saved articles need no network or AI call.
 */
@RunWith(AndroidJUnit4::class)
class ArticleReaderInstrumentedTest {

    @Before
    fun setUp() {
        ReaderTestSupport.resetReader()
        ReaderTestSupport.seed()
    }

    @After
    fun tearDown() {
        ReaderTestSupport.finishAllActivities()
        ReaderTestSupport.resetReader()
    }

    /** Launches the reader and waits for the saved article to render from storage. */
    private fun launch(): ActivityScenario<ArticleActivity> {
        val scenario = ActivityScenario.launch<ArticleActivity>(ArticleActivity.intent(context, VIDEO_ID))
        assertTrue("saved article rendered", ReaderTestSupport.waitFor {
            var rendered = false
            scenario.onActivity {
                rendered = it.findViewById<LinearLayout>(R.id.article_container).childCount > 0 &&
                    it.findViewById<NestedScrollView>(R.id.article_scroll).isShown
            }
            rendered
        })
        return scenario
    }

    private fun scrollY(scenario: ActivityScenario<ArticleActivity>): Int {
        var y = -1
        scenario.onActivity { y = it.findViewById<NestedScrollView>(R.id.article_scroll).scrollY }
        return y
    }

    @Test
    fun savedArticleRendersStructuredSectionsAndSourceLabel() {
        launch().use {
            onView(withText(ARTICLE_TITLE)).check(matches(isDisplayed()))
            onView(withText("Section 1")).check(matches(isDisplayed()))
            ReaderTestSupport.screenshot("article-saved-top")
            it.onActivity { activity ->
                val container = activity.findViewById<LinearLayout>(R.id.article_container)
                val texts = (0 until container.childCount).map { i ->
                    (container.getChildAt(i) as android.widget.TextView).text.toString()
                }
                assertTrue(texts.any { t -> t.contains(activity.getString(R.string.libreread_source_creator_captions)) })
                assertTrue(texts.any { t -> t.contains("Seed Creator") })
                assertTrue(texts.any { t -> t == activity.getString(R.string.libreread_visual_notes) })
                // title, meta, 30 headings, 90 paragraphs, visual heading, 1 note, footer
                assertEquals(1 + 1 + 30 + 90 + 1 + 1 + 1, container.childCount)
            }
        }
    }

    @Test
    fun transcriptToggleShowsOriginalTranscript() {
        launch().use { scenario ->
            onView(withText(ARTICLE_TITLE)).check(matches(isDisplayed()))
            scenario.onActivity {
                it.findViewById<MaterialToolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_transcript, 0)
            }
            onView(withText(str(R.string.libreread_transcript_title))).check(matches(isDisplayed()))
            onView(withText(org.hamcrest.CoreMatchers.containsString(TRANSCRIPT_LINE))).check(matches(isDisplayed()))
            ReaderTestSupport.screenshot("article-transcript")

            // the toggle survives recreation
            scenario.recreate()
            onView(withText(str(R.string.libreread_transcript_title))).check(matches(isDisplayed()))

            scenario.onActivity {
                it.findViewById<MaterialToolbar>(R.id.toolbar).menu
                    .performIdentifierAction(R.id.action_transcript, 0)
            }
            onView(withText(ARTICLE_TITLE)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun readingPositionSurvivesRecreationAndReopen() {
        val scenario = launch()
        onView(withText(ARTICLE_TITLE)).check(matches(isDisplayed()))
        scenario.onActivity { it.findViewById<NestedScrollView>(R.id.article_scroll).scrollTo(0, 4000) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val scrolled = scrollY(scenario)
        assertTrue("article should be long enough to scroll", scrolled > 1000)

        scenario.recreate()
        assertTrue(
            "position restored after recreation",
            ReaderTestSupport.waitFor { kotlin.math.abs(scrollY(scenario) - scrolled) < 400 }
        )
        ReaderTestSupport.screenshot("article-after-recreate")

        // closing pauses the screen, which writes the position to its own file
        scenario.close()
        val store = ReaderGraph.store(context)
        assertTrue(ReaderTestSupport.waitFor {
            runBlocking { store.readPosition(VIDEO_ID) }?.let { it.revision == "seed-1" && it.blockIndex > 0 } == true
        })

        launch().use { reopened ->
            assertTrue(
                "position restored after reopening",
                ReaderTestSupport.waitFor { kotlin.math.abs(scrollY(reopened) - scrolled) < 400 }
            )
        }
    }

    @Test
    fun positionFromAnotherRevisionIsIgnored() {
        runBlocking {
            ReaderGraph.store(context).writePosition(
                com.github.libretube.reader.model.ReadingPosition(VIDEO_ID, "old-revision", 60, 0, 0.8f, 1)
            )
        }
        launch().use { scenario ->
            onView(withText(ARTICLE_TITLE)).check(matches(isDisplayed()))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, scrollY(scenario))
        }
    }

    private fun str(resId: Int) = context.getString(resId)
}
