package com.bookmind.core.learning

import com.bookmind.core.ai.RecallLevel

/**
 * XP rewards quality of learning, not app opens or speed: delayed correct recall, unaided recall and
 * explanation/transfer answers earn more than recognition. Fast guessing earns nothing extra.
 */
object Gamification {
    data class XpEvent(val kind: Kind, val correct: Boolean = true, val level: RecallLevel? = null, val delayedDays: Double = 0.0, val hintUsed: Boolean = false)

    enum class Kind { REVIEW, QUIZ_ANSWER, TEACH_SESSION_COMPLETED, NOTE_CREATED, CHAPTER_STUDIED, BOOK_COMPLETED, REAL_LIFE_TASK, DAILY_GOAL }

    fun xp(e: XpEvent): Int = when (e.kind) {
        Kind.REVIEW, Kind.QUIZ_ANSWER -> {
            if (!e.correct) 1 // showing up and trying still counts a little
            else {
                val base = when (e.level) {
                    RecallLevel.RECOGNITION, null -> 2
                    RecallLevel.RECALL -> 4
                    RecallLevel.EXPLANATION -> 6
                    RecallLevel.TRANSFER, RecallLevel.APPLICATION -> 8
                }
                val delayBonus = when {
                    e.delayedDays >= 30 -> 6
                    e.delayedDays >= 7 -> 4
                    e.delayedDays >= 1 -> 2
                    else -> 0
                }
                (base + delayBonus) / if (e.hintUsed) 2 else 1
            }
        }
        Kind.TEACH_SESSION_COMPLETED -> 15
        Kind.NOTE_CREATED -> 2
        Kind.CHAPTER_STUDIED -> 10
        Kind.BOOK_COMPLETED -> 50
        Kind.REAL_LIFE_TASK -> 20
        Kind.DAILY_GOAL -> 10
    }

    /** Level n needs 100·n·(n+1)/2 XP in total: 100, 300, 600, 1000 … */
    fun level(totalXp: Int): Int {
        var level = 1
        while (100 * level * (level + 1) / 2 <= totalXp) level++
        return level
    }

    fun xpForLevel(level: Int): Int = 100 * (level - 1) * level / 2

    data class Badge(val id: String, val title: String, val description: String)

    data class Stats(
        val reviews: Int,
        val longestStreak: Int,
        val booksCompleted: Int,
        val conceptsRetained30d: Int,
        val explanationsCorrect: Int,
        val realLifeTasks: Int,
        val notes: Int,
    )

    val allBadges = listOf(
        Badge("first_review", "İlk tekrar", "İlk tekrarını tamamladın."),
        Badge("streak_7", "Bir hafta", "7 gün üst üste öğrendin."),
        Badge("streak_30", "Bir ay", "30 gün üst üste öğrendin."),
        Badge("reviews_100", "Yüz tekrar", "100 tekrar tamamladın."),
        Badge("book_1", "İlk kitap", "Bir kitabı tamamladın."),
        Badge("retained_10", "Kalıcı hafıza", "10 kavramı 30 günden uzun aralıkla doğru hatırladın."),
        Badge("explainer_10", "Anlatıcı", "10 açık uçlu soruyu kendi cümlelerinle doğru yanıtladın."),
        Badge("applier_5", "Uygulayıcı", "5 gerçek hayat görevini tamamladın."),
        Badge("note_taker", "Not ustası", "25 kişisel not aldın."),
    )

    fun earnedBadges(s: Stats): List<Badge> = allBadges.filter { b ->
        when (b.id) {
            "first_review" -> s.reviews >= 1
            "streak_7" -> s.longestStreak >= 7
            "streak_30" -> s.longestStreak >= 30
            "reviews_100" -> s.reviews >= 100
            "book_1" -> s.booksCompleted >= 1
            "retained_10" -> s.conceptsRetained30d >= 10
            "explainer_10" -> s.explanationsCorrect >= 10
            "applier_5" -> s.realLifeTasks >= 5
            "note_taker" -> s.notes >= 25
            else -> false
        }
    }
}
