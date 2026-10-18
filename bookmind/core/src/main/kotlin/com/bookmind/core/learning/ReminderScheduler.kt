package com.bookmind.core.learning

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Computes the next reminder time in the user's local time zone. Pure function so DST changes, travel and
 * paused periods can be unit tested; WorkManager only receives the resulting delay.
 */
object ReminderScheduler {
    data class Preferences(
        val enabled: Boolean,
        val time: LocalTime,
        val days: Set<DayOfWeek>,
        /** Holiday/pause mode: no reminders until this date (inclusive). */
        val pausedUntil: LocalDate? = null,
        /** 1 = one reminder a day; 2 = also an evening nudge if nothing was studied. */
        val intensity: Int = 1,
        val eveningNudgeTime: LocalTime = LocalTime.of(20, 30),
    )

    fun next(prefs: Preferences, now: ZonedDateTime): ZonedDateTime? {
        if (!prefs.enabled || prefs.days.isEmpty()) return null
        val times = buildList {
            add(prefs.time)
            if (prefs.intensity >= 2 && prefs.eveningNudgeTime.isAfter(prefs.time)) add(prefs.eveningNudgeTime)
        }
        for (offset in 0..14L) {
            val date = now.toLocalDate().plusDays(offset)
            if (date.dayOfWeek !in prefs.days) continue
            if (prefs.pausedUntil != null && !date.isAfter(prefs.pausedUntil)) continue
            for (t in times) {
                // atZone resolves DST gaps by moving forward, so 02:30 on a spring-forward day becomes 03:30.
                val candidate = ZonedDateTime.of(date, t, now.zone)
                if (candidate.isAfter(now)) return candidate
            }
        }
        return null
    }

    fun delayMillis(prefs: Preferences, nowMillis: Long, zone: ZoneId): Long? {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        return next(prefs, now)?.toInstant()?.toEpochMilli()?.minus(nowMillis)
    }

    /** Whether the evening nudge should actually be shown (skip it if the daily goal is already reached). */
    fun shouldShowEveningNudge(goalReached: Boolean, dueCount: Int) = !goalReached && dueCount > 0
}
