package com.bookmind.core.learning

import java.time.LocalDate

/**
 * Daily learning streak with forgiving rules: a missed day can be covered by a "freeze" (earned one per
 * 7-day streak, at most 2 held), so a single missed day does not wipe out progress.
 */
object Streak {
    data class Result(val current: Int, val longest: Int, val freezesUsed: Int, val freezesLeft: Int, val activeToday: Boolean)

    fun compute(activeDays: Set<LocalDate>, today: LocalDate, maxFreezes: Int = 2): Result {
        if (activeDays.isEmpty()) return Result(0, 0, 0, 0, false)
        val sorted = activeDays.sorted()
        var longest = 0
        var run = 0
        var freezes = 0
        var used = 0
        var prev: LocalDate? = null
        for (d in sorted) {
            if (prev == null) run = 1 else {
                val gap = java.time.temporal.ChronoUnit.DAYS.between(prev, d).toInt() - 1
                if (gap == 0) run++
                else if (gap in 1..freezes) { freezes -= gap; used += gap; run++ }
                else { run = 1; freezes = 0 }
            }
            if (run % 7 == 0) freezes = minOf(maxFreezes, freezes + 1)
            longest = maxOf(longest, run)
            prev = d
        }
        val last = sorted.last()
        val gapToToday = java.time.temporal.ChronoUnit.DAYS.between(last, today).toInt()
        val current = when {
            gapToToday <= 1 -> run // today or yesterday: streak is still alive
            gapToToday - 1 <= freezes -> run
            else -> 0
        }
        return Result(current, longest, used, freezes, last == today)
    }
}
