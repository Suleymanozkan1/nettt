package com.bookmind.core.quiz

import com.bookmind.core.ai.MatchPair
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuizScorerTest {
    @Test fun `multiple choice and true false`() {
        val mcq = QuestionDraft(QuestionType.MULTIPLE_CHOICE, "?", listOf("Kararlılık", "Zorluk"), "Kararlılık")
        assertTrue(QuizScorer.score(mcq, "kararlılık").correct)
        assertFalse(QuizScorer.score(mcq, "Zorluk").correct)
        val tf = QuestionDraft(QuestionType.TRUE_FALSE, "?", answer = "false")
        assertTrue(QuizScorer.score(tf, "Yanlış").correct)
        assertFalse(QuizScorer.score(tf, "doğru").correct)
        assertFalse(QuizScorer.score(tf, "bilmem").correct)
    }

    @Test fun `typed answers tolerate case, diacritics, suffixes and small typos`() {
        val q = QuestionDraft(QuestionType.FILL_BLANK, "_____", answer = "Unutma eğrisi", acceptableAnswers = listOf("forgetting curve"))
        listOf("unutma egrisi", "UNUTMA EĞRİSİ", "unutma eğrisinin", "unutma egirsi", "Forgetting curve").forEach {
            assertTrue(QuizScorer.score(q, it).correct, it)
        }
        listOf("", "aralıklı tekrar", "unutma").forEach { assertFalse(QuizScorer.score(q, it).correct, it) }
    }

    @Test fun `calculation compares numbers`() {
        val q = QuestionDraft(QuestionType.CALCULATION, "2,5 × 4 = ?", answer = "10")
        assertTrue(QuizScorer.score(q, "10.0").correct)
        assertTrue(QuizScorer.score(q, "10,0").correct)
        assertFalse(QuizScorer.score(q, "11").correct)
    }

    @Test fun `ordering and matching give partial credit`() {
        val ord = QuestionDraft(QuestionType.ORDERING, "?", listOf("b", "a", "c"), "a | b | c")
        assertEquals(1.0, QuizScorer.score(ord, "a | b | c").score)
        assertEquals(1.0 / 3, QuizScorer.score(ord, "a | c | b").score, 1e-9)
        val match = QuestionDraft(QuestionType.MATCHING, "?", pairs = listOf(MatchPair("A", "1"), MatchPair("B", "2")))
        assertEquals(1.0, QuizScorer.score(match, "A=1 | B=2").score)
        assertEquals(0.5, QuizScorer.score(match, "A=1 | B=1").score)
    }

    @Test fun `display answer is human readable`() {
        assertEquals("Yanlış", QuizScorer.displayAnswer(QuestionDraft(QuestionType.TRUE_FALSE, "?", answer = "false")))
        assertEquals("a → b", QuizScorer.displayAnswer(QuestionDraft(QuestionType.ORDERING, "?", answer = "a | b")))
    }
}
