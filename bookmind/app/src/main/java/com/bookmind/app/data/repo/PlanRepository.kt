package com.bookmind.app.data.repo

import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.PlanTaskEntity
import com.bookmind.app.data.db.StudyPlanEntity
import com.bookmind.app.data.db.XpEventEntity
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.newId
import com.bookmind.core.learning.Gamification
import com.bookmind.core.learning.StudyPlanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class PlanRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val users: UserRepository,
    private val clock: AppClock,
) {
    fun plan(bookId: String): Flow<StudyPlanEntity?> = db.plans().observeForBook(bookId)
    fun tasks(planId: String): Flow<List<PlanTaskEntity>> = db.plans().observeTasks(planId)
    fun today(): Flow<List<PlanTaskEntity>> = flow { emit(users.userId()) }.flatMapLatest { db.plans().observeToday(it, clock.today().toEpochDay()) }

    suspend fun create(bookId: String, dailyMinutes: Int) {
        val userId = users.userId()
        val chapters = db.chapters().forBook(bookId)
        val concepts = db.concepts().forBook(bookId)
        val prerequisiteChapters = concepts.filter { it.isPrerequisite }.map { it.chapterId }.toSet()
        val infos = chapters.map { ch ->
            StudyPlanner.ChapterInfo(
                index = ch.orderIndex, title = ch.title, words = ch.wordCount,
                conceptCount = concepts.count { it.chapterId == ch.id },
                // Chapters that hold prerequisite concepts are learned before the chapters that follow them.
                prerequisiteOf = if (ch.id in prerequisiteChapters) chapters.filter { it.orderIndex > ch.orderIndex }.map { it.orderIndex }.toSet() else emptySet(),
            )
        }
        val now = clock.now()
        val existing = db.plans().forBook(bookId)
        val plan = StudyPlanEntity(existing?.id ?: newId(), userId, bookId, dailyMinutes, clock.today().toEpochDay(), existing?.createdAt ?: now, now)
        db.plans().upsertPlan(plan)
        db.plans().replaceTasks(plan.id, StudyPlanner.build(infos, dailyMinutes, clock.today(), lang = AppLanguage.tag).map { it.toEntity(plan.id) })
    }

    /** Called when the plan is shown: moves missed tasks forward and adds reviews for weak chapters. */
    suspend fun refresh(bookId: String) {
        val plan = db.plans().forBook(bookId) ?: return
        val tasks = db.plans().tasks(plan.id)
        val domain = tasks.map { StudyPlanner.PlanTask(LocalDate.ofEpochDay(it.epochDay), StudyPlanner.TaskType.valueOf(it.type), it.chapterIndex, it.minutes, it.title, it.done) }
        val today = clock.today()
        var updated = StudyPlanner.reschedule(domain, today, plan.dailyMinutes)
        updated = StudyPlanner.adapt(updated, chapterAccuracy(bookId), today, AppLanguage.tag)
        if (updated != domain) {
            val doneIds = tasks.filter { it.done }.associateBy { Triple(it.epochDay, it.title, it.type) }
            db.plans().replaceTasks(plan.id, updated.map { t ->
                t.toEntity(plan.id).let { e -> doneIds[Triple(e.epochDay, e.title, e.type)]?.let { e.copy(id = it.id, completedAt = it.completedAt) } ?: e }
            })
        }
    }

    private suspend fun chapterAccuracy(bookId: String): Map<Int, Double> {
        val userId = users.userId()
        val chapters = db.chapters().forBook(bookId)
        val conceptChapter = db.concepts().forBook(bookId).associate { it.id to it.chapterId }
        val items = db.learning().allItems(userId).filter { it.bookId == bookId }.associateBy { it.id }
        val logs = db.learning().logsSince(userId, clock.now() - 14 * 86_400_000L).filter { it.learningItemId in items }
        return logs.groupBy { l -> items[l.learningItemId]?.conceptId?.let(conceptChapter::get) }
            .filterKeys { it != null }
            .mapNotNull { (chapterId, l) -> chapters.firstOrNull { it.id == chapterId }?.let { it.orderIndex to l.count { x -> x.rating > 1 }.toDouble() / l.size } }
            .toMap()
    }

    suspend fun setDone(task: PlanTaskEntity, done: Boolean) {
        db.plans().setDone(task.id, done, if (done) clock.now() else null)
        if (done && task.type == StudyPlanner.TaskType.PRACTICE.name) {
            db.xp().insert(XpEventEntity(newId(), users.userId(), Gamification.Kind.REAL_LIFE_TASK.name, Gamification.xp(Gamification.XpEvent(Gamification.Kind.REAL_LIFE_TASK)), clock.now(), task.id))
        }
    }

    private fun StudyPlanner.PlanTask.toEntity(planId: String) =
        PlanTaskEntity(newId(), planId, day.toEpochDay(), type.name, chapterIndex, minutes, title, done)
}
