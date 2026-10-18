package com.bookmind.core.srs

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * SuperMemo-2 style fallback. It is simpler and fully explainable (interval × ease factor), which is useful
 * when a user prefers predictable intervals. FSRS stays the default.
 */
class Sm2Scheduler(
    private val relearnStepMs: Long = 10 * MINUTE_MS,
    private val maximumIntervalDays: Long = 36_500,
) : SpacedRepetitionScheduler {

    override val version: String = VERSION

    override fun schedule(current: MemoryState, rating: Rating, reviewedAt: Long): ScheduleResult {
        // SM-2 quality 0..5 mapped from the four buttons.
        val q = when (rating) {
            Rating.AGAIN -> 1
            Rating.HARD -> 3
            Rating.GOOD -> 4
            Rating.EASY -> 5
        }
        val ease = max(1.3, current.easeFactor + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
        val previousDays = if (current.isNew) 0.0 else current.stability
        val retrievability = retrievability(current, reviewedAt)

        if (rating == Rating.AGAIN) {
            val state = current.copy(
                phase = if (current.isNew) CardPhase.LEARNING else CardPhase.RELEARNING,
                stability = 1.0,
                difficulty = difficultyFromEase(ease),
                easeFactor = ease,
                lastReviewAt = reviewedAt,
                nextReviewAt = reviewedAt + relearnStepMs,
                reviewCount = current.reviewCount + 1,
                lapseCount = current.lapseCount + if (current.isNew) 0 else 1,
                lastRating = rating,
                algorithmVersion = VERSION,
            )
            return ScheduleResult(state, relearnStepMs, retrievability)
        }

        val successfulReviews = if (current.phase == CardPhase.REVIEW) current.reviewCount - current.lapseCount else 0
        var days = when {
            successfulReviews <= 0 -> if (rating == Rating.EASY) 4.0 else 1.0
            successfulReviews == 1 -> 6.0
            else -> previousDays * ease
        }
        if (rating == Rating.HARD) days = max(1.0, days * 0.8)
        if (rating == Rating.EASY && successfulReviews > 0) days *= 1.3
        val intervalDays = days.roundToLong().coerceIn(1, maximumIntervalDays)
        val state = current.copy(
            phase = CardPhase.REVIEW,
            stability = intervalDays.toDouble(),
            difficulty = difficultyFromEase(ease),
            easeFactor = ease,
            lastReviewAt = reviewedAt,
            nextReviewAt = reviewedAt + intervalDays * DAY_MS,
            reviewCount = current.reviewCount + 1,
            lastRating = rating,
            algorithmVersion = VERSION,
        )
        return ScheduleResult(state, intervalDays * DAY_MS, retrievability)
    }

    /** SM-2 has no forgetting model; we reuse the FSRS curve with the interval as stability, labelled an estimate. */
    override fun retrievability(state: MemoryState, at: Long): Double? {
        if (state.isNew || state.lastReviewAt == null || state.stability <= 0) return null
        val elapsed = max(0.0, (at - state.lastReviewAt).toDouble() / DAY_MS)
        return FsrsScheduler.forgettingCurve(elapsed, state.stability)
    }

    private fun difficultyFromEase(ease: Double): Double = (11 - (ease - 1.3) / (2.5 - 1.3) * 5).coerceIn(1.0, 10.0)

    companion object {
        const val VERSION = "sm2-1"
    }
}
