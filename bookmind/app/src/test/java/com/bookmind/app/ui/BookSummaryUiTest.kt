package com.bookmind.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.bookmind.app.MainActivity
import com.bookmind.app.R
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.data.summary.BookSummaryRepository
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject
import kotlin.test.assertEquals

/** The book page's Gemini summary card and the summary reader, with a fake Gemini model. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "tr-w393dp-h852dp-xxhdpi")
class BookSummaryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var processor: BookProcessor
    @Inject lateinit var summaries: BookSummaryRepository

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val out = System.getProperty("bookmind.screenshots")?.let(::File)
    private var bookId = ""

    @Before fun setUp() {
        TestGeminiModule.reset()
        TestGeminiModule.configured = true
        hilt.inject()
        runBlocking {
            settings.clear()
            settings.update { it.copy(onboardingDone = true) }
            bookId = library.addDemoBook().also { processor.process(it) }
        }
    }

    @After fun tearDown() { scenario?.close(); summaries.deleteAll(); TestGeminiModule.reset() }

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

    private fun waitForTag(tag: String) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(30)
        }
        throw AssertionError("not found: $tag")
    }

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

    private fun openBook() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(s(R.string.home_title))
        compose.onAllNodesWithText(s(R.string.tab_library)).onFirst().performClick()
        waitForText("Öğrenmenin Bilimi")
        compose.onAllNodesWithText("Öğrenmenin Bilimi").onFirst().performClick()
        waitForTag("book_summary_card")
    }

    @Test fun summary_card_starts_the_summary_and_the_reader_shows_book_and_chapter_summaries() {
        openBook()
        compose.onNodeWithText(s(R.string.book_summary_privacy)).assertExists()
        shot("gemini_01_book_card")
        compose.onNodeWithText(s(R.string.book_summary_start)).performScrollTo().performClick()
        // Queued in WorkManager until a connection is available (tests: constraints are never met).
        waitForText(s(R.string.book_summary_queued))
        assertEquals(BookSummaryRepository.Phase.QUEUED, summaries.status(bookId).phase)
        // Run the work directly, as the worker would.
        runBlocking { summaries.run(bookId) }
        waitForTag("open_book_summary")
        shot("gemini_02_book_card_done")
        compose.onNodeWithTag("open_book_summary").performScrollTo().performClick()
        waitForTag("book_summary")
        waitForText("Kitabın ana fikri")
        compose.onNodeWithText(s(R.string.book_summary_tab_overall)).assertExists()
        shot("gemini_03_reader_overall")
        compose.onNodeWithText(s(R.string.book_summary_tab_chapters)).performClick()
        waitForText("Unutma Eğrisi")
        shot("gemini_04_reader_chapters")
    }

    @Test fun a_waiting_rate_limit_is_explained_on_the_card() {
        TestGeminiModule.fake.failWith = { com.bookmind.core.ai.AiException.QuotaExceeded(System.currentTimeMillis() + 3_600_000) }
        runBlocking { summaries.run(bookId) }
        openBook()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("book_summary_card"))
        waitForText(s(R.string.book_summary_waiting, "").substringBefore("%").take(20))
        shot("gemini_05_waiting_limit")
    }
}
