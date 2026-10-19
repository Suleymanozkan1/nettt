package com.bookmind.app.data.repo

import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.db.ConceptLinkEntity
import com.bookmind.app.data.db.LinkRow
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.newId
import com.bookmind.core.ai.AiResult
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.text.TextNormalizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/** Cross-book knowledge graph. Links are suggestions with a confidence and an origin label, never facts. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class GraphRepository @Inject constructor(
    private val db: BookMindDatabase,
    private val ai: LearningAi,
    private val users: UserRepository,
    private val clock: AppClock,
) {
    fun links(): Flow<List<LinkRow>> = flow { emit(users.userId()) }.flatMapLatest { db.graph().observe(it) }

    data class Result(val links: Int, val warnings: List<String>, val aiGenerated: Boolean)

    suspend fun rebuild(): Result {
        val userId = users.userId()
        val books = db.books().all(userId).associateBy { it.id }
        val concepts = db.concepts().allForUser(userId).filter { it.bookId in books }
        val byBook = concepts.groupBy { it.bookId }
        if (byBook.size < 2) return Result(0, listOf(AppLanguage.text.needTwoBooks), false)
        // Most important concepts per book keep the prompt small.
        val input = byBook.mapKeys { books.getValue(it.key).title }.mapValues { (_, cs) -> cs.sortedByDescending { it.importance }.take(30).map { it.title } }
        return when (val r = ai.linkConcepts(input, AppLanguage.tag)) {
            is AiResult.Failed -> Result(0, listOf(AppLanguage.text.aiError(r.error)), false)
            is AiResult.Ok -> {
                val now = clock.now()
                fun find(title: String) = concepts.firstOrNull { TextNormalizer.asciiFold(it.title) == TextNormalizer.asciiFold(title) }
                val entities = r.value.mapNotNull { l ->
                    val from = find(l.fromConcept) ?: return@mapNotNull null
                    // Resolve "to" in a different book than "from".
                    val to = concepts.firstOrNull { it.bookId != from.bookId && TextNormalizer.asciiFold(it.title) == TextNormalizer.asciiFold(l.toConcept) } ?: return@mapNotNull null
                    ConceptLinkEntity(newId(), userId, from.id, to.id, l.relation.take(40), l.explanation.take(500), l.confidence, r.info.aiGenerated, r.info.engine, now)
                }.distinctBy { Triple(it.fromConceptId, it.toConceptId, it.relation) }
                db.graph().clearGenerated(userId)
                db.graph().upsert(entities)
                Result(entities.size, r.warnings, r.info.aiGenerated)
            }
        }
    }
}
