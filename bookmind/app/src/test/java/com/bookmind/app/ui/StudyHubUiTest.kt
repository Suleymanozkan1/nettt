package com.bookmind.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.bookmind.app.MainActivity
import com.bookmind.app.R
import com.bookmind.app.audio.PodcastTimeline
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.repo.StudyHubRepository
import com.bookmind.app.data.repo.StudyModule
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.processing.BookProcessor
import com.bookmind.core.ai.study.PodcastLine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The "Next lesson" hub: Learn / Practise / Exam modules on the demo book, without a model (classic methods). */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "tr-w393dp-h852dp-xxhdpi")
class StudyHubUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var processor: BookProcessor
    @Inject lateinit var db: BookMindDatabase
    @Inject lateinit var hub: StudyHubRepository

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var bookId = ""
    private val out = System.getProperty("bookmind.screenshots")?.let(::File)
    private val tr = java.util.Locale.forLanguageTag("tr")

    @Before fun setUp() {
        hilt.inject()
        runBlocking {
            settings.clear()
            settings.update { it.copy(onboardingDone = true) }
            bookId = library.addDemoBook().also { processor.process(it) }
        }
    }

    @After fun tearDown() { scenario?.close() }

    private fun pause(ms: Long = 300) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }

    private fun waitForText(text: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(30)
        }
        throw AssertionError("not found: $text")
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 20_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(30)
        }
        throw AssertionError("not found: $tag")
    }

    private fun click(text: String) = compose.onAllNodesWithText(text).onFirst().performScrollTo().performClick()

    private fun back() { scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }; pause(400) }

    private fun shot(name: String) {
        val dir = out ?: return
        dir.mkdirs()
        pause(300); compose.waitForIdle()
        var bmp: Bitmap? = null
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(dir, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Library → demo book → "Next lesson" card → hub. */
    private fun openHub() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(s(R.string.home_title))
        compose.onAllNodesWithText(s(R.string.tab_library)).onFirst().performClick()
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForTag("next_lesson")
        shot("study_00_book")
        compose.onNodeWithTag("next_lesson").performScrollTo().performClick()
        waitForTag("study_hub")
        waitForText(s(R.string.study_tab_learn).uppercase(tr))
    }

    private fun studyItems() = runBlocking { db.learning().forBook(db.users().current()!!.id, bookId) }.filter { it.prompt.isNotBlank() }

    @Test fun hub_shows_learn_practice_exam_and_flashcards_feed_the_review_queue() {
        openHub()
        // ÖĞREN grid with the recommended flashcards card.
        compose.onNodeWithText(s(R.string.study_recommended).uppercase(tr)).assertExists()
        compose.onNodeWithText(s(R.string.study_podcast)).assertExists()
        shot("study_01_hub_learn")
        compose.onNodeWithText(s(R.string.study_tab_practice).uppercase(tr)).performClick()
        waitForText(s(R.string.study_true_false))
        shot("study_02_hub_practice")
        compose.onNodeWithText(s(R.string.study_tab_exam).uppercase(tr)).performClick()
        waitForText(s(R.string.study_written_setup))
        compose.onNodeWithText(s(R.string.study_written_body)).assertExists()
        compose.onNodeWithText(s(R.string.study_oral_setup)).assertExists()
        shot("study_03_hub_exam")
        compose.onNodeWithText(s(R.string.study_tab_learn).uppercase(tr)).performClick()

        compose.onNodeWithTag("module_FLASHCARDS").performClick()
        waitForTag("flashcard")
        shot("study_04_flashcard_front")
        compose.onNodeWithTag("flashcard").performClick()
        pause(700)
        shot("study_05_flashcard_back")
        val before = studyItems().size
        click(s(R.string.study_again))
        waitForText(s(R.string.study_card_n, 2, 99).substringBefore("99"))
        pause(500)
        assertTrue(studyItems().size > before, "flashcard marked 'again' goes to the review queue")
    }

    @Test fun notes_podcast_and_gaps_open_with_classic_content() {
        openHub()
        compose.onNodeWithTag("module_NOTES").performClick()
        waitForText(s(R.string.study_main_idea).uppercase(tr))
        compose.onNodeWithText(s(R.string.study_key_concepts)).assertExists()
        compose.onNodeWithText(s(R.string.origin_local)).assertExists()
        shot("study_06_notes")
        back()
        compose.onNodeWithTag("module_PODCAST").performClick()
        waitForTag("podcast")
        waitForText(s(R.string.study_host))
        compose.onNodeWithText(s(R.string.study_transcript)).assertExists()
        shot("study_07_podcast")
        back()
        compose.onNodeWithTag("module_GAPS").performClick()
        waitForText(s(R.string.study_gaps_prereq))
        shot("study_08_gaps")
        val done = runBlocking { hub.lessons(bookId) }.first { it.done.isNotEmpty() }.done
        assertTrue(StudyModule.NOTES in done && StudyModule.GAPS in done, "$done")
    }

    @Test fun multiple_choice_and_true_false_explain_answers_and_complete_the_lesson() {
        openHub()
        compose.onNodeWithText(s(R.string.study_tab_practice).uppercase(tr)).performClick()
        compose.onNodeWithTag("module_MCQ").performClick()
        waitForTag("option_0")
        shot("study_09_mcq")
        var rounds = 0
        while (compose.onAllNodesWithTag("session_summary").fetchSemanticsNodes().isEmpty() && rounds++ < 12) {
            compose.onNodeWithTag("option_0").performScrollTo().performClick()
            pause(200)
            if (rounds == 1) shot("study_10_mcq_answered")
            val next = compose.onAllNodesWithText(s(R.string.next)).fetchSemanticsNodes().isNotEmpty()
            click(s(if (next) R.string.next else R.string.study_finish))
            pause(200)
        }
        waitForTag("session_summary")
        shot("study_11_mcq_done")
        val lesson = runBlocking { hub.lessons(bookId) }.first { StudyModule.MCQ in it.done }
        assertTrue(lesson.completed)
        back()
        compose.onNodeWithTag("module_TRUE_FALSE").performClick()
        waitForTag("tf_true")
        compose.onNodeWithTag("tf_true").performClick()
        pause(200)
        // The explanation appears right away.
        assertTrue(compose.onAllNodesWithText(s(R.string.correct)).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText(s(R.string.incorrect)).fetchSemanticsNodes().isNotEmpty())
        shot("study_12_true_false")
        back()
        compose.onNodeWithTag("module_EXERCISES").performClick()
        waitForText(s(R.string.study_cloze))
        shot("study_13_exercises")
    }

    @Test fun written_exam_grades_each_answer_and_shows_a_report_card() {
        openHub()
        compose.onNodeWithText(s(R.string.study_tab_exam).uppercase(tr)).performClick()
        click(s(R.string.study_written_setup))
        waitForText(s(R.string.study_question_count))
        shot("study_14_exam_setup")
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithText(s(R.string.study_start_exam)).performClick()
        waitForTag("answer_0")
        compose.onNodeWithTag("answer_0").performTextInput("Tekrarları zamana yaymak hafızayı güçlendirir ve unutmayı yavaşlatır.")
        shot("study_15_written_exam")
        click(s(R.string.study_submit_exam))
        waitForTag("written_report")
        waitForText(s(R.string.study_report_card).uppercase(tr))
        shot("study_16_report_card")
        assertTrue(runBlocking { hub.lessons(bookId) }.any { StudyModule.WRITTEN_EXAM in it.done })
        // Blank answers scored 0 and went to the review queue.
        assertTrue(studyItems().any { it.itemType == com.bookmind.app.data.db.LearningItemType.WRONG_ANSWER })
    }

    @Test fun oral_exam_works_by_typing_when_the_phone_has_no_speech_recognition() {
        openHub()
        compose.onNodeWithText(s(R.string.study_tab_exam).uppercase(tr)).performClick()
        click(s(R.string.study_oral_setup))
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithText(s(R.string.study_start_exam)).performClick()
        waitForTag("oral_answer")
        waitForText(s(R.string.study_examiner))
        shot("study_17_oral_exam")
        var rounds = 0
        while (compose.onAllNodesWithTag("oral_report").fetchSemanticsNodes().isEmpty() && rounds++ < 10) {
            compose.onNodeWithTag("oral_answer").performTextInput("Bilmiyorum, emin değilim.")
            compose.onNodeWithContentDescription(s(R.string.send)).performClick()
            pause(600)
            if (rounds == 1) shot("study_18_oral_turn")
        }
        waitForTag("oral_report")
        waitForText(s(R.string.study_oral_score).uppercase(tr))
        shot("study_19_oral_report")
        assertTrue(runBlocking { hub.lessons(bookId) }.any { StudyModule.ORAL_EXAM in it.done })
    }

    @Test fun podcast_timeline_seeks_by_words_and_lines() {
        val lines = listOf(PodcastLine("A", "Merhaba, bugün aralıklı tekrar konuşuyoruz."), PodcastLine("B", "Tekrarları zamana yaymak unutmayı yavaşlatır."))
        val t = PodcastTimeline(lines)
        assertEquals(0 to 0, t.locate(0))
        val (line, char) = t.locate(t.positionOf(0, 12))
        assertEquals(0, line)
        assertTrue(char <= 12 && (char == 0 || lines[0].text[char - 1] == ' '), "$char")
        assertEquals(1, t.locate(t.positionOf(1, 0) + 10).first)
        assertEquals(2 to 0, t.locate(t.totalMs + 5_000))
        assertTrue(t.positionOf(1, 0) > t.positionOf(0, 20))
    }

    /** Dark theme pictures of the main study screens, for visual review only. */
    @Test @Config(qualifiers = "+tr-night") fun dark_screenshots() {
        assumeTrue(out != null)
        openHub()
        shot("study_dark_01_hub")
        compose.onNodeWithTag("module_FLASHCARDS").performClick()
        waitForTag("flashcard")
        shot("study_dark_02_flashcard")
        back()
        compose.onNodeWithTag("module_PODCAST").performClick()
        waitForTag("podcast")
        shot("study_dark_03_podcast")
    }
}
