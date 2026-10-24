package com.bookmind.app.ui.navigation

import android.net.Uri

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val LIBRARY = "library"
    const val ADD_BOOK = "add_book"
    const val BOOK = "book/{bookId}"
    const val CHAPTER = "chapter/{chapterId}"
    const val SUMMARY = "summary/{bookId}/{type}?chapterId={chapterId}"
    const val CONCEPT = "concept/{conceptId}"
    const val TEACH = "teach/{bookId}/{conceptId}"
    const val QUIZ_SETUP = "quiz_setup/{bookId}"
    const val QUIZ = "quiz/{quizId}"
    const val QUIZ_RESULT = "quiz_result/{attemptId}"
    const val REVIEW = "review?bookId={bookId}"
    const val ANALYTICS = "analytics"
    const val NOTES = "notes?bookId={bookId}"
    const val NOTE_EDIT = "note_edit?bookId={bookId}&noteId={noteId}&chapterId={chapterId}&highlight={highlight}"
    const val CONNECTIONS = "connections"
    const val PLAN = "plan/{bookId}"
    const val ASK = "ask/{bookId}"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val AI = "ai"
    const val BOOK_SUMMARY = "book_summary/{bookId}"
    const val STUDY = "study/{bookId}?lesson={lesson}"
    const val STUDY_MODULE = "study/{bookId}/{lesson}/{module}?count={count}&difficulty={difficulty}"

    fun book(id: String) = "book/$id"
    fun chapter(id: String) = "chapter/${Uri.encode(id)}"
    fun summary(bookId: String, type: String, chapterId: String? = null) = "summary/$bookId/$type" + (chapterId?.let { "?chapterId=${Uri.encode(it)}" } ?: "")
    fun concept(id: String) = "concept/$id"
    fun teach(bookId: String, conceptId: String) = "teach/$bookId/$conceptId"
    fun quizSetup(bookId: String) = "quiz_setup/$bookId"
    fun quiz(id: String) = "quiz/$id"
    fun quizResult(id: String) = "quiz_result/$id"
    fun review(bookId: String? = null) = "review" + (bookId?.let { "?bookId=$it" } ?: "")
    fun notes(bookId: String? = null) = "notes" + (bookId?.let { "?bookId=$it" } ?: "")
    fun noteEdit(bookId: String, noteId: String? = null, chapterId: String? = null, highlight: String? = null) =
        "note_edit?bookId=$bookId" + (noteId?.let { "&noteId=$it" } ?: "") + (chapterId?.let { "&chapterId=${Uri.encode(it)}" } ?: "") +
            (highlight?.let { "&highlight=${Uri.encode(it.take(1_500))}" } ?: "")
    fun plan(bookId: String) = "plan/$bookId"
    fun ask(bookId: String) = "ask/$bookId"
    fun bookSummary(bookId: String) = "book_summary/$bookId"
    fun study(bookId: String, lesson: Int? = null) = "study/$bookId" + (lesson?.let { "?lesson=$it" } ?: "")
    fun studyModule(bookId: String, lesson: Int, module: String, count: Int? = null, difficulty: Int? = null) =
        "study/$bookId/$lesson/$module" + listOfNotNull(count?.let { "count=$it" }, difficulty?.let { "difficulty=$it" }).joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
}
