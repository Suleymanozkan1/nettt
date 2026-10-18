# Veri modeli

Kaynak: `app/src/main/java/com/bookmind/app/data/db/Entities.kt` (Room, şema sürümü **2**). Dışa aktarılmış şemalar:
`app/schemas/.../1.json`, `2.json`. Sunucu tabloları bu şemadan üretilir
(`supabase/migrations/20261018000002_synced_tables.sql`).

## Genel kurallar

- Kimlikler UUID metinleridir (çevrimdışı oluşturulan satırlar senkronizasyonda kimliğini korur). Bölüm kimlikleri
  deterministiktir: `<bookId>:<sıra>`; bu, metnin başka cihazda yeniden çıkarılmasında kavramların bağını korur.
- Zaman damgaları epoch milisaniye (UTC). Gün sınırı gerektiren hesaplar (günlük hedef, seri, hatırlatma) cihazın
  o anki saat dilimiyle yapılır.
- JSON sütunları sürümlü kotlinx.serialization belgeleridir: `SourceRef` listeleri, `GenerationInfo`,
  `SummaryDocument`, `QuestionDraft`, `ChapterAnalysis`, `BookAnalysis`.
- `updatedAt` last-writer-wins için, `deletedAt` silmenin diğer cihazlara yayılması için (tombstone).
- Bütünlük: tüm ilişkiler foreign key; çocuklar `CASCADE`, kavram silinince kart `SET NULL` (kart ve geçmişi
  korunur); sık sorgular için indeksler; tekrarları önlemek için benzersiz kısıtlar.

## Tablolar

| Tablo | Önemli alanlar | Not |
|---|---|---|
| `users` | language, timezone, dailyGoalMinutes, remoteUserId | Tek yerel profil; sunucuda `profiles` |
| `books` | title, author, language, category (tür), description, sourceType, processingStatus/Progress/Message, chapterCount, wordCount, analysisJson, isDemo, lastOpenedAt, completedAt, deletedAt | |
| `book_sources` | bookId (benzersiz), fileReference, checksum (indeksli), sizeBytes, originalName, sourceMetadata, processingVersion | Yinelenen kitap tespiti |
| `chapters` | bookId+orderIndex (benzersiz), title, startLocation/endLocation, pageStart/pageEnd, wordCount, textFile, contentHash, summary, analysisJson, processingStatus/Version, generatedBy | Metin dosyada (SQLite imleç penceresi sınırı) |
| `concepts` | bookId, chapterId, title, normalizedTitle (bölümde benzersiz), explanation, importance, difficulty, isPrerequisite, sourceReferences, isFavorite, userEdited, generatedBy | |
| `summaries` | bookId, chapterId?, summaryType, length, content (SummaryDocument), sourceReferences, modelMetadata, warnings, userEditedText | |
| `learning_items` | userId, bookId, conceptId?, itemType, prompt, expectedAnswer, explanation, sourceReferences, difficulty, questionJson, dedupeKey (kullanıcıda benzersiz), suspended | Tür: CONCEPT, KEY_IDEA, DATE_EVENT, FORMULA, CHARACTER_RELATION, PRINCIPLE, CASE, WRONG_ANSWER, USER_NOTE, QUESTION |
| `review_states` | learningItemId (benzersiz), phase, stability, difficulty, easeFactor, lastReviewAt, nextReviewAt (indeksli), reviewCount, lapseCount, lastRating, algorithmVersion | `nextReviewAt = null` → henüz tanıtılmamış yeni kart |
| `review_logs` | learningItemId, reviewedAt, response, correctness (ölçüm 0..1), responseTimeMs, confidenceRating (kullanıcı beyanı), rating (nihai), scheduledIntervalMs, elapsedDays, hintUsed, recallLevel, retrievabilityBefore (tahmin), source, algorithmMetadata | Ölçüm, tahmin ve beyan ayrı sütunlarda |
| `quizzes` | bookId, title, configuration (QuizConfig), generatedBy, warnings | |
| `quiz_questions` | quizId, learningItemId?, orderIndex, questionType, prompt, options, expectedAnswer, explanation, sourceReferences, gradingRubric, payload (QuestionDraft), needsReview | |
| `quiz_attempts` | quizId, startedAt, completedAt, score, resultMetadata | |
| `quiz_answers` | attemptId+questionId (benzersiz), userAnswer, score, correct, feedback, gradeConfidence, gradedBy, responseTimeMs | |
| `study_sessions` | bookId?, sessionType (TEACH, QUIZ, REVIEW, READ, PLAN_TASK, STORY), startedAt, completedAt, sessionMetadata | Günlük hedef bunlardan ölçülür |
| `teach_messages` | sessionId, role, content, metadata (TeachTurn) | Yalnızca cihazda |
| `user_notes` | bookId, chapterId?, content, highlight, sourceLocation, isFavorite, inReviewQueue, deletedAt | |
| `notification_preferences` | userId (benzersiz), enabled, localTime, selectedDays (bit maskesi), notificationTypes, intensity, pausedUntilEpochDay, showContent | |
| `stories` | bookId, conceptId, kind, title, body, fromBook, sourceReference, questionsJson, generatedBy | Yalnızca cihazda |
| `xp_events` | kind, xp, at, refId | |
| `ai_cache`, `ai_usage` | anahtar → doğrulanmış JSON; görev, token, maliyet | Yalnızca cihazda |
| `sync_outbox` | entity, entityId, op (UPSERT/DELETE/UPLOAD), attempts, lastError | Tetikleyicilerle dolar |
| `concept_links` (v2) | fromConceptId, toConceptId, relation (benzersiz üçlü), explanation, confidence, aiDerived, engine | Kitaplar arası grafik |
| `study_plans` (v2) | bookId (benzersiz), dailyMinutes, startEpochDay | |
| `plan_tasks` (v2) | planId, epochDay, type, chapterIndex, minutes, title, done, completedAt | |

## Migration

- v1 → v2: `AutoMigration(1, 2)` (yeni tablolar). `DatabaseTest` bunu `MigrationTestHelper` ile doğrular ve
  Room'un taşınan dosyayı güncel şemaya karşı açabildiğini kontrol eder.
- Kural: şema değişiklikleri her zaman migration ile gelir; `fallbackToDestructiveMigration` kullanılmaz.

## Sunucu (Supabase)

Her tabloya `user_id uuid not null default auth.uid()` ve sunucu tarafından korunan `server_updated_at` eklenir.
Foreign key'ler `(alan, user_id)` bileşiktir: FK denetimleri RLS'yi atladığı için, bir kullanıcının başka bir
kullanıcının satırına (kimliğini bilse bile) bağ kuramaması bu şekilde sağlanır. RLS: her tabloda
`user_id = auth.uid()` (select/insert/update/delete). Ayrıca `profiles`, `ai_requests` (kota/maliyet defteri;
istemci yazamaz), özel `book-files` kovası (yol `<kullanıcı>/<kitap>/...`, yalnızca sahibi).
