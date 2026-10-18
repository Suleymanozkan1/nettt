package com.bookmind.core.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-4.5 (Free Spaced Repetition Scheduler) implemented from the published formulas of the
 * open-spaced-repetition project (https://github.com/open-spaced-repetition, MIT licensed).
 *
 * The model keeps two variables per item: stability S (days until recall probability drops to 90 %) and
 * difficulty D (1..10). Retrievability follows the power forgetting curve R(t, S) = (1 + F·t/S)^DECAY.
 *
 * Learning steps are kept deliberately short: a failed or brand-new item that the user could not recall
 * comes back after [relearnStepMs] in the same session; everything else is scheduled in whole days.
 */
class FsrsScheduler(
    private val params: DoubleArray = DEFAULT_WEIGHTS,
    private val desiredRetention: Double = 0.9,
    private val maximumIntervalDays: Int = 36_500,
    private val relearnStepMs: Long = 10 * MINUTE_MS,
    /** Optional interval fuzz (±5 %) seeded by caller so tests stay deterministic. */
    private val fuzz: ((intervalDays: Long) -> Long)? = null,
) : SpacedRepetitionScheduler {

    init {
        require(params.size == 17) { "FSRS-4.5 needs 17 weights, got ${params.size}" }
        require(desiredRetention in 0.7..0.97) { "desiredRetention must be within 0.70..0.97" }
    }

    override val version: String = VERSION

    override fun schedule(current: MemoryState, rating: Rating, reviewedAt: Long): ScheduleResult {
        if (current.isNew || current.stability <= 0.0) {
            val s = initialStability(rating)
            val d = initialDifficulty(rating)
            return finish(current, rating, reviewedAt, s, d, retrievabilityBefore = null, lapse = false)
        }

        val elapsedDays = max(0.0, (reviewedAt - (current.lastReviewAt ?: reviewedAt)).toDouble() / DAY_MS)
        val r = forgettingCurve(elapsedDays, current.stability)
        val d = nextDifficulty(current.difficulty, rating)
        val s = if (rating == Rating.AGAIN) {
            nextForgetStability(current.difficulty, current.stability, r)
        } else {
            nextRecallStability(current.difficulty, current.stability, r, rating)
        }
        return finish(current, rating, reviewedAt, s, d, retrievabilityBefore = r, lapse = rating == Rating.AGAIN)
    }

    override fun retrievability(state: MemoryState, at: Long): Double? {
        if (state.isNew || state.stability <= 0.0 || state.lastReviewAt == null) return null
        val elapsedDays = max(0.0, (at - state.lastReviewAt).toDouble() / DAY_MS)
        return forgettingCurve(elapsedDays, state.stability)
    }

    private fun finish(
        current: MemoryState,
        rating: Rating,
        reviewedAt: Long,
        stability: Double,
        difficulty: Double,
        retrievabilityBefore: Double?,
        lapse: Boolean,
    ): ScheduleResult {
        val intervalMs: Long
        val phase: CardPhase
        if (rating == Rating.AGAIN) {
            intervalMs = relearnStepMs
            phase = if (current.isNew) CardPhase.LEARNING else CardPhase.RELEARNING
        } else {
            var days = nextIntervalDays(stability)
            fuzz?.let { days = it(days).coerceIn(1, maximumIntervalDays.toLong()) }
            intervalMs = days * DAY_MS
            phase = CardPhase.REVIEW
        }
        val state = current.copy(
            phase = phase,
            stability = stability,
            difficulty = difficulty,
            lastReviewAt = reviewedAt,
            nextReviewAt = reviewedAt + intervalMs,
            reviewCount = current.reviewCount + 1,
            lapseCount = current.lapseCount + if (lapse) 1 else 0,
            lastRating = rating,
            algorithmVersion = VERSION,
        )
        return ScheduleResult(state, intervalMs, retrievabilityBefore)
    }

    internal fun initialStability(rating: Rating): Double = max(params[rating.grade - 1], 0.1)

    internal fun initialDifficulty(rating: Rating): Double =
        clampDifficulty(params[4] - (rating.grade - 3) * params[5])

    internal fun nextDifficulty(d: Double, rating: Rating): Double {
        val next = d - params[6] * (rating.grade - 3)
        // Mean reversion towards the initial difficulty of a "Good" answer keeps D from drifting to the bounds.
        return clampDifficulty(params[7] * initialDifficulty(Rating.GOOD) + (1 - params[7]) * next)
    }

    internal fun nextRecallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) params[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) params[16] else 1.0
        return s * (1 + exp(params[8]) * (11 - d) * s.pow(-params[9]) *
            (exp((1 - r) * params[10]) - 1) * hardPenalty * easyBonus)
    }

    internal fun nextForgetStability(d: Double, s: Double, r: Double): Double {
        val next = params[11] * d.pow(-params[12]) * ((s + 1).pow(params[13]) - 1) * exp((1 - r) * params[14])
        return min(next, s).coerceAtLeast(0.1)
    }

    internal fun nextIntervalDays(stability: Double): Long {
        val interval = stability / FACTOR * (desiredRetention.pow(1 / DECAY) - 1)
        return interval.roundToLong().coerceIn(1, maximumIntervalDays.toLong())
    }

    private fun clampDifficulty(d: Double) = d.coerceIn(1.0, 10.0)

    companion object {
        const val VERSION = "fsrs-4.5"
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0

        /** Default FSRS-4.5 weights published by open-spaced-repetition. */
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474,
            0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
        )

        fun forgettingCurve(elapsedDays: Double, stability: Double): Double =
            (1 + FACTOR * elapsedDays / stability).pow(DECAY)

        /** Deterministic ±5 % fuzz (intervals ≥ 3 days) keyed by an item id, so reviews of a batch spread out. */
        fun seededFuzz(seed: String): (Long) -> Long = { days ->
            if (days < 3) days else {
                val spread = max(1L, (days * 0.05).roundToLong())
                val offset = Math.floorMod(seed.hashCode().toLong() * 31 + days, 2 * spread + 1) - spread
                days + offset
            }
        }
    }
}
