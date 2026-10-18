package com.bookmind.core.learning

import com.bookmind.core.ai.RecallLevel
import kotlin.math.sqrt

/**
 * Learning analytics from the review log. Keeps three kinds of numbers apart: measured (what the user
 * actually answered), predicted (algorithm estimates), and self-reported (the user's own rating).
 */
object LearningAnalytics {
    data class ReviewEvent(
        val itemId: String,
        val at: Long,
        val correct: Boolean,
        val selfRating: Int?,
        val elapsedDaysSincePrevious: Double?,
        val hintUsed: Boolean,
        val level: RecallLevel,
        val firstAttempt: Boolean,
    )

    data class Rate(val correct: Int, val total: Int) {
        val value: Double? get() = if (total == 0) null else correct.toDouble() / total

        /** 95 % Wilson interval; with few answers it is wide, which the UI shows instead of a falsely precise %. */
        fun interval(): ClosedFloatingPointRange<Double>? {
            if (total == 0) return null
            val z = 1.96
            val p = correct.toDouble() / total
            val denom = 1 + z * z / total
            val centre = (p + z * z / (2 * total)) / denom
            val half = z * sqrt(p * (1 - p) / total + z * z / (4.0 * total * total)) / denom
            return (centre - half).coerceAtLeast(0.0)..(centre + half).coerceAtMost(1.0)
        }

        /** Below this many answers we do not draw conclusions. */
        val enoughData: Boolean get() = total >= MIN_SAMPLE
    }

    const val MIN_SAMPLE = 10

    data class Report(
        val overall: Rate,
        val firstAttempt: Rate,
        /** Correct answers given at least a day after the previous review: the best signal of durable memory. */
        val delayedRecall: Rate,
        val unaidedRecall: Rate,
        val byLevel: Map<RecallLevel, Rate>,
        val selfReportedEasyShare: Double?,
    )

    fun report(events: List<ReviewEvent>): Report {
        fun rate(list: List<ReviewEvent>) = Rate(list.count { it.correct }, list.size)
        val rated = events.filter { it.selfRating != null }
        return Report(
            overall = rate(events),
            firstAttempt = rate(events.filter { it.firstAttempt }),
            delayedRecall = rate(events.filter { (it.elapsedDaysSincePrevious ?: 0.0) >= 1.0 }),
            unaidedRecall = rate(events.filter { !it.hintUsed && it.level != RecallLevel.RECOGNITION }),
            byLevel = RecallLevel.entries.associateWith { l -> rate(events.filter { it.level == l }) },
            selfReportedEasyShare = if (rated.isEmpty()) null else rated.count { it.selfRating == 4 }.toDouble() / rated.size,
        )
    }

    /**
     * Suggests an active-recall exercise: if the user recognises answers but fails to produce or explain them,
     * propose recall/explanation tasks (section 10 of the spec).
     */
    fun recallGapSuggestion(r: Report): RecallLevel? {
        val recog = r.byLevel[RecallLevel.RECOGNITION]?.takeIf { it.enoughData }?.value ?: return null
        val recall = r.byLevel[RecallLevel.RECALL]?.takeIf { it.total >= 3 }?.value
        val explain = r.byLevel[RecallLevel.EXPLANATION]?.takeIf { it.total >= 3 }?.value
        return when {
            recall != null && recog - recall >= 0.25 -> RecallLevel.RECALL
            explain != null && recog - explain >= 0.25 -> RecallLevel.EXPLANATION
            recall == null && recog >= 0.8 -> RecallLevel.RECALL
            else -> null
        }
    }

    /**
     * A concept counts as "retained" (kalıcı) only after a correct, unaided recall at least [minDays] after the
     * previous review — not because a summary was read or a multiple-choice question was ticked.
     */
    fun isRetained(eventsForItem: List<ReviewEvent>, minDays: Double = 21.0): Boolean =
        eventsForItem.any { it.correct && !it.hintUsed && it.level != RecallLevel.RECOGNITION && (it.elapsedDaysSincePrevious ?: 0.0) >= minDays }

    /** Weak = recent accuracy below 60 % over at least 3 answers, or 2+ lapses in the last 5 reviews. */
    fun isWeak(eventsForItem: List<ReviewEvent>): Boolean {
        val recent = eventsForItem.sortedBy { it.at }.takeLast(5)
        if (recent.size >= 3 && recent.count { it.correct }.toDouble() / recent.size < 0.6) return true
        return recent.count { !it.correct } >= 2
    }
}
