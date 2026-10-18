package com.bookmind.app.notifications

import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.NotificationPreferenceEntity
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.util.AppClock
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.learning.ReminderScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

enum class ReminderType { DUE_REVIEWS, OVERDUE, UPCOMING, DAILY_GOAL, CONTINUE_BOOK }

data class ReminderPrefs(
    val enabled: Boolean,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val types: Set<ReminderType>,
    val intensity: Int,
    val pausedUntil: LocalDate?,
    val showContent: Boolean,
) {
    fun toScheduler() = ReminderScheduler.Preferences(enabled, time, days, pausedUntil, intensity)
}

fun NotificationPreferenceEntity.toPrefs() = ReminderPrefs(
    enabled = enabled,
    time = runCatching { LocalTime.parse(localTime) }.getOrDefault(LocalTime.of(19, 0)),
    days = DayOfWeek.entries.filter { selectedDays and (1 shl (it.value - 1)) != 0 }.toSet(),
    types = notificationTypes.split(',').mapNotNull { runCatching { ReminderType.valueOf(it.trim()) }.getOrNull() }.toSet(),
    intensity = intensity,
    pausedUntil = pausedUntilEpochDay?.let(LocalDate::ofEpochDay),
    showContent = showContent,
)

@Singleton
class ReminderManager @Inject constructor(
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val scheduler: WorkScheduler,
    private val clock: AppClock,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val prefs: Flow<ReminderPrefs?> = flow { emit(users.userId()) }.flatMapLatest { id ->
        db.notificationPrefs().observe(id).map { it?.toPrefs() }
    }

    suspend fun current(): ReminderPrefs? = db.notificationPrefs().get(users.userId())?.toPrefs()

    suspend fun save(p: ReminderPrefs) {
        val userId = users.userId()
        val existing = db.notificationPrefs().get(userId)
        val entity = NotificationPreferenceEntity(
            id = existing?.id ?: com.bookmind.app.util.newId(),
            userId = userId,
            enabled = p.enabled,
            localTime = "%02d:%02d".format(p.time.hour, p.time.minute),
            selectedDays = p.days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) },
            notificationTypes = p.types.joinToString(",") { it.name },
            intensity = p.intensity.coerceIn(1, 2),
            pausedUntilEpochDay = p.pausedUntil?.toEpochDay(),
            showContent = p.showContent,
            updatedAt = clock.now(),
        )
        db.notificationPrefs().upsert(entity)
        reschedule()
    }

    /** Recomputes the next reminder in the current local time zone (called on start, on save, on TZ change). */
    suspend fun reschedule() {
        val p = current()
        if (p == null || !p.enabled) { scheduler.cancelReminders(); return }
        val delay = ReminderScheduler.delayMillis(p.toScheduler(), clock.now(), clock.zone())
        if (delay == null) scheduler.cancelReminders() else scheduler.scheduleReminder(delay)
    }
}
