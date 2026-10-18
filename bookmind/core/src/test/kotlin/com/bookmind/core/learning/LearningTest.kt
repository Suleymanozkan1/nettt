package com.bookmind.core.learning

import com.bookmind.core.ai.RecallLevel
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LearningTest {
    private val istanbul = ZoneId.of("Europe/Istanbul")

    @Test fun `daily goal counts minutes of the local day and caps runaway sessions`() {
        val day = LocalDate.of(2026, 3, 10)
        val at = { h: Int, m: Int -> ZonedDateTime.of(day, LocalTime.of(h, m), istanbul).toInstant().toEpochMilli() }
        val sessions = listOf(
            DailyGoal.Session(at(8, 0), at(8, 12)),
            DailyGoal.Session(at(23, 50), at(23, 59) + 20 * 60_000), // crosses midnight: only 10 min today
            DailyGoal.Session(at(12, 0), null), // unfinished
            DailyGoal.Session(at(13, 0), at(13, 0) + 5 * 3_600_000), // left open: capped at 90
        )
        assertEquals(12 + 10 + 90, DailyGoal.minutesOn(day, sessions, istanbul))
        val p = DailyGoal.progress(20, at(14, 0), sessions, istanbul)
        assertTrue(p.reached)
        assertEquals(1f, p.fraction)
        assertEquals(5, DailyGoal.suggestedSessionMinutes(DailyGoal.Progress(10, 8), dueCount = 40))
        assertEquals(10, DailyGoal.suggestedSessionMinutes(DailyGoal.Progress(30, 0), dueCount = 20))
    }

    @Test fun `streak survives a missed day with a freeze`() {
        val start = LocalDate.of(2026, 1, 1)
        val days = (0L until 7).map { start.plusDays(it) }.toSet() + start.plusDays(8) + start.plusDays(9)
        val s = Streak.compute(days, start.plusDays(9))
        assertEquals(9, s.current)
        assertEquals(1, s.freezesUsed)
        assertTrue(s.activeToday)
        val broken = Streak.compute(setOf(start, start.plusDays(1)), start.plusDays(5))
        assertEquals(0, broken.current)
        assertEquals(2, broken.longest)
        assertEquals(2, Streak.compute(setOf(start, start.plusDays(1)), start.plusDays(2)).current)
    }

    @Test fun `xp rewards quality over speed`() {
        val recog = Gamification.xp(Gamification.XpEvent(Gamification.Kind.QUIZ_ANSWER, level = RecallLevel.RECOGNITION))
        val explain = Gamification.xp(Gamification.XpEvent(Gamification.Kind.QUIZ_ANSWER, level = RecallLevel.EXPLANATION))
        val delayed = Gamification.xp(Gamification.XpEvent(Gamification.Kind.REVIEW, level = RecallLevel.RECALL, delayedDays = 30.0))
        val hinted = Gamification.xp(Gamification.XpEvent(Gamification.Kind.REVIEW, level = RecallLevel.RECALL, delayedDays = 30.0, hintUsed = true))
        assertTrue(explain > recog)
        assertTrue(delayed > Gamification.xp(Gamification.XpEvent(Gamification.Kind.REVIEW, level = RecallLevel.RECALL)))
        assertTrue(hinted < delayed)
        assertEquals(1, Gamification.level(99)); assertEquals(2, Gamification.level(100)); assertEquals(3, Gamification.level(300))
        val badges = Gamification.earnedBadges(Gamification.Stats(1, 8, 0, 0, 0, 0, 0)).map { it.id }
        assertEquals(listOf("first_review", "streak_7"), badges)
    }

    @Test fun `plan respects the daily budget, orders prerequisites and adds checkpoints`() {
        val chapters = listOf(
            StudyPlanner.ChapterInfo(0, "A", 1_200, 4),
            StudyPlanner.ChapterInfo(1, "B (temel)", 2_400, 6, prerequisiteOf = setOf(0)),
            StudyPlanner.ChapterInfo(2, "C", 6_000, 8),
        )
        val start = LocalDate.of(2026, 5, 1)
        val plan = StudyPlanner.build(chapters, 20, start)
        plan.groupBy { it.day }.forEach { (day, tasks) -> assertTrue(tasks.sumOf { it.minutes } <= 20, "$day over budget") }
        val learnOrder = plan.filter { it.type == StudyPlanner.TaskType.LEARN_CHAPTER }.map { it.chapterIndex }.distinct()
        assertEquals(listOf(1, 0, 2), learnOrder)
        assertTrue(plan.any { it.type == StudyPlanner.TaskType.CHECKPOINT })
        assertTrue(plan.any { it.title.contains("(1/") }, "long chapter split over days")
        assertEquals(start, plan.first().day)
    }

    @Test fun `missed days are rescheduled forward without overloading a day`() {
        val start = LocalDate.of(2026, 5, 1)
        val plan = StudyPlanner.build(listOf(StudyPlanner.ChapterInfo(0, "A", 2_400, 4), StudyPlanner.ChapterInfo(1, "B", 2_400, 4)), 15, start)
        val today = start.plusDays(3)
        val moved = StudyPlanner.reschedule(plan, today, 15)
        assertEquals(plan.size, moved.size)
        assertTrue(moved.filter { !it.done }.all { it.day >= today })
        moved.filter { !it.done }.groupBy { it.day }.forEach { (_, ts) -> assertTrue(ts.sumOf { it.minutes } <= 15) }
        val adapted = StudyPlanner.adapt(moved, mapOf(0 to 0.4, 1 to 0.9), today)
        assertEquals(moved.size + 1, adapted.size)
    }

    @Test fun `reminders honour local time, selected days and pause mode`() {
        val prefs = ReminderScheduler.Preferences(true, LocalTime.of(9, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        val sundayNight = ZonedDateTime.of(LocalDateTime.of(2026, 3, 8, 22, 0), istanbul) // Sunday
        assertEquals(ZonedDateTime.of(LocalDateTime.of(2026, 3, 9, 9, 0), istanbul), ReminderScheduler.next(prefs, sundayNight))
        val mondayAfter = ZonedDateTime.of(LocalDateTime.of(2026, 3, 9, 9, 1), istanbul)
        assertEquals(DayOfWeek.WEDNESDAY, ReminderScheduler.next(prefs, mondayAfter)!!.dayOfWeek)
        val paused = prefs.copy(pausedUntil = LocalDate.of(2026, 3, 12))
        assertEquals(LocalDate.of(2026, 3, 16), ReminderScheduler.next(paused, sundayNight)!!.toLocalDate())
        assertNull(ReminderScheduler.next(prefs.copy(enabled = false), sundayNight))
        val twice = prefs.copy(intensity = 2)
        assertEquals(LocalTime.of(20, 30), ReminderScheduler.next(twice, mondayAfter)!!.toLocalTime())
    }

    @Test fun `reminders handle DST gaps and time zone changes`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val prefs = ReminderScheduler.Preferences(true, LocalTime.of(2, 30), DayOfWeek.entries.toSet())
        // 29 March 2026: clocks jump 02:00 → 03:00 in Berlin; 02:30 does not exist.
        val before = ZonedDateTime.of(LocalDateTime.of(2026, 3, 28, 23, 0), berlin)
        assertEquals(LocalTime.of(3, 30), ReminderScheduler.next(prefs, before)!!.toLocalTime())
        // Same instant, different zone: the reminder follows the user's local clock.
        val nineAm = prefs.copy(time = LocalTime.of(9, 0))
        val nowMillis = before.toInstant().toEpochMilli()
        val inTokyo = ReminderScheduler.delayMillis(nineAm, nowMillis, ZoneId.of("Asia/Tokyo"))!!
        val inBerlin = ReminderScheduler.delayMillis(nineAm, nowMillis, berlin)!!
        assertTrue(inTokyo != inBerlin)
        assertTrue(inTokyo > 0 && inBerlin > 0)
    }

    @Test fun `analytics separate recognition from recall and need enough data`() {
        fun ev(level: RecallLevel, correct: Boolean, days: Double = 0.0, i: Int = 0) =
            LearningAnalytics.ReviewEvent("i$i", i.toLong(), correct, 3, days, false, level, firstAttempt = i == 0)
        val events = List(12) { ev(RecallLevel.RECOGNITION, true, i = it) } + List(4) { ev(RecallLevel.RECALL, it == 0, 2.0, it) }
        val r = LearningAnalytics.report(events)
        assertTrue(r.byLevel.getValue(RecallLevel.RECOGNITION).enoughData)
        assertFalse(r.byLevel.getValue(RecallLevel.RECALL).enoughData)
        assertEquals(RecallLevel.RECALL, LearningAnalytics.recallGapSuggestion(r))
        assertEquals(0.25, r.delayedRecall.value)
        val ci = r.overall.interval()!!
        assertTrue(ci.start < r.overall.value!! && r.overall.value!! < ci.endInclusive)
        assertTrue(LearningAnalytics.isRetained(listOf(ev(RecallLevel.RECALL, true, 25.0))))
        assertFalse(LearningAnalytics.isRetained(listOf(ev(RecallLevel.RECOGNITION, true, 25.0))))
        assertTrue(LearningAnalytics.isWeak(listOf(ev(RecallLevel.RECALL, false), ev(RecallLevel.RECALL, false, i = 1), ev(RecallLevel.RECALL, true, i = 2))))
    }
}
