package com.bookmind.core.ai

import com.bookmind.core.text.Checksums
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer

/** Stores raw validated model output keyed by (task, prompt version, provider, input hash). */
interface AiCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, task: AiTask, value: String)
}

/** Records usage for cost tracking and enforces the user's daily budget before a request is sent. */
interface AiUsageMeter {
    suspend fun allow(task: AiTask): Boolean
    suspend fun record(task: AiTask, response: AiResponse)
}

sealed interface AiResult<out T> {
    data class Ok<T>(val value: T, val info: GenerationInfo, val warnings: List<String> = emptyList(), val fromCache: Boolean = false) : AiResult<T>
    data class Failed(val error: AiException) : AiResult<Nothing>
}

/**
 * Single entry point for all generated learning content. Chooses the remote provider when configured,
 * retries transient failures with backoff, validates output, caches it, and falls back to the on-device
 * engine (clearly labelled) when the provider is missing, over budget or failing.
 */
class LearningAi(
    private val providerSource: suspend () -> AiProvider?,
    private val cache: AiCache? = null,
    private val meter: AiUsageMeter? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = 3,
    private val baseBackoffMs: Long = 700,
    val local: LocalHeuristicEngine = LocalHeuristicEngine(),
) {
    data class Book(val title: String, val author: String?, val language: String?, val genre: BookGenre)

    private fun localInfo(promptVersion: String) =
        GenerationInfo(engine = "local", model = "heuristic-1", promptVersion = promptVersion, aiGenerated = false, createdAt = clock())

    private suspend fun <T> run(
        request: AiRequest,
        serializer: KSerializer<T>,
        validate: (T) -> OutputValidator.Validated<T>,
        fallback: (() -> T)?,
        allowFallback: Boolean = true,
    ): AiResult<T> {
        val provider = try { providerSource() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (provider == null) {
            // On-device mode is the user's choice, not a problem: the origin badge says "local", no warning needed.
            return localOrFail(fallback, request.promptVersion, null, AiException.NotConfigured())
        }
        if (meter != null && !meter.allow(request.task)) {
            return localOrFail(fallback, request.promptVersion, "Günlük yapay zekâ kullanım sınırına ulaşıldı; yerel yöntem kullanıldı.", AiException.QuotaExceeded(null))
        }

        val inputHash = Checksums.sha256(request.system + "\u0000" + request.user)
        val cacheKey = "${request.task}:${request.promptVersion}:${provider.id}:$inputHash"
        cache?.get(cacheKey)?.let { cached ->
            runCatching { validate(AiJson.decode(serializer, cached)) }.getOrNull()?.let { v ->
                return AiResult.Ok(v.value, GenerationInfo(provider.id, null, request.promptVersion, aiGenerated = true, inputHash = inputHash, createdAt = clock()), v.warnings, fromCache = true)
            }
        }

        var lastError: AiException = AiException.Network(null)
        var current = request
        for (attempt in 1..maxAttempts) {
            try {
                val response = provider.complete(current)
                meter?.record(request.task, response)
                val validated = validate(AiJson.decode(serializer, response.text))
                cache?.put(cacheKey, request.task, AiJson.json.encodeToString(serializer, validated.value))
                return AiResult.Ok(
                    validated.value,
                    GenerationInfo(provider.id, response.model, request.promptVersion, aiGenerated = true, inputHash = inputHash, createdAt = clock(),
                        inputTokens = response.inputTokens, outputTokens = response.outputTokens),
                    validated.warnings,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                lastError = e
                when {
                    e is AiException.InvalidOutput && attempt < maxAttempts -> {
                        // One corrective retry: remind the model of the format.
                        current = request.copy(user = request.user + "\n\nYour previous reply was not valid JSON for the schema (${e.message}). Reply with JSON only.")
                    }
                    e.retryable && attempt < maxAttempts -> delay(baseBackoffMs * (1L shl (attempt - 1)))
                    else -> break
                }
            }
        }
        return if (allowFallback) localOrFail(fallback, request.promptVersion, "Yapay zekâ yanıtı alınamadı (${lastError.message}); yerel yöntem kullanıldı.", lastError)
        else AiResult.Failed(lastError)
    }

    private fun <T> localOrFail(fallback: (() -> T)?, promptVersion: String, warning: String?, error: AiException): AiResult<T> =
        if (fallback == null) AiResult.Failed(error) else AiResult.Ok(fallback(), localInfo(promptVersion), listOfNotNull(warning))

    // ----- Payload helpers for the mock provider -----

    private fun chaptersPayload(chapters: List<LocalHeuristicEngine.ChapterInput>) =
        AiJson.json.encodeToString(MockPayload.Chapters.serializer(), MockPayload.Chapters(chapters.map { MockPayload.Chapter(it.index, it.title, it.text) }))

    private fun conceptsPayload(concepts: List<LocalHeuristicEngine.ConceptInput>) =
        AiJson.json.encodeToString(MockPayload.Concepts.serializer(), MockPayload.Concepts(concepts.map { MockPayload.Concept(it.title, it.explanation, it.chapterIndex, it.quote) }))

    // ----- Tasks -----

    suspend fun analyzeBook(title: String?, author: String?, lang: String?, chapters: List<LocalHeuristicEngine.ChapterInput>): AiResult<BookAnalysis> {
        val toc = chapters.joinToString("\n") { "${it.index + 1}. ${it.title}" }
        val opening = chapters.take(2).joinToString("\n\n") { it.text.take(6_000) }
        val request = PromptBuilder.bookAnalysis(title, author, lang, "$toc\n\n$opening")
            .copy(payload = mapOf("title" to title.orEmpty(), "author" to author.orEmpty(), "lang" to lang.orEmpty(), "chapters" to chaptersPayload(chapters.take(3))))
        return run(request, BookAnalysis.serializer(), { OutputValidator.Validated(it, emptyList()) }, { local.bookAnalysis(title, author, lang, chapters) })
    }

    suspend fun analyzeChapter(book: Book, chapter: LocalHeuristicEngine.ChapterInput, chunk: String, partInfo: String): AiResult<ChapterAnalysis> {
        val request = PromptBuilder.chapterAnalysis(book.title, chapter.title, chapter.index, book.genre, book.language, chunk, partInfo)
            .copy(payload = mapOf("chapters" to chaptersPayload(listOf(chapter.copy(text = chunk)))))
        return run(request, ChapterAnalysis.serializer(), { OutputValidator.chapterAnalysis(it, chapter.text) }, { local.chapterAnalysis(chapter.copy(text = chunk)) })
    }

    suspend fun summarize(
        book: Book,
        type: SummaryType,
        length: SummaryLength,
        chapters: List<LocalHeuristicEngine.ChapterInput>,
        chapterDigests: Map<Int, String>,
        concepts: List<LocalHeuristicEngine.ConceptInput>,
        spoilerFree: Boolean = false,
    ): AiResult<SummaryDocument> {
        val digest = chapters.joinToString("\n\n") { ch ->
            "[${ch.index}] ${ch.title}\n${chapterDigests[ch.index].orEmpty().ifBlank { ch.text.take(2_500) }}\nKavramlar: " +
                concepts.filter { it.chapterIndex == ch.index }.joinToString("; ") { it.title }
        }
        val request = PromptBuilder.summary(type, book.title, book.language, length, digest, spoilerFree)
            .copy(payload = mapOf("type" to type.name, "length" to length.name, "title" to book.title, "chapters" to chaptersPayload(chapters), "concepts" to conceptsPayload(concepts)))
        val texts = chapters.sortedBy { it.index }.let { list -> (0..(list.maxOfOrNull { it.index } ?: -1)).map { i -> list.firstOrNull { it.index == i }?.text.orEmpty() } }
        return run(request, SummaryDocument.serializer(), { OutputValidator.summary(it.copy(type = type), texts) }, { local.summary(type, book.title, chapters, concepts, length) })
    }

    suspend fun generateQuiz(
        book: Book,
        chapters: List<LocalHeuristicEngine.ChapterInput>,
        concepts: List<LocalHeuristicEngine.ConceptInput>,
        count: Int,
        difficulty: Int,
        types: Set<QuestionType>,
        spoilerFree: Boolean,
        seed: Int,
    ): AiResult<List<QuestionDraft>> {
        val material = chapters.joinToString("\n\n") { ch ->
            "[${ch.index}] ${ch.title}\n" + concepts.filter { it.chapterIndex == ch.index }.joinToString("\n") { "- ${it.title}: ${it.explanation}" } + "\n" + ch.text.take(3_000)
        }
        val request = PromptBuilder.quiz(book.title, book.genre, book.language, count, difficulty, types, material, spoilerFree)
            .copy(payload = mapOf("count" to count.toString(), "types" to types.joinToString(",") { it.name }, "chapters" to chaptersPayload(chapters), "concepts" to conceptsPayload(concepts)))
        val maxIndex = chapters.maxOfOrNull { it.index } ?: -1
        val texts = (0..maxIndex).map { i -> chapters.firstOrNull { it.index == i }?.text.orEmpty() }
        val result = run(
            request,
            QuizDraft.serializer(),
            { draft -> OutputValidator.quiz(draft, texts).let { v -> OutputValidator.Validated(QuizDraft(v.value), v.warnings) } },
            { QuizDraft(local.quiz(concepts, chapters, count, types, seed)) },
        )
        return when (result) {
            is AiResult.Ok -> if (result.value.questions.isEmpty()) AiResult.Failed(AiException.InvalidOutput("Soru üretmek için yeterli kavram yok"))
                else AiResult.Ok(result.value.questions.take(count), result.info, result.warnings, result.fromCache)
            is AiResult.Failed -> result
        }
    }

    data class TeachContext(
        val step: Int,
        val concept: LocalHeuristicEngine.ConceptInput,
        val chapter: LocalHeuristicEngine.ChapterInput?,
        val level: TeachingLevel,
        val style: String,
        val history: List<Pair<String, String>>,
        val lastAnswer: String?,
        val lastExpected: String?,
    )

    suspend fun teach(book: Book, ctx: TeachContext): AiResult<TeachTurn> {
        val excerpt = ctx.chapter?.let { ch ->
            val idx = local.termRegex(ctx.concept.title).find(ch.text)?.range?.first ?: 0
            ch.text.substring((idx - 2_000).coerceAtLeast(0), (idx + 4_000).coerceAtMost(ch.text.length))
        }.orEmpty()
        val request = PromptBuilder.teach(ctx.concept.title, ctx.concept.explanation, book.title, book.genre, book.language, ctx.level, ctx.style, ctx.history.takeLast(12), excerpt)
            .copy(payload = mapOf("step" to ctx.step.toString(), "answer" to ctx.lastAnswer.orEmpty(), "expected" to ctx.lastExpected.orEmpty(), "style" to ctx.style,
                "concepts" to conceptsPayload(listOf(ctx.concept)), "chapters" to chaptersPayload(listOfNotNull(ctx.chapter))))
        // Tutor turns are conversational; caching them would repeat the same reply, so no cache for TEACH.
        return runUncached(request, TeachTurn.serializer(), { OutputValidator.Validated(it, emptyList()) }) {
            local.teach(ctx.step, ctx.concept, ctx.chapter, ctx.lastAnswer, ctx.lastExpected, ctx.style)
        }
    }

    private suspend fun <T> runUncached(request: AiRequest, serializer: KSerializer<T>, validate: (T) -> OutputValidator.Validated<T>, fallback: () -> T): AiResult<T> =
        LearningAi(providerSource, null, meter, clock, maxAttempts, baseBackoffMs, local).run(request, serializer, validate, fallback)

    suspend fun gradeOpenAnswer(question: String, modelAnswer: String, rubric: List<String>, userAnswer: String, lang: String?, excerpt: String, acceptable: List<String> = emptyList()): AiResult<GradeResult> {
        val request = PromptBuilder.grade(question, modelAnswer, rubric, userAnswer, lang, excerpt)
            .copy(payload = mapOf("modelAnswer" to modelAnswer, "rubric" to rubric.joinToString("\n"), "answer" to userAnswer))
        return run(request, GradeResult.serializer(), { OutputValidator.Validated(OutputValidator.grade(it), emptyList()) }, { local.grade(modelAnswer, rubric, userAnswer, acceptable) })
    }

    suspend fun story(book: Book, concept: LocalHeuristicEngine.ConceptInput, chapter: LocalHeuristicEngine.ChapterInput?, kind: String): AiResult<StoryDraft> {
        val excerpt = chapter?.text?.take(4_000).orEmpty()
        val request = PromptBuilder.story(concept.title, concept.explanation, kind, book.language, excerpt)
            .copy(payload = mapOf("concepts" to conceptsPayload(listOf(concept)), "chapters" to chaptersPayload(listOfNotNull(chapter))))
        return runUncached(request, StoryDraft.serializer(), { s ->
            val warnings = mutableListOf<String>()
            val src = s.source?.let { ref ->
                val q = OutputValidator.limitQuote(ref.quote)
                val ok = q == null || (chapter != null && OutputValidator.quoteOccursIn(q, chapter.text))
                if (!ok) warnings += "Hikâyenin kitap kaynağı doğrulanamadı; üretilmiş örnek olarak işaretlendi."
                ref.copy(quote = if (ok) q else null, verified = ok)
            }
            OutputValidator.Validated(s.copy(source = src, fromBook = s.fromBook && src?.verified == true, questions = s.questions.filter { OutputValidator.questionIssue(it) == null }), warnings)
        }) { local.story(concept, chapter) }
    }

    suspend fun linkConcepts(conceptsByBook: Map<String, List<String>>, lang: String?): AiResult<List<ConceptLinkDraft>> {
        val known = conceptsByBook.values.flatten().toSet()
        val request = PromptBuilder.conceptLinks(conceptsByBook, lang)
            .copy(payload = mapOf("conceptsByBook" to AiJson.json.encodeToString(MockPayload.ConceptsByBook.serializer(), MockPayload.ConceptsByBook(conceptsByBook))))
        val result = run(request, ConceptLinksDraft.serializer(), { d -> OutputValidator.links(d, known).let { OutputValidator.Validated(ConceptLinksDraft(it.value), it.warnings) } }, { local.links(conceptsByBook) })
        return when (result) {
            is AiResult.Ok -> AiResult.Ok(result.value.links, result.info, result.warnings, result.fromCache)
            is AiResult.Failed -> result
        }
    }
}
