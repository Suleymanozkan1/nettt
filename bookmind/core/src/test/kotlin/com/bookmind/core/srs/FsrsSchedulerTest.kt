package com.bookmind.core.srs

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FsrsSchedulerTest {
    private val fsrs = FsrsScheduler()
    private val t0 = 1_700_000_000_000L

    @Test fun `new card intervals grow with the rating`() {
        val new = MemoryState()
        val again = fsrs.schedule(new, Rating.AGAIN, t0)
        val hard = fsrs.schedule(new, Rating.HARD, t0)
        val good = fsrs.schedule(new, Rating.GOOD, t0)
        val easy = fsrs.schedule(new, Rating.EASY, t0)
        assertEquals(10 * MINUTE_MS, again.scheduledIntervalMs)
        assertEquals(CardPhase.LEARNING, again.state.phase)
        assertTrue(hard.scheduledIntervalMs <= good.scheduledIntervalMs)
        assertTrue(good.scheduledIntervalMs < easy.scheduledIntervalMs)
        // With the default weights and 90 % retention, interval ≈ initial stability: Good ≈ 4 days, Easy ≈ 14 days.
        assertEquals(4 * DAY_MS, good.scheduledIntervalMs)
        assertEquals(14 * DAY_MS, easy.scheduledIntervalMs)
        assertNull(good.retrievabilityBefore)
        assertEquals(FsrsScheduler.VERSION, good.state.algorithmVersion)
    }

    @Test fun `retrievability is 90 percent after stability days`() {
        val r = FsrsScheduler.forgettingCurve(elapsedDays = 10.0, stability = 10.0)
        assertTrue(abs(r - 0.9) < 1e-9)
        assertTrue(FsrsScheduler.forgettingCurve(0.0, 5.0) == 1.0)
        assertTrue(FsrsScheduler.forgettingCurve(30.0, 5.0) < FsrsScheduler.forgettingCurve(10.0, 5.0))
    }

    @Test fun `successive good reviews on time keep increasing the interval`() {
        var state = fsrs.schedule(MemoryState(), Rating.GOOD, t0).state
        var previous = 0L
        repeat(6) {
            val result = fsrs.schedule(state, Rating.GOOD, state.nextReviewAt!!)
            assertTrue(result.scheduledIntervalMs > previous, "interval should grow: ${result.scheduledIntervalMs} > $previous")
            previous = result.scheduledIntervalMs
            state = result.state
        }
        assertEquals(7, state.reviewCount)
        assertEquals(0, state.lapseCount)
        assertTrue(previous > 60 * DAY_MS)
    }

    @Test fun `a lapse lowers stability, counts the lapse and relearns soon`() {
        val learned = fsrs.schedule(fsrs.schedule(MemoryState(), Rating.GOOD, t0).state, Rating.GOOD, t0 + 4 * DAY_MS).state
        val lapse = fsrs.schedule(learned, Rating.AGAIN, learned.nextReviewAt!!)
        assertTrue(lapse.state.stability < learned.stability)
        assertEquals(1, lapse.state.lapseCount)
        assertEquals(CardPhase.RELEARNING, lapse.state.phase)
        assertEquals(10 * MINUTE_MS, lapse.scheduledIntervalMs)
        assertTrue(lapse.state.difficulty > learned.difficulty)
    }

    @Test fun `difficulty stays within bounds`() {
        var state = fsrs.schedule(MemoryState(), Rating.AGAIN, t0).state
        repeat(30) { state = fsrs.schedule(state, Rating.AGAIN, (state.nextReviewAt ?: t0) + DAY_MS).state }
        assertTrue(state.difficulty in 1.0..10.0)
        var easy = fsrs.schedule(MemoryState(), Rating.EASY, t0).state
        repeat(30) { easy = fsrs.schedule(easy, Rating.EASY, easy.nextReviewAt!!).state }
        assertTrue(easy.difficulty in 1.0..10.0)
        assertTrue(easy.nextReviewAt!! - easy.lastReviewAt!! <= 36_500 * DAY_MS)
    }

    @Test fun `a late successful review strengthens memory more than an early one`() {
        val s = fsrs.schedule(MemoryState(), Rating.GOOD, t0).state
        val onTime = fsrs.schedule(s, Rating.GOOD, s.nextReviewAt!!)
        val late = fsrs.schedule(s, Rating.GOOD, s.nextReviewAt!! + 10 * DAY_MS)
        val early = fsrs.schedule(s, Rating.GOOD, s.lastReviewAt!! + 1 * DAY_MS)
        assertTrue(late.state.stability > onTime.state.stability)
        assertTrue(onTime.state.stability > early.state.stability)
        assertTrue(late.retrievabilityBefore!! < onTime.retrievabilityBefore!!)
    }

    @Test fun `higher desired retention gives shorter intervals`() {
        val strict = FsrsScheduler(desiredRetention = 0.95)
        val relaxed = FsrsScheduler(desiredRetention = 0.8)
        val s = 20.0
        assertTrue(strict.nextIntervalDays(s) < fsrs.nextIntervalDays(s))
        assertTrue(relaxed.nextIntervalDays(s) > fsrs.nextIntervalDays(s))
    }

    @Test fun `seeded fuzz is deterministic and bounded`() {
        val f = FsrsScheduler.seededFuzz("item-42")
        assertEquals(f(40), f(40))
        assertTrue(f(40) in 38..42)
        assertEquals(2, f(2))
    }

    @Test fun `preview lists the interval for each rating`() {
        val preview = fsrs.preview(MemoryState(), t0)
        assertEquals(Rating.entries.toSet(), preview.keys)
        assertTrue(preview.getValue(Rating.AGAIN) < preview.getValue(Rating.EASY))
    }

    @Test fun `retrievability is unknown for new cards`() {
        assertNull(fsrs.retrievability(MemoryState(), t0))
        val s = fsrs.schedule(MemoryState(), Rating.GOOD, t0).state
        assertEquals(1.0, fsrs.retrievability(s, t0))
    }
}
