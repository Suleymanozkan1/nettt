package com.bookmind.core.learning

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Daily goal progress. Minutes come from finished study sessions of the local calendar day, so travelling to
 * another time zone moves the day boundary with the user.
 */
object DailyGoal {
    data class Session(val startedAt: Long, val completedAt: Long?)

    data class Progress(val goalMinutes: Int, val studiedMinutes: Int) {
        val fraction: Float get() = if (goalMinutes <= 0) 0f else (studiedMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
        val reached: Boolean get() = goalMinutes > 0 && studiedMinutes >= goalMinutes
        val remainingMinutes: Int get() = (goalMinutes - studiedMinutes).coerceAtLeast(0)
    }

    /** Sessions longer than this are capped (an app left open overnight should not count as study time). */
    const val MAX_SESSION_MINUTES = 90

    fun minutesOn(day: LocalDate, sessions: List<Session>, zone: ZoneId): Int {
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val totalMs = sessions.sumOf { s ->
            val end = s.completedAt ?: return@sumOf 0L
            val capped = minOf(end, s.startedAt + MAX_SESSION_MINUTES * 60_000L)
            (minOf(capped, dayEnd) - maxOf(s.startedAt, dayStart)).coerceAtLeast(0)
        }
        return (totalMs / 60_000L).toInt()
    }

    fun progress(goalMinutes: Int, now: Long, sessions: List<Session>, zone: ZoneId): Progress =
        Progress(goalMinutes, minutesOn(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), sessions, zone))

    /** Recommended session length for the home screen: 5–15 minutes, never more than what is left today. */
    fun suggestedSessionMinutes(progress: Progress, dueCount: Int): Int {
        val byDue = (dueCount * 0.5).toInt().coerceIn(5, 15)
        return if (progress.reached) 5 else minOf(byDue, progress.remainingMinutes.coerceAtLeast(5))
    }
}
