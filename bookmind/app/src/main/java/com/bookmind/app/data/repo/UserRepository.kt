package com.bookmind.app.data.repo

import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.NotificationPreferenceEntity
import com.bookmind.app.data.db.UserEntity
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The single local profile. Created on first launch; linked to a cloud account after sign-in. */
@Singleton
class UserRepository @Inject constructor(private val db: BookMindDatabase, private val clock: AppClock) {
    private val mutex = Mutex()
    @Volatile private var cached: String? = null

    val user: Flow<UserEntity?> = db.users().observe()

    suspend fun userId(): String = cached ?: mutex.withLock {
        cached ?: (db.users().current() ?: createDefault()).id.also { cached = it }
    }

    private suspend fun createDefault(): UserEntity {
        val now = clock.now()
        val user = UserEntity(
            id = newId(),
            language = if (Locale.getDefault().language == "tr") "tr" else "en",
            timezone = clock.zone().id,
            dailyGoalMinutes = 15,
            createdAt = now,
            updatedAt = now,
        )
        db.users().upsert(user)
        db.notificationPrefs().upsert(
            NotificationPreferenceEntity(newId(), user.id, enabled = false, localTime = "19:00", selectedDays = 0b1111111,
                notificationTypes = "DUE_REVIEWS,OVERDUE,DAILY_GOAL,CONTINUE_BOOK", intensity = 1, updatedAt = now),
        )
        return user
    }

    suspend fun update(transform: (UserEntity) -> UserEntity) {
        val current = db.users().current() ?: createDefault()
        val next = transform(current).copy(updatedAt = clock.now())
        db.users().upsert(next)
    }

    /** Keeps the stored time zone in step with the device so reminders follow the user when travelling. */
    suspend fun refreshTimezone() {
        val zone = clock.zone().id
        if (db.users().current()?.timezone != zone) update { it.copy(timezone = zone) }
    }
}
