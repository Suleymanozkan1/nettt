package com.bookmind.app.data.repo

import android.content.Context
import androidx.core.content.edit
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.LearningItemType
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.util.toJson
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.study.Lesson
import com.bookmind.core.ai.study.Mistake
import com.bookmind.core.ai.study.StudyAi
import com.bookmind.core.text.Checksums
import com.bookmind.core.text.SourceRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The study modules of the "Next lesson" hub; a lesson is one chapter. */
enum class StudyModule(val exam: Boolean = false) {
    NOTES, PODCAST, FLASHCARDS, GAPS,
    EXERCISES, MCQ, TRUE_FALSE,
    WRITTEN_EXAM(exam = true), ORAL_EXAM(exam = true),
}

/**
 * Lessons, progress and the link to spaced repetition for the study hub. Content comes from [StudyAi]: the
 * downloaded on-device model when there is one, otherwise the classic algorithms; nothing goes to a paid service.
 */
@Singleton
class StudyHubRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val db: BookMindDatabase,
    private val study: StudyRepository,
    private val reviews: ReviewRepository,
    private val learningAi: LearningAi,
) {
    val ai = StudyAi(learningAi)
    private val prefs = context.getSharedPreferences("study_hub", Context.MODE_PRIVATE)

    data class LessonInfo(val index: Int, val title: String, val done: Set<StudyModule>) {
        /** A lesson is complete once the learner has checked themselves: the test or one of the exams. */
        val completed get() = StudyModule.MCQ in done || done.any { it.exam }
    }

    private val _version = MutableStateFlow(0)
    /** Bumped whenever progress changes, so screens can reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun lessons(bookId: String): List<LessonInfo> {
        val done = prefs.getStringSet(bookId, emptySet()).orEmpty()
        return db.chapters().forBook(bookId).map { ch ->
            LessonInfo(ch.orderIndex, ch.title, StudyModule.entries.filter { "${ch.orderIndex}:${it.name}" in done }.toSet())
        }
    }

    /** The first lesson the learner has not completed yet (or the last one when all are done). */
    fun nextLesson(lessons: List<LessonInfo>): LessonInfo? = lessons.firstOrNull { !it.completed } ?: lessons.lastOrNull()

    fun markDone(bookId: String, lesson: Int, module: StudyModule) {
        val set = prefs.getStringSet(bookId, emptySet()).orEmpty() + "$lesson:${module.name}"
        prefs.edit { putStringSet(bookId, set) }
        _version.value++
    }

    suspend fun lesson(bookId: String, index: Int): Lesson {
        val chapters = study.chapterInputs(bookId)
        val ch = chapters.firstOrNull { it.index == index } ?: chapters.first()
        val concepts = study.conceptInputs(bookId)
        return Lesson(ch.index, ch.title, ch.text, concepts.filter { it.chapterIndex == ch.index }, concepts)
    }

    /**
     * Questions the learner got wrong or keeps forgetting for this lesson: wrong quiz/practice answers and review
     * cards rated "again". Items without a chapter count when they mention one of the lesson's concepts.
     */
    suspend fun mistakes(bookId: String, lesson: Lesson): List<Mistake> =
        reviews.itemsForBook(bookId).filter { it.itemType == LearningItemType.WRONG_ANSWER || it.lastRating == 1 || it.lapseCount > 0 }
            .filter { item ->
                val chapter = item.question()?.chapterIndex
                chapter == lesson.index || (chapter == null && lesson.concepts.any { learningAi.local.containsTerm(item.prompt, it.title) })
            }
            .map { Mistake(it.prompt, it.expectedAnswer) }
            .distinctBy { it.question }

    /**
     * Puts something the learner missed (a flashcard marked "again", a wrong test or true/false answer, a weak exam
     * answer) into the existing review queue, due right away.
     */
    suspend fun addToReview(bookId: String, lesson: Lesson, prompt: String, expected: String, explanation: String?, question: QuestionDraft? = null) {
        reviews.ensureItem(
            bookId = bookId, type = LearningItemType.WRONG_ANSWER,
            dedupeKey = "study:$bookId:${Checksums.sha256(prompt).take(16)}",
            prompt = prompt, expected = expected, explanation = explanation,
            sourceRefs = listOf(SourceRef(lesson.index, lesson.title)).toJson(),
            question = question?.copy(chapterIndex = lesson.index), dueNow = true,
        )
    }

    val lang: String get() = AppLanguage.tag
}
