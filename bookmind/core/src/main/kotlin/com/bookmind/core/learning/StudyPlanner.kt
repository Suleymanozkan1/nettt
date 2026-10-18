package com.bookmind.core.learning

import java.time.LocalDate

/**
 * Turns a book into a day-by-day learning plan: prerequisite chapters first, a teaching session plus a short
 * quiz per chapter, review slots in between, and checkpoints. Missed days are rescheduled forward without
 * compressing everything into one day.
 */
object StudyPlanner {
    enum class TaskType { LEARN_CHAPTER, QUIZ, REVIEW, PRACTICE, CHECKPOINT }

    data class ChapterInfo(val index: Int, val title: String, val words: Int, val conceptCount: Int, val prerequisiteOf: Set<Int> = emptySet())

    data class PlanTask(val day: LocalDate, val type: TaskType, val chapterIndex: Int?, val minutes: Int, val title: String, val done: Boolean = false)

    /** Average reading/learning speed used for estimates (words per minute, including note taking). */
    const val STUDY_WPM = 120

    fun build(chapters: List<ChapterInfo>, dailyMinutes: Int, start: LocalDate, reviewMinutes: Int = 5): List<PlanTask> {
        require(dailyMinutes in 5..240) { "dailyMinutes must be 5..240" }
        val ordered = orderByPrerequisites(chapters)
        val tasks = mutableListOf<PlanTask>()
        var day = start
        var used = 0

        fun place(type: TaskType, chapter: Int?, minutes: Int, title: String) {
            var remaining = minutes
            var part = 1
            val totalParts = (minutes + dailyMinutes - 1) / dailyMinutes
            while (remaining > 0) {
                val free = dailyMinutes - used
                if (free < minOf(5, remaining)) { day = day.plusDays(1); used = 0; continue }
                val chunk = minOf(free, remaining)
                tasks += PlanTask(day, type, chapter, chunk, if (totalParts > 1) "$title (${part}/$totalParts)" else title)
                used += chunk
                remaining -= chunk
                part++
            }
        }

        for ((i, ch) in ordered.withIndex()) {
            // From the second day on, every day starts with a short review of what was learned so far.
            if (i > 0 && used == 0) place(TaskType.REVIEW, null, minOf(reviewMinutes, dailyMinutes / 3).coerceAtLeast(3), "Günlük tekrar")
            val learnMinutes = (ch.words / STUDY_WPM).coerceIn(5, 90)
            place(TaskType.LEARN_CHAPTER, ch.index, learnMinutes, "Öğren: ${ch.title}")
            place(TaskType.QUIZ, ch.index, (2 + ch.conceptCount / 2).coerceIn(3, 10), "Quiz: ${ch.title}")
            if ((i + 1) % 3 == 0 || i == ordered.lastIndex) {
                place(TaskType.CHECKPOINT, null, 10, if (i == ordered.lastIndex) "Kitap sonu kontrol noktası" else "Kontrol noktası")
            }
        }
        place(TaskType.PRACTICE, null, 10, "Gerçek hayatta uygulama görevi")
        return tasks
    }

    /** Topological order where prerequisite chapters come first; ties keep book order. */
    fun orderByPrerequisites(chapters: List<ChapterInfo>): List<ChapterInfo> {
        val byIndex = chapters.associateBy { it.index }
        val visited = mutableSetOf<Int>()
        val result = mutableListOf<ChapterInfo>()
        val dependsOn = chapters.associate { c -> c.index to chapters.filter { c.index in it.prerequisiteOf }.map { it.index } }
        fun visit(i: Int, stack: Set<Int>) {
            if (i in visited || i in stack) return
            dependsOn[i].orEmpty().sorted().forEach { visit(it, stack + i) }
            visited += i
            byIndex[i]?.let { result += it }
        }
        chapters.sortedBy { it.index }.forEach { visit(it.index, emptySet()) }
        return result
    }

    /**
     * Moves unfinished tasks from past days forward, starting today, respecting the daily budget. Nothing is
     * dropped and no day gets more than the budget, so a missed day never turns into a guilt-inducing pile.
     */
    fun reschedule(tasks: List<PlanTask>, today: LocalDate, dailyMinutes: Int): List<PlanTask> {
        val done = tasks.filter { it.done }
        val overdue = tasks.filter { !it.done && it.day < today }
        if (overdue.isEmpty()) return tasks
        val future = tasks.filter { !it.done && it.day >= today }
        val queue = (overdue + future).sortedWith(compareBy({ it.day }, { it.type.ordinal }))
        val result = done.toMutableList()
        var day = today
        var used = 0
        for (t in queue) {
            if (used > 0 && used + t.minutes > dailyMinutes) { day = day.plusDays(1); used = 0 }
            result += t.copy(day = maxOf(day, if (t in future && t.day > day) t.day else day))
            if (result.last().day > day) { day = result.last().day; used = 0 }
            used += t.minutes
        }
        return result
    }

    /** Adapts the next days: weak chapters (low quiz accuracy) get an extra review task. */
    fun adapt(tasks: List<PlanTask>, chapterAccuracy: Map<Int, Double>, today: LocalDate): List<PlanTask> {
        val weak = chapterAccuracy.filter { it.value < 0.6 }.keys
        if (weak.isEmpty()) return tasks
        val alreadyPlanned = tasks.filter { it.type == TaskType.REVIEW && it.chapterIndex in weak && it.day >= today && !it.done }.mapNotNull { it.chapterIndex }.toSet()
        val extra = (weak - alreadyPlanned).map { PlanTask(today.plusDays(1), TaskType.REVIEW, it, 5, "Zorlandığın bölümü tekrar et") }
        return (tasks + extra).sortedWith(compareBy({ it.day }, { it.type.ordinal }))
    }
}
