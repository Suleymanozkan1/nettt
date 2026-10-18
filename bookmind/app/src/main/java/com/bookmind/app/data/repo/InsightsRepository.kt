package com.bookmind.app.data.repo

import com.bookmind.app.data.db.BookEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.BookRecall
import com.bookmind.app.data.db.ConceptEntity
import com.bookmind.app.util.AppClock
import com.bookmind.core.ai.RecallLevel
import com.bookmind.core.learning.DailyGoal
import com.bookmind.core.learning.Gamification
import com.bookmind.core.learning.LearningAnalytics
import com.bookmind.core.learning.Streak
import com.bookmind.core.srs.FsrsScheduler
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Read models for the home and analytics screens. Measured, predicted and self-reported values stay separate. */
@Singleton
class InsightsRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val clock: AppClock,
) {
    data class Home(
        val goal: DailyGoal.Progress,
        val dueToday: Int,
        val overdue: Int,
        val newAvailable: Int,
        val suggestedMinutes: Int,
        val continueBooks: List<BookEntity>,
        val recentConcepts: List<ConceptEntity>,
        val weakConcepts: List<String>,
        val weekMinutes: List<Pair<LocalDate, Int>>,
        val streak: Streak.Result,
        val xp: Int,
        val level: Int,
    )

    suspend fun home(): Home {
        val userId = users.userId()
        val zone = clock.zone()
        val user = db.users().current()
        val weekStart = clock.today().minusDays(6)
        val sessions = db.sessions().since(userId, weekStart.atStartOfDay(zone).toInstant().toEpochMilli()).map { DailyGoal.Session(it.startedAt, it.completedAt) }
        val goal = DailyGoal.progress(user?.dailyGoalMinutes ?: 15, clock.now(), sessions, zone)
        val due = db.learning().dueCount(userId, clock.endOfToday())
        val books = db.books().all(userId).filter { it.completedAt == null }.sortedByDescending { it.lastOpenedAt ?: it.createdAt }.take(3)
        val concepts = db.concepts().allForUser(userId).sortedByDescending { it.createdAt }.take(5)
        val logs = db.learning().logsSince(userId, clock.now() - 30 * 86_400_000L)
        return Home(
            goal = goal,
            dueToday = due,
            overdue = db.learning().dueCount(userId, clock.startOfToday()),
            newAvailable = db.learning().newCount(userId),
            suggestedMinutes = DailyGoal.suggestedSessionMinutes(goal, due),
            continueBooks = books,
            recentConcepts = concepts,
            weakConcepts = weakItems(userId, logs).take(5),
            weekMinutes = (0..6).map { d -> weekStart.plusDays(d.toLong()).let { it to DailyGoal.minutesOn(it, sessions, zone) } },
            streak = streak(userId),
            xp = db.xp().totalSince(userId, 0),
            level = Gamification.level(db.xp().totalSince(userId, 0)),
        )
    }

    private suspend fun weakItems(userId: String, logs: List<com.bookmind.app.data.db.ReviewLogEntity>): List<String> {
        val byItem = logs.groupBy { it.learningItemId }
        val items = db.learning().allItems(userId).associateBy { it.id }
        return byItem.filter { (_, l) -> LearningAnalytics.isWeak(l.map(::event)) }.keys
            .mapNotNull { items[it] }.filter { !it.suspended }
            .map { it.prompt.substringAfter('“').substringBefore('”') }.distinct()
    }

    private fun event(l: com.bookmind.app.data.db.ReviewLogEntity) = LearningAnalytics.ReviewEvent(
        itemId = l.learningItemId, at = l.reviewedAt, correct = l.rating > 1, selfRating = l.confidenceRating, elapsedDaysSincePrevious = l.elapsedDays,
        hintUsed = l.hintUsed, level = runCatching { RecallLevel.valueOf(l.recallLevel) }.getOrDefault(RecallLevel.RECALL), firstAttempt = l.elapsedDays == null,
    )

    private suspend fun streak(userId: String): Streak.Result {
        val zone = clock.zone()
        val since = clock.now() - 400 * 86_400_000L
        val days = db.learning().logsSince(userId, since).map { Instant.ofEpochMilli(it.reviewedAt).atZone(zone).toLocalDate() }.toMutableSet()
        db.sessions().since(userId, since).filter { it.completedAt != null }.forEach { days += Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        return Streak.compute(days, clock.today())
    }

    data class Analytics(
        val report: LearningAnalytics.Report,
        val forecast: List<Pair<LocalDate, Int>>,
        val dueToday: Int,
        val overdue: Int,
        /** Model prediction: average recall probability of all studied items right now. */
        val predictedRetention: Double?,
        val studiedItems: Int,
        val retainedConcepts: Int,
        val weakConcepts: List<String>,
        val recallByBook: List<BookRecall>,
        val minutesByDay: List<Pair<LocalDate, Int>>,
        val goalDaysReached: Int,
        val goalDays: Int,
        val suggestion: RecallLevel?,
        val badges: List<Gamification.Badge>,
        val xp: Int,
        val level: Int,
        val streak: Streak.Result,
        val totalStudyMinutes: Int,
    )

    suspend fun analytics(): Analytics {
        val userId = users.userId()
        val zone = clock.zone()
        val now = clock.now()
        val logs = db.learning().logsSince(userId, 0)
        val events = logs.map(::event)
        val today = clock.today()
        val upcoming = db.learning().upcoming(userId, clock.startOfToday(), today.plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli())
        val forecast = (0..13).map { d ->
            val day = today.plusDays(d.toLong())
            day to upcoming.count { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == day }
        }
        val fsrs = FsrsScheduler()
        val states = db.learning().allStates(userId).filter { it.lastReviewAt != null }
        val predicted = states.mapNotNull { s ->
            fsrs.retrievability(com.bookmind.core.srs.MemoryState(com.bookmind.core.srs.CardPhase.REVIEW, s.stability, s.difficulty, s.lastReviewAt, s.nextReviewAt, s.reviewCount), now)
        }
        val sessions = db.sessions().since(userId, today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()).map { DailyGoal.Session(it.startedAt, it.completedAt) }
        val minutes = (0..29).map { d -> today.minusDays((29 - d).toLong()).let { it to DailyGoal.minutesOn(it, sessions, zone) } }
        val goalMinutes = db.users().current()?.dailyGoalMinutes ?: 15
        val activeDays = minutes.takeLast(14)
        val retained = events.groupBy { it.itemId }.count { (_, e) -> LearningAnalytics.isRetained(e) }
        val report = LearningAnalytics.report(events)
        val xp = db.xp().totalSince(userId, 0)
        val streak = streak(userId)
        val stats = Gamification.Stats(
            reviews = logs.size, longestStreak = streak.longest,
            booksCompleted = db.books().all(userId).count { it.completedAt != null }, conceptsRetained30d = events.groupBy { it.itemId }.count { (_, e) -> LearningAnalytics.isRetained(e, 30.0) },
            explanationsCorrect = events.count { it.level == RecallLevel.EXPLANATION && it.correct }, realLifeTasks = db.xp().count(userId, Gamification.Kind.REAL_LIFE_TASK.name),
            notes = db.notes().all(userId).size,
        )
        return Analytics(
            report = report, forecast = forecast, dueToday = db.learning().dueCount(userId, clock.endOfToday()), overdue = db.learning().dueCount(userId, clock.startOfToday()),
            predictedRetention = predicted.takeIf { it.isNotEmpty() }?.average(), studiedItems = states.size, retainedConcepts = retained,
            weakConcepts = weakItems(userId, logs.filter { it.reviewedAt > now - 30 * 86_400_000L }), recallByBook = db.learning().recallByBook(userId),
            minutesByDay = minutes, goalDaysReached = activeDays.count { it.second >= goalMinutes }, goalDays = activeDays.size,
            suggestion = LearningAnalytics.recallGapSuggestion(report), badges = Gamification.earnedBadges(stats), xp = xp, level = Gamification.level(xp),
            streak = streak, totalStudyMinutes = minutes.sumOf { it.second },
        )
    }

    /** Points on the predicted forgetting curve of one item (for the curve chart). Labelled as a model estimate in the UI. */
    fun curve(stability: Double, days: Int = 30): List<Pair<Int, Double>> =
        (0..days).map { it to FsrsScheduler.forgettingCurve(it.toDouble(), stability.coerceAtLeast(0.1)) }

    suspend fun averageStability(): Double? = db.learning().allStates(users.userId()).filter { it.lastReviewAt != null }.map { it.stability }.takeIf { it.isNotEmpty() }?.average()

}
