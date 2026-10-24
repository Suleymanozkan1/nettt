package com.bookmind.app.ui

import android.content.Context
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.bookmind.app.MainActivity
import com.bookmind.app.R
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.processing.BookProcessor
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end UI flows on Robolectric with the real Hilt graph (in-memory DB, local AI engine). */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = HiltTestApplication::class, qualifiers = "tr-w411dp-h891dp")
class AppUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var processor: BookProcessor
    @Inject lateinit var reminders: ReminderManager
    @Inject lateinit var db: BookMindDatabase

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        TestGeminiModule.reset()
        hilt.inject()
        runBlocking { settings.clear() }
    }

    @After fun tearDown() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java) }

    private fun ready(withDemo: Boolean = true): String? = runBlocking {
        settings.update { it.copy(onboardingDone = true) }
        if (withDemo) library.addDemoBook().also { processor.process(it) } else null
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /**
     * Polls [condition] while letting the main looper run: the view model resumes on the main thread after Room's
     * background write, which neither waitForIdle nor waitUntil waits for.
     */
    private fun eventually(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("condition not met within $timeoutMs ms")
    }

    private fun clickTab(label: Int) = compose.onAllNodesWithText(s(label)).onFirst().performClick()

    @Test fun onboarding_completes_and_lands_on_today() {
        launch()
        waitForText("Türkçe")
        repeat(4) { compose.onNodeWithText(s(R.string.next)).performClick() }
        compose.onNodeWithText(s(R.string.onb_setup_title)).assertExists()
        compose.onNodeWithText(s(R.string.minutes_short, 20)).performClick()
        compose.onNodeWithText(s(R.string.onb_start)).performClick()
        waitForText(s(R.string.home_title))
        assertTrue(runBlocking { settings.current().onboardingDone })
        assertEquals(20, runBlocking { db.users().current()!!.dailyGoalMinutes })
        assertEquals(true, runBlocking { reminders.current()!!.enabled })
        // The demo book was added during onboarding.
        waitForText("Öğrenmenin Bilimi")
    }

    @Test fun library_book_detail_and_quick_summary() {
        ready()
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_library)
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForText(s(R.string.review))
        // The summaries sit below the summary and "Next lesson" cards: scroll the page to them first.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(s(R.string.summary_quick)))
        compose.onNodeWithText(s(R.string.summary_quick)).performClick()
        compose.onNodeWithText(s(R.string.generate)).performClick()
        waitForText("Temel fikir")
        // Locally generated content is labelled as such.
        waitForText(s(R.string.origin_local))
    }

    @Test fun daily_review_shows_answer_and_schedules() {
        ready()
        launch()
        waitForText(s(R.string.home_start_reviews))
        compose.onNodeWithText(s(R.string.home_start_reviews)).performClick()
        waitForText(s(R.string.show_answer))
        compose.onNodeWithText(s(R.string.show_answer)).performClick()
        waitForText(s(R.string.rate_good))
        compose.onNodeWithText(s(R.string.rate_good)).performScrollTo().performClick()
        waitForText(s(R.string.review_progress, 2, 15))
        val reviewed = runBlocking { db.learning().logsSince(db.users().current()!!.id, 0) }
        assertEquals(1, reviewed.size)
    }

    @Test fun quiz_can_be_created_and_started() {
        val bookId = ready()!!
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_library)
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForText(s(R.string.quiz))
        compose.onNode(hasText(s(R.string.quiz), substring = true) and hasClickAction()).performClick()
        waitForText(s(R.string.create_quiz))
        compose.onNodeWithText(s(R.string.create_quiz)).performClick()
        waitForText(s(R.string.question_n_of, 1, 8), 15_000)
        assertEquals(1, runBlocking { db.quizzes().wrongQuestions(bookId).size + 1 })
    }

    @Test fun notification_settings_are_saved() {
        ready(withDemo = false)
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_profile)
        waitForText(s(R.string.notifications_title))
        compose.onNodeWithText(s(R.string.notifications_title)).performClick()
        waitForText(s(R.string.reminders_enabled))
        compose.onNodeWithText(s(R.string.reminders_enabled)).performClick()
        eventually { runBlocking { reminders.current()?.enabled } == true }
    }

    @Test fun empty_library_and_manual_entry_validation() {
        ready(withDemo = false)
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_library)
        waitForText(s(R.string.library_empty_title))
        compose.onAllNodesWithText(s(R.string.add_book)).onFirst().performClick()
        waitForText(s(R.string.add_manual))
        compose.onNodeWithText(s(R.string.add_manual)).performClick()
        compose.onNode(hasSetTextAction() and hasText(s(R.string.book_title))).performTextInput("Kısa")
        compose.onNode(hasSetTextAction() and hasText(s(R.string.book_text))).performTextInput("çok kısa metin")
        waitForText(s(R.string.text_too_short))
    }

    @Test fun ask_the_book_answers_offline_with_sources() {
        ready()
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_library)
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForText(s(R.string.ask_title))
        compose.onNode(hasText(s(R.string.ask_title)) and hasClickAction()).performScrollTo().performClick()
        waitForText(s(R.string.ask_empty_title))
        compose.onNode(hasSetTextAction()).performTextInput("Test etkisi nedir?")
        compose.onNodeWithContentDescription(s(R.string.send)).performClick()
        // No AI model selected: BM25 finds the passage and the book's own sentences are quoted with their chapter.
        waitForText("Kitapta sorunla en çok örtüşen cümleler", 15_000)
        waitForText(s(R.string.ask_sources))
        waitForText("Aktif Hatırlama")
    }

    @Test fun ai_assistant_shows_gemini_status_and_the_book_page_explains_a_build_without_key() {
        val bookId = ready()!!
        launch()
        waitForText(s(R.string.home_title))
        clickTab(R.string.tab_profile)
        waitForText(s(R.string.ai_title))
        // Test builds have no Gemini key: everything uses the classic methods and says so.
        compose.onNodeWithText(s(R.string.ai_status_no_key)).performScrollTo().assertExists()
        compose.onNodeWithText(s(R.string.ai_title)).performScrollTo().performClick()
        waitForText(s(R.string.ai_hero_title))
        compose.onNodeWithText(s(R.string.ai_no_key_title)).assertExists()
        compose.onNodeWithText(s(R.string.ai_privacy_sent)).performScrollTo().assertExists()
        compose.onNodeWithText("fake-gemini").assertExists()
        assertEquals(com.bookmind.app.data.settings.AiMode.GEMINI, runBlocking { settings.current().aiMode })
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        clickTab(R.string.tab_library)
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForText(s(R.string.book_summary_no_key))
        assertTrue(bookId.isNotEmpty())
    }
}
