package com.bookmind.app.data.repo

import androidx.room.withTransaction
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.DueItem
import com.bookmind.app.data.db.LearningItemEntity
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.db.ReviewLogEntity
import com.bookmind.app.data.db.ReviewStateEntity
import com.bookmind.app.data.db.SessionType
import com.bookmind.app.data.db.StudySessionEntity
import com.bookmind.app.data.db.XpEventEntity
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.data.settings.SrsAlgorithm
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.app.util.newId
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.RecallLevel
import com.bookmind.core.learning.Gamification
import com.bookmind.core.srs.CardPhase
import com.bookmind.core.srs.FsrsScheduler
import com.bookmind.core.srs.MemoryState
import com.bookmind.core.srs.Rating
import com.bookmind.core.srs.RatingResolver
import com.bookmind.core.srs.Sm2Scheduler
import com.bookmind.core.srs.SpacedRepetitionScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

fun DueItem.memoryState() = MemoryState(
    phase = runCatching { CardPhase.valueOf(phase) }.getOrDefault(CardPhase.NEW),
    stability = stability,
    difficulty = difficulty,
    lastReviewAt = lastReviewAt,
    nextReviewAt = nextReviewAt,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    lastRating = lastRating?.let { Rating.fromGrade(it) },
    easeFactor = easeFactor,
    algorithmVersion = algorithmVersion,
)

fun DueItem.question(): QuestionDraft? = questionJson?.let { runCatching { AppJson.decodeFromString(QuestionDraft.serializer(), it) }.getOrNull() }

/** Tracks study sessions so the daily goal measures actual study time. */
@Singleton
class SessionTracker @Inject constructor(private val db: BookMindDatabase, private val users: UserRepository, private val clock: AppClock) {
    suspend fun start(type: SessionType, bookId: String?, metadata: String? = null): String {
        val s = StudySessionEntity(newId(), users.userId(), bookId, type, clock.now(), null, metadata)
        db.sessions().insert(s)
        return s.id
    }

    private val detached = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Finishes a session even if the calling screen's scope is already being cancelled. */
    fun finishDetached(id: String) { detached.launch { finish(id) } }

    suspend fun finish(id: String) {
        val s = db.sessions().get(id) ?: return
        if (s.completedAt != null) return
        db.sessions().upsert(s.copy(completedAt = clock.now()))
    }
}

/** Spaced repetition: due queue, grading, scheduling and review history. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class ReviewRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    suspend fun scheduler(): SpacedRepetitionScheduler {
        val s = settings.current()
        return when (s.algorithm) {
            SrsAlgorithm.FSRS -> FsrsScheduler(desiredRetention = s.desiredRetention.toDouble())
            SrsAlgorithm.SM2 -> Sm2Scheduler()
        }
    }

    fun dueCount(): Flow<Int> = flow { emit(users.userId()) }.flatMapLatest { db.learning().observeDueCount(it, clock.endOfToday()) }

    /** Today's queue: everything due by the end of the local day, then a limited number of new items. */
    suspend fun todayQueue(bookId: String? = null): List<DueItem> {
        val userId = users.userId()
        val due = db.learning().due(userId, clock.endOfToday(), 500)
        val newLimit = (settings.current().newItemsPerDay - introducedToday(userId)).coerceAtLeast(0)
        val fresh = if (newLimit > 0) db.learning().newItems(userId, newLimit) else emptyList()
        return (due + fresh).filter { bookId == null || it.bookId == bookId }
    }

    private suspend fun introducedToday(userId: String): Int =
        db.learning().logsSince(userId, clock.startOfToday()).filter { it.elapsedDays == null }.map { it.learningItemId }.distinct().size

    suspend fun overdueCount(): Int = db.learning().dueCount(users.userId(), clock.startOfToday())
    suspend fun newCount(): Int = db.learning().newCount(users.userId())

    suspend fun preview(item: DueItem): Map<Rating, Long> = scheduler().preview(item.memoryState(), clock.now())

    data class Outcome(val rating: Rating, val intervalMs: Long, val xp: Int)

    /**
     * Records one review. [correct]/[score] are measured from the answer; [selfRating] is what the user said.
     * Objective evidence wins (see RatingResolver).
     */
    suspend fun submit(
        item: DueItem,
        selfRating: Rating?,
        correct: Boolean? = null,
        score: Double? = null,
        response: String? = null,
        responseTimeMs: Long? = null,
        hintUsed: Boolean = false,
        level: RecallLevel = RecallLevel.RECALL,
        source: String = "review",
    ): Outcome {
        val now = clock.now()
        val rating = RatingResolver.resolve(correct, selfRating, responseTimeMs, hintUsed, score)
        val state = item.memoryState()
        val result = scheduler().schedule(state, rating, now)
        val userId = users.userId()
        val elapsedDays = state.lastReviewAt?.let { (now - it) / 86_400_000.0 }
        val xp = Gamification.xp(Gamification.XpEvent(Gamification.Kind.REVIEW, rating != Rating.AGAIN, level, elapsedDays ?: 0.0, hintUsed))
        val existing = db.learning().state(item.itemId)
        db.withTransaction {
            val s = result.state
            db.learning().upsertState(
                ReviewStateEntity(
                    id = existing?.id ?: newId(), userId = userId, learningItemId = item.itemId, phase = s.phase.name,
                    stability = s.stability, difficulty = s.difficulty, easeFactor = s.easeFactor, lastReviewAt = s.lastReviewAt,
                    nextReviewAt = s.nextReviewAt, reviewCount = s.reviewCount, lapseCount = s.lapseCount, lastRating = s.lastRating?.grade,
                    algorithmVersion = s.algorithmVersion, updatedAt = now,
                ),
            )
            val logId = newId()
            db.learning().insertLog(
                ReviewLogEntity(
                    id = logId, userId = userId, learningItemId = item.itemId, reviewedAt = now, response = response?.take(4_000),
                    correctness = score ?: correct?.let { if (it) 1.0 else 0.0 }, responseTimeMs = responseTimeMs,
                    confidenceRating = selfRating?.grade, rating = rating.grade, scheduledIntervalMs = result.scheduledIntervalMs,
                    elapsedDays = elapsedDays, hintUsed = hintUsed, recallLevel = level.name, retrievabilityBefore = result.retrievabilityBefore,
                    source = source, algorithmMetadata = """{"algorithm":"${s.algorithmVersion}","stability":${s.stability},"difficulty":${s.difficulty}}""",
                ),
            )
            db.xp().insert(XpEventEntity(newId(), userId, Gamification.Kind.REVIEW.name, xp, now, item.itemId))
        }
        return Outcome(rating, result.scheduledIntervalMs, xp)
    }

    /** Adds an item to the review queue (idempotent by [dedupeKey]); optionally due immediately. */
    suspend fun ensureItem(
        bookId: String,
        type: LearningItemType,
        dedupeKey: String,
        prompt: String,
        expected: String,
        explanation: String?,
        sourceRefs: String,
        question: QuestionDraft? = null,
        conceptId: String? = null,
        dueNow: Boolean = false,
    ): String {
        val userId = users.userId()
        val now = clock.now()
        db.learning().byKey(userId, dedupeKey)?.let { existing ->
            if (dueNow) db.learning().state(existing.id)?.let { st ->
                if (st.nextReviewAt == null || st.nextReviewAt > now) db.learning().upsertState(st.copy(nextReviewAt = now, updatedAt = now))
            }
            return existing.id
        }
        val item = LearningItemEntity(
            newId(), userId, bookId, conceptId, type, prompt.take(2_000), expected.take(4_000), explanation, sourceRefs,
            question?.difficulty ?: 3, question?.let { AppJson.encodeToString(QuestionDraft.serializer(), it) }, dedupeKey, createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.learning().insertItem(item)
            db.learning().insertState(
                ReviewStateEntity(newId(), userId, item.id, CardPhase.NEW.name, 0.0, 0.0, 2.5, nextReviewAt = if (dueNow) now else null,
                    reviewCount = 0, lapseCount = 0, algorithmVersion = "", updatedAt = now),
            )
        }
        return item.id
    }

    suspend fun suspend(itemId: String, suspended: Boolean) {
        db.learning().setSuspended(itemId, suspended, clock.now())
    }

    suspend fun itemsForBook(bookId: String): List<DueItem> = db.learning().forBook(users.userId(), bookId)

    suspend fun conceptItem(bookId: String, normalizedTitle: String): DueItem? =
        db.learning().byKey(users.userId(), "concept:$bookId:$normalizedTitle")?.let { item -> itemsForBook(bookId).firstOrNull { it.itemId == item.id } }
}
