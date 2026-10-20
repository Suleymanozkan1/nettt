package com.bookmind.core.ai.study

import kotlinx.serialization.Serializable

/*
 * Results of the "Next lesson" study modules. The shapes are deliberately flat and short so a 1–4B on-device model
 * can fill them reliably; every field has a default so a partly filled answer still decodes.
 */

/** ÖĞREN › Akıllı metin: a structured lesson note instead of a plain summary. */
@Serializable
data class SmartNotes(
    val title: String = "",
    val mainIdea: String = "",
    val concepts: List<TermNote> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val quotes: List<String> = emptyList(),
    val takeaway: String = "",
)

@Serializable
data class TermNote(val term: String = "", val explanation: String = "")

/** ÖĞREN › Podcast: a two-person dialogue (A = host, B = expert) read aloud by the phone's TTS. */
@Serializable
data class PodcastScript(val title: String = "", val lines: List<PodcastLine> = emptyList())

@Serializable
data class PodcastLine(val speaker: String = "A", val text: String = "")

/** ÖĞREN › Hafıza kartları. */
@Serializable
data class FlashcardDeck(val cards: List<Flashcard> = emptyList())

@Serializable
data class Flashcard(val front: String = "", val back: String = "")

/** ÖĞREN › Bilgi boşlukları: short cards that fill a prerequisite or a mistake. */
@Serializable
data class GapDeck(val gaps: List<GapCard> = emptyList())

@Serializable
data class GapCard(
    val topic: String = "",
    /** Why this is a gap: "needed to follow this chapter" or "you answered … wrong". */
    val why: String = "",
    val explanation: String = "",
    val example: String = "",
)

/** A mistake from practice or exams, fed to the gap analysis. */
@Serializable
data class Mistake(val question: String, val expected: String, val given: String? = null)

/** PRATİK YAP › Alıştırma: cloze sentences and term–definition matching. */
@Serializable
data class ExerciseSet(val cloze: List<ClozeItem> = emptyList(), val matching: List<MatchItem> = emptyList())

@Serializable
data class ClozeItem(
    /** The sentence with the missing word replaced by "_____". */
    val sentence: String = "",
    val answer: String = "",
    /** Choices including the answer; empty means the user types it. */
    val options: List<String> = emptyList(),
)

@Serializable
data class MatchItem(val term: String = "", val definition: String = "")

/** PRATİK YAP › Test: four-option multiple choice. */
@Serializable
data class McqSet(val questions: List<McqQuestion> = emptyList())

@Serializable
data class McqQuestion(
    val question: String = "",
    val options: List<String> = emptyList(),
    /** Index (0–3) of the correct option. */
    val answer: Int = 0,
    val explanation: String = "",
)

/** PRATİK YAP › Doğru / Yanlış. */
@Serializable
data class TrueFalseSet(val items: List<TrueFalseItem> = emptyList())

@Serializable
data class TrueFalseItem(val statement: String = "", val isTrue: Boolean = true, val explanation: String = "")

/** SINAV › Yazılı deneme sınavı. */
@Serializable
data class WrittenExam(val questions: List<ExamQuestion> = emptyList())

@Serializable
data class ExamQuestion(val question: String = "", val idealAnswer: String = "", val keyPoints: List<String> = emptyList())

/** Grade of one written answer, 0–100. */
@Serializable
data class WrittenGrade(
    val score: Double = 0.0,
    val missing: List<String> = emptyList(),
    val feedback: String = "",
    val idealAnswer: String = "",
)

/** SINAV › Sözlü: the examiner's short reaction to a spoken answer. */
@Serializable
data class OralTurn(
    val score: Double = 0.0,
    /** At most two sentences, read aloud. */
    val feedback: String = "",
    /** A short follow-up question when the answer was incomplete; null moves on to the next question. */
    val followUp: String? = null,
)
