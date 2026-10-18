package com.bookmind.core.srs

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Sm2AndResolverTest {
    private val t0 = 1_700_000_000_000L

    @Test fun `sm2 follows the 1, 6, interval times ease progression`() {
        val sm2 = Sm2Scheduler()
        val r1 = sm2.schedule(MemoryState(), Rating.GOOD, t0)
        assertEquals(1 * DAY_MS, r1.scheduledIntervalMs)
        val r2 = sm2.schedule(r1.state, Rating.GOOD, r1.state.nextReviewAt!!)
        assertEquals(6 * DAY_MS, r2.scheduledIntervalMs)
        val r3 = sm2.schedule(r2.state, Rating.GOOD, r2.state.nextReviewAt!!)
        assertEquals(15 * DAY_MS, r3.scheduledIntervalMs) // 6 × 2.5
        val lapse = sm2.schedule(r3.state, Rating.AGAIN, r3.state.nextReviewAt!!)
        assertEquals(1, lapse.state.lapseCount)
        assertTrue(lapse.state.easeFactor < r3.state.easeFactor)
        assertTrue(lapse.state.easeFactor >= 1.3)
    }

    @Test fun `wrong answers are always AGAIN`() {
        assertEquals(Rating.AGAIN, RatingResolver.resolve(correct = false, selfRating = Rating.EASY))
        assertEquals(Rating.AGAIN, RatingResolver.resolve(correct = null, selfRating = Rating.GOOD, score = 0.3))
    }

    @Test fun `correct but self-rated again becomes hard`() {
        assertEquals(Rating.HARD, RatingResolver.resolve(correct = true, selfRating = Rating.AGAIN))
    }

    @Test fun `hints and partial credit cap the rating at hard`() {
        assertEquals(Rating.HARD, RatingResolver.resolve(correct = true, selfRating = Rating.EASY, hintUsed = true))
        assertEquals(Rating.HARD, RatingResolver.resolve(correct = null, selfRating = Rating.GOOD, score = 0.6))
    }

    @Test fun `slow answers are not easy`() {
        assertEquals(Rating.GOOD, RatingResolver.resolve(correct = true, selfRating = Rating.EASY, responseTimeMs = 60_000))
        assertEquals(Rating.EASY, RatingResolver.resolve(correct = true, selfRating = Rating.EASY, responseTimeMs = 3_000))
    }

    @Test fun `self graded flashcards use the self rating`() {
        assertEquals(Rating.EASY, RatingResolver.resolve(correct = null, selfRating = Rating.EASY))
        assertEquals(Rating.AGAIN, RatingResolver.resolve(correct = null, selfRating = Rating.AGAIN))
        assertEquals(Rating.GOOD, RatingResolver.resolve(correct = true, selfRating = null))
    }
}
