package com.bookmind.app.ui

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.bookmind.app.MainActivity
import com.bookmind.app.R
import com.bookmind.app.data.repo.LibraryRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.processing.BookProcessor
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.inject.Inject

/** Renders the main screens to PNG for visual review. Run with -Dbookmind.screenshots=<dir>; skipped otherwise. */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = HiltTestApplication::class, qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTour {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose: ComposeTestRule = createEmptyComposeRule()

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var processor: BookProcessor

    private fun back(sc: ActivityScenario<MainActivity>) { sc.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; pause(400) }
    private var scenario: ActivityScenario<MainActivity>? = null
    private val out = System.getProperty("bookmind.screenshots")?.let(::File)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun s(id: Int): String { var r = ""; scenario?.onActivity { r = it.getString(id) }; return r.ifEmpty { context.getString(id) } }

    private fun shot(name: String) {
        pause(300); compose.waitForIdle()
        var bmp: Bitmap? = null
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
        }
        File(out, "$name.png").outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitFor(text: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(30)
        }
        error("not found: $text")
    }
    private fun pause(ms: Long = 400) { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) } }
    private fun click(text: String) {
        val exact = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        compose.onAllNodesWithText(text, substring = !exact).onFirst().performClick()
    }

    private fun tour(prefix: String) {
        assumeTrue(out != null); out!!.mkdirs()
        hilt.inject()
        runBlocking { settings.clear() }
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            waitFor("Türkçe")
            if (prefix == "en_") { click("English"); pause(1500) }
            shot("${prefix}00_language")
            click(s(R.string.next)); pause(300); shot("${prefix}01_onboarding")
            repeat(3) { click(s(R.string.next)); pause(200) }
            shot("${prefix}02_onboarding_setup")
            click(s(R.string.onb_reminders)); pause(200)
            click(s(R.string.onb_start))
            waitFor(s(R.string.home_title))
            pause(500)
            runBlocking { db().books().all(db().users().current()!!.id).forEach { processor.process(it.id) } }
            runCatching { waitFor(s(R.string.home_start_reviews)) }
            shot("${prefix}03_home")
            val title = bookTitle()
            click(s(R.string.tab_library)); waitFor(title); pause(500); shot("${prefix}04_library")
            compose.onAllNodesWithText(title, substring = true).onFirst().performClick()
            waitFor(s(R.string.summaries)); pause(500); shot("${prefix}05_book")
            click(s(R.string.summary_quick)); pause(500); shot("${prefix}06a_summary_empty")
            click(s(R.string.generate)); waitFor(s(R.string.origin_local)); pause(500); shot("${prefix}06_summary")
            back(it)
            click(s(R.string.quiz)); pause(500); shot("${prefix}07_quiz_setup")
            click(s(R.string.create_quiz)); runCatching { waitFor(s(R.string.check_answer)) }; pause(500); shot("${prefix}08_quiz")
            runCatching { compose.onAllNodesWithText("A").onFirst().performClick(); pause(200); click(s(R.string.check_answer)); pause(1200); shot("${prefix}08b_quiz_feedback") }
            back(it); pause(300)
            runCatching { click(s(R.string.exit)) }; pause(500)
            click(s(R.string.concepts)); pause(400); shot("${prefix}05b_concepts")
            back(it); pause(400)
            click(s(R.string.tab_review)); runCatching { waitFor(s(R.string.show_answer)) }; pause(500); shot("${prefix}09_review")
            runCatching { click(s(R.string.show_answer)); pause(500); shot("${prefix}10_review_answer") }
            click(s(R.string.tab_progress)); runCatching { waitFor(s(R.string.forecast)) }; pause(500); shot("${prefix}11_progress")
            click(s(R.string.tab_profile)); pause(800); shot("${prefix}12_profile")
            runCatching {
                compose.onAllNodesWithText(s(R.string.ai_title)).onFirst().performScrollTo().performClick()
                waitFor(s(R.string.ai_hero_title)); pause(500); shot("${prefix}12b_ai")
                back(it); pause(400)
            }
            click(s(R.string.tab_today)); pause(800); shot("${prefix}13_home_after")
        }
    }

    @Inject lateinit var dbRef: com.bookmind.app.data.db.BookMindDatabase
    private fun db() = dbRef
    private fun bookTitle() = runBlocking { db().books().all(db().users().current()!!.id).first().title }

    @Test @Config(qualifiers = "+tr") fun tour_tr() = tour("tr_")
    @Test @Config(qualifiers = "+tr-night") fun tour_dark() = tour("dark_")

    /** CI's emulator profile: everything on the first screens must be reachable without scrolling. */
    @Test @Config(qualifiers = "tr-w320dp-h640dp-mdpi") fun small_screen() {
        assumeTrue(out != null); out!!.mkdirs()
        hilt.inject()
        runBlocking { settings.clear() }
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            waitFor("Türkçe"); compose.onNodeWithText("English").assertIsDisplayed(); shot("small_00_language")
            click(s(R.string.next)); pause(300); shot("small_01_onboarding")
            repeat(3) { click(s(R.string.next)); pause(200) }
            shot("small_02_setup")
        }
    }
    @Test @Config(qualifiers = "+en") fun tour_en() = tour("en_")
}
