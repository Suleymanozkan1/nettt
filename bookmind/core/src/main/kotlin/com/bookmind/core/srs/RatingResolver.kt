package com.bookmind.core.srs

/**
 * Combines what actually happened (was the answer correct, was a hint used, how long it took) with the user's
 * self-assessment. Objective evidence wins: a wrong answer is always AGAIN, and a user cannot rate EASY an item
 * they needed a hint for.
 */
object RatingResolver {
    /** Answers slower than this are not considered "very easy" even if the user says so. */
    const val SLOW_ANSWER_MS = 25_000L

    fun resolve(
        correct: Boolean?,
        selfRating: Rating?,
        responseTimeMs: Long? = null,
        hintUsed: Boolean = false,
        /** Partial credit (0..1) for open answers. Below 0.5 counts as wrong, below 0.8 caps at HARD. */
        score: Double? = null,
    ): Rating {
        val objectivelyCorrect = when {
            score != null -> score >= 0.5
            else -> correct
        }
        if (objectivelyCorrect == false) return Rating.AGAIN

        var rating = selfRating ?: Rating.GOOD
        if (objectivelyCorrect == true && rating == Rating.AGAIN) {
            // Correct but the user felt they guessed: treat as a hard recall, not as a lapse.
            rating = Rating.HARD
        }
        if (hintUsed && rating > Rating.HARD) rating = Rating.HARD
        if (score != null && score < 0.8 && rating > Rating.HARD) rating = Rating.HARD
        if (rating == Rating.EASY && responseTimeMs != null && responseTimeMs > SLOW_ANSWER_MS) rating = Rating.GOOD
        return rating
    }
}
