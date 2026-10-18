package com.bookmind.core.quiz

import com.bookmind.core.ai.QuestionDraft
import com.bookmind.core.ai.QuestionType
import com.bookmind.core.text.TextNormalizer

/**
 * Rule-based grading for objective question types. Open answers go through LearningAi.gradeOpenAnswer.
 * Typed answers are compared case- and diacritic-insensitively with a small typo tolerance.
 */
object QuizScorer {
    data class Score(val score: Double, val correct: Boolean, val feedback: String)

    fun isObjective(q: QuestionDraft): Boolean =
        q.type.isObjective || (q.type == QuestionType.SHORT_ANSWER && q.answer.split(' ').size <= 3)

    fun score(q: QuestionDraft, userAnswer: String): Score {
        val answer = userAnswer.trim()
        return when (q.type) {
            QuestionType.MULTIPLE_CHOICE, QuestionType.SCENARIO, QuestionType.REAL_LIFE, QuestionType.FIND_ERROR ->
                if (q.options.isEmpty()) typed(q, answer) else binary(normalize(answer) == normalize(q.answer), q)
            QuestionType.TRUE_FALSE -> binary(truthValue(answer) != null && truthValue(answer) == truthValue(q.answer), q)
            QuestionType.FILL_BLANK, QuestionType.SHORT_ANSWER, QuestionType.CALCULATION -> typed(q, answer)
            QuestionType.ORDERING -> {
                val expected = splitList(q.answer)
                val given = splitList(answer)
                val positions = expected.indices.count { i -> given.getOrNull(i)?.let(::normalize) == normalize(expected[i]) }
                val s = if (expected.isEmpty()) 0.0 else positions.toDouble() / expected.size
                Score(s, s == 1.0, if (s == 1.0) "Sıralama doğru." else "Doğru sıra: ${expected.joinToString(" → ")}")
            }
            QuestionType.MATCHING -> {
                // Answer format: "left=right" entries separated by " | ".
                val given = splitList(answer).mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 }?.let { normalize(it[0]) to normalize(it[1]) } }.toMap()
                val right = q.pairs.count { p -> given[normalize(p.left)] == normalize(p.right) }
                val s = if (q.pairs.isEmpty()) 0.0 else right.toDouble() / q.pairs.size
                Score(s, s == 1.0, if (s == 1.0) "Tüm eşleştirmeler doğru." else "$right/${q.pairs.size} eşleştirme doğru.")
            }
            else -> throw IllegalArgumentException("${q.type} is graded by the open-answer evaluator")
        }
    }

    private fun binary(ok: Boolean, q: QuestionDraft) = Score(if (ok) 1.0 else 0.0, ok, if (ok) "Doğru." else "Doğru cevap: ${displayAnswer(q)}")

    private fun typed(q: QuestionDraft, answer: String): Score {
        val candidates = (listOf(q.answer) + q.acceptableAnswers).filter { it.isNotBlank() }
        val a = normalize(answer)
        if (a.isEmpty()) return Score(0.0, false, "Cevap boş. Doğru cevap: ${q.answer}")
        if (q.type == QuestionType.CALCULATION) {
            val num = a.replace(',', '.').toDoubleOrNull()
            val ok = candidates.any { c -> val e = normalize(c).replace(',', '.').toDoubleOrNull(); e != null && num != null && kotlin.math.abs(e - num) <= 1e-6 + kotlin.math.abs(e) * 0.001 }
            if (ok) return Score(1.0, true, "Doğru.")
        }
        val ok = candidates.any { c ->
            val n = normalize(c)
            // Turkish suffixes: "enflasyonun" for "enflasyon" is accepted when the root matches.
            n == a || (a.startsWith(n) && a.length - n.length <= 4 && n.length >= 4) || (n.length >= 5 && levenshtein(n, a) <= n.length / 6 + 1)
        }
        return Score(if (ok) 1.0 else 0.0, ok, if (ok) "Doğru." else "Doğru cevap: ${q.answer}")
    }

    fun displayAnswer(q: QuestionDraft): String = when (q.type) {
        QuestionType.TRUE_FALSE -> if (truthValue(q.answer) == true) "Doğru" else "Yanlış"
        QuestionType.ORDERING -> splitList(q.answer).joinToString(" → ")
        QuestionType.MATCHING -> q.pairs.joinToString("; ") { "${it.left} → ${it.right}" }
        else -> q.answer
    }

    fun truthValue(s: String): Boolean? = when (TextNormalizer.foldCase(s.trim())) {
        "true", "doğru", "d", "evet", "yes" -> true
        "false", "yanlış", "y", "hayır", "no" -> false
        else -> null
    }

    fun splitList(s: String) = s.split('|').map { it.trim() }.filter { it.isNotEmpty() }

    fun normalize(s: String) = TextNormalizer.asciiFold(s).replace(Regex("[^\\p{L}\\p{N},.]+"), " ").trim().trimEnd('.')

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
