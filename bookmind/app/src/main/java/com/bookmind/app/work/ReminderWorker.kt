package com.bookmind.app.work

import com.bookmind.app.data.settings.AppLanguage
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bookmind.app.R
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.notifications.Notifier
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.notifications.ReminderType
import com.bookmind.app.util.AppClock
import com.bookmind.core.learning.DailyGoal
import com.bookmind.core.learning.ReminderScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Duration
import java.time.LocalTime

/**
 * Fires at the user's chosen local time, decides what (if anything) is worth saying, posts the notification
 * and schedules the next run. Android may delay it (Doze, battery saver); we never promise an exact minute.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val reminders: ReminderManager,
    private val clock: AppClock,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val prefs = reminders.current() ?: return Result.success()
            val today = clock.today()
            val active = prefs.enabled && today.dayOfWeek in prefs.days && (prefs.pausedUntil == null || today.isAfter(prefs.pausedUntil))
            if (active) {
                val userId = users.userId()
                val due = db.learning().dueCount(userId, clock.endOfToday())
                val overdue = db.learning().dueCount(userId, clock.startOfToday())
                val user = db.users().current()
                val sessions = db.sessions().since(userId, clock.startOfToday()).map { DailyGoal.Session(it.startedAt, it.completedAt) }
                val goal = DailyGoal.progress(user?.dailyGoalMinutes ?: 15, clock.now(), sessions, clock.zone())
                val now = LocalTime.now(clock.zone())
                val isEveningNudge = prefs.intensity >= 2 && Duration.between(prefs.toScheduler().eveningNudgeTime, now).abs().toMinutes() < 45 &&
                    Duration.between(prefs.time, now).abs().toMinutes() > 45
                val shouldShow = if (isEveningNudge) ReminderScheduler.shouldShowEveningNudge(goal.reached, due) else (due > 0 || !goal.reached)
                if (shouldShow) {
                    val ctx = AppLanguage.localized(applicationContext)
                    val lines = buildList {
                        if (ReminderType.DUE_REVIEWS in prefs.types && due > 0) add(ctx.getString(R.string.reminder_due, due))
                        if (ReminderType.OVERDUE in prefs.types && overdue > 0) add(ctx.getString(R.string.reminder_overdue, overdue))
                        if (ReminderType.DAILY_GOAL in prefs.types && !goal.reached) add(ctx.getString(R.string.reminder_goal, goal.remainingMinutes))
                        if (ReminderType.CONTINUE_BOOK in prefs.types && prefs.showContent) {
                            db.books().all(userId).filter { it.completedAt == null }.maxByOrNull { it.lastOpenedAt ?: 0 }?.let { add(ctx.getString(R.string.reminder_continue, it.title)) }
                        }
                    }
                    if (lines.isNotEmpty()) {
                        Notifier.showReminder(ctx, ctx.getString(R.string.reminder_title), lines.joinToString("\n"), if (due > 0) "review" else "home")
                    }
                }
            }
        } finally {
            reminders.reschedule()
        }
        return Result.success()
    }
}
