package com.bookmind.core.srs

/**
 * The four review grades shown to the user. Values match the FSRS grade numbers (1..4).
 */
enum class Rating(val grade: Int) {
    /** "Hiç hatırlayamadım" */
    AGAIN(1),

    /** "Zor hatırladım" */
    HARD(2),

    /** "Doğru hatırladım" */
    GOOD(3),

    /** "Çok kolaydı" */
    EASY(4);

    companion object {
        fun fromGrade(grade: Int): Rating =
            entries.firstOrNull { it.grade == grade } ?: throw IllegalArgumentException("Unknown grade $grade")
    }
}

enum class CardPhase { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * Scheduler-facing memory state of one learning item. Times are epoch milliseconds (UTC), so the state is
 * independent of the device time zone; only notification/day-boundary code deals with local time.
 */
data class MemoryState(
    val phase: CardPhase = CardPhase.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val lastReviewAt: Long? = null,
    val nextReviewAt: Long? = null,
    val reviewCount: Int = 0,
    val lapseCount: Int = 0,
    val lastRating: Rating? = null,
    /** SM-2 only: ease factor. Kept so switching algorithms never loses data. */
    val easeFactor: Double = 2.5,
    val algorithmVersion: String = "",
) {
    val isNew: Boolean get() = phase == CardPhase.NEW
}

data class ScheduleResult(
    val state: MemoryState,
    /** Interval between this review and the next due date. */
    val scheduledIntervalMs: Long,
    /** Retrievability the algorithm estimated right before this review (null for a new card). */
    val retrievabilityBefore: Double?,
)

/**
 * Spaced-repetition algorithm boundary. Implementations are pure: no clock, no I/O, so they can be unit
 * tested and swapped without touching UI or storage code.
 */
interface SpacedRepetitionScheduler {
    val version: String

    fun schedule(current: MemoryState, rating: Rating, reviewedAt: Long): ScheduleResult

    /** Algorithm estimate of recall probability at [at]. This is a model prediction, not a measurement. */
    fun retrievability(state: MemoryState, at: Long): Double?

    /** Preview of the next interval for each rating, for the "next review in …" labels on rating buttons. */
    fun preview(current: MemoryState, reviewedAt: Long): Map<Rating, Long> =
        Rating.entries.associateWith { schedule(current, it, reviewedAt).scheduledIntervalMs }
}

internal const val MINUTE_MS = 60_000L
internal const val DAY_MS = 86_400_000L
