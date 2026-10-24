package com.bookmind.core.i18n

import com.bookmind.core.ai.AiException

/**
 * Every user-visible phrase the core module produces (on-device summaries, questions, tutor turns, grading
 * feedback, plan task titles, warnings) in the reader's output language. Only "en" selects English;
 * anything else is Turkish, the app's default language.
 */
sealed class CoreText {
    companion object {
        fun of(lang: String?): CoreText = if (lang?.lowercase()?.startsWith("en") == true) En else Tr
    }

    // Summaries
    abstract val localNote: String
    abstract val keyIdea: String
    abstract val highlights: String
    abstract val keyConcepts: String
    abstract fun quickTitle(book: String): String
    abstract fun chapterSummariesTitle(book: String): String
    abstract val executiveLocalNote: String
    abstract fun keyIdeasTitle(book: String): String
    abstract val coOccurs: String
    abstract fun conceptMapTitle(book: String): String
    abstract val conceptMapNote: String
    abstract val sharedConceptsBody: String
    abstract fun comparisonTitle(book: String): String
    abstract val noSharedTitle: String
    abstract val noSharedBody: String
    abstract val comparisonNote: String

    // Questions
    abstract fun mcqPrompt(term: String): String
    abstract fun inBook(explanation: String): String
    abstract fun blankAnswer(answer: String): String
    // ----- Study hub ("Next lesson") fallbacks -----
    abstract fun podcastWelcome(topic: String): String
    abstract fun podcastAskTerm(term: String): String
    abstract val podcastWhyMatters: String
    abstract val podcastClosing: String
    abstract fun flashFront(term: String): String
    abstract fun gapFromEarlier(chapterNumber: Int): String
    abstract val gapCore: String
    abstract val gapMistake: String
    abstract fun examExplain(term: String): String
    abstract fun examCompare(a: String, b: String): String
    abstract fun examApply(term: String): String
    abstract fun oralFollowUp(point: String): String
    abstract val oralGood: String
    abstract val oralPartial: String
    abstract val oralMissed: String

    abstract fun falseBecause(term: String, sentence: String): String
    abstract val trueInBook: String
    abstract fun explainOwnWords(term: String): String
    abstract fun bookExplanation(explanation: String): String
    abstract fun define(term: String): String
    abstract val matchPrompt: String
    abstract fun orderPrompt(chapter: String): String
    abstract val orderExplanation: String
    abstract val thisConcept: String
    abstract fun whatIs(term: String): String

    // Grading
    abstract val dontKnowAnswers: Set<String>
    abstract val noProblem: String
    abstract val noAnswer: String
    abstract val correct: String
    abstract val matchedAcceptable: String
    abstract val coversMainPoints: String
    abstract val partlyCorrect: String
    abstract val missesMainPoints: String
    abstract fun keywordRationale(covered: Int, total: Int): String
    abstract val generallyCorrect: String
    abstract val incompleteOrWrong: String
    abstract val orderCorrect: String
    abstract fun correctOrder(order: String): String
    abstract val allMatchesCorrect: String
    abstract fun matchesCorrect(right: Int, total: Int): String
    abstract fun correctAnswer(answer: String): String
    abstract fun emptyAnswer(answer: String): String
    abstract val trueWord: String
    abstract val falseWord: String

    // Tutor
    abstract fun teachStart(term: String): String
    abstract fun teachDiagnose(term: String): String
    abstract val styleNeedsAi: String
    abstract val knowsBasics: String
    abstract val stepByStep: String
    abstract fun bookExplains(explanation: String): String
    abstract fun bookExample(sentence: String): String
    abstract fun fillBlank(sentence: String): String
    abstract val keyWordQuestion: String
    abstract val correctClap: String
    abstract fun notQuite(expected: String): String
    abstract fun anotherSentence(sentence: String): String
    abstract val applyQuestion: String
    abstract val goodApplication: String
    abstract val beConcrete: String
    abstract fun explainToNovice(term: String): String
    abstract val explainedWell: String
    abstract fun missedPoints(points: String): String
    abstract val addedToReview: String

    // Stories & links
    abstract fun bookExamplesTitle(term: String): String
    abstract fun howAppears(term: String): String
    abstract val sameName: String
    abstract val namesOverlap: String

    // Planner
    abstract val dailyReview: String
    abstract fun learn(chapter: String): String
    abstract fun quiz(chapter: String): String
    abstract val finalCheckpoint: String
    abstract val checkpoint: String
    abstract val practiceTask: String
    abstract val reviewWeakChapter: String

    // Warnings and errors
    abstract val quotaReached: String
    abstract fun aiFailed(reason: String): String
    abstract val notEnoughConcepts: String
    abstract val storySourceUnverified: String
    abstract fun quoteRemoved(term: String): String
    abstract fun sourceChapterMissing(n: Int): String
    abstract val quoteUnverified: String
    abstract fun questionSkipped(issue: String): String
    abstract val questionQuoteUnverified: String
    abstract val unknownLinkSkipped: String
    abstract fun part(n: Int): String
    abstract val untitledBook: String
    abstract val ocrWarning: String
    abstract fun onlyFirstPages(n: Int): String
    abstract val needTwoBooks: String
    abstract fun rememberHighlight(quote: String): String
    abstract fun yourNote(line: String): String
    abstract fun couldNotGrade(reason: String): String

    abstract fun chaptersFailed(n: Int): String
    abstract val quizWrongOnly: String
    abstract val answerFromBook: String
    abstract fun unsupportedClaims(items: String): String
    abstract val answerNotFound: String
    abstract val answerUncited: String
    abstract val quizNearForgetting: String
    abstract val quizMixed: String
    abstract val quizFullBook: String
    abstract fun aiError(e: AiException): String

    // Parsing
    abstract val opening: String
    abstract val introduction: String
    abstract fun chapter(n: Int): String
    abstract fun pages(from: Int, to: Int): String
    abstract fun line(n: Int): String
    abstract fun page(n: Int): String
    abstract val pageAbbrev: String
    abstract val noHeadings: String
    abstract val pdfScanned: String
    abstract val pdfNoOutline: String
    abstract fun backMatterRemoved(pages: Int): String
    abstract fun backMatterChapters(count: Int): String
    abstract fun epubMissing(href: String): String
    abstract val epubNoText: String

    object Tr : CoreText() {
        override val localNote = "Bu içerik cihaz üzerinde, kitaptaki cümleler seçilerek oluşturuldu (yapay zekâ kullanılmadı). Yorumlayıcı bir özet için yapay zekâ sağlayıcısını etkinleştirin."
        override val keyIdea = "Temel fikir"
        override val highlights = "Öne çıkan noktalar"
        override val keyConcepts = "Temel kavramlar"
        override fun quickTitle(book: String) = "$book – Hızlı özet"
        override fun chapterSummariesTitle(book: String) = "$book – Bölüm özetleri"
        override val executiveLocalNote = "Yönetici özeti (problem, tez, çözüm, kanıt) yorum gerektirir; yerel modda bölüm özetleri gösteriliyor."
        override fun keyIdeasTitle(book: String) = "$book – En önemli fikirler"
        override val coOccurs = "birlikte geçiyor"
        override fun conceptMapTitle(book: String) = "$book – Kavram haritası"
        override val conceptMapNote = "Bağlantılar kavramların aynı cümlelerde birlikte geçmesine dayanır; neden-sonuç ilişkisi anlamına gelmez."
        override val sharedConceptsBody = "Bu bölümler ortak kavramlar üzerinde duruyor."
        override fun comparisonTitle(book: String) = "$book – Bölüm karşılaştırması"
        override val noSharedTitle = "Ortak kavram bulunamadı"
        override val noSharedBody = "Bölümler arasında belirgin ortak kavram tespit edilmedi."
        override val comparisonNote = "Destekleme/çelişki ilişkisini belirlemek yorum gerektirir; yerel mod yalnızca ortak kavramları gösterir."

        override fun mcqPrompt(term: String) = "“$term” için aşağıdakilerden hangisi kitaptaki açıklamaya uygundur?"
        override fun inBook(explanation: String) = "Kitapta: $explanation"
        override fun blankAnswer(answer: String) = "Boşluğa “$answer” gelir."
        override fun podcastWelcome(topic: String) = "Merhaba, hoş geldiniz! Bugün “$topic” konusunu konuşuyoruz. Kısaca neyi anlatıyor?"
        override fun podcastAskTerm(term: String) = "Peki “$term” tam olarak ne demek?"
        override val podcastWhyMatters = "Bu neden önemli, biraz açar mısın?"
        override val podcastClosing = "Toparlarsak, dinleyicilerimizin aklında ne kalmalı?"
        override fun flashFront(term: String) = "“$term” nedir?"
        override fun gapFromEarlier(chapterNumber: Int) = "Bu bölümde kullanılıyor ama Bölüm $chapterNumber'de anlatıldı."
        override val gapCore = "Bu bölümün temel taşı; diğer fikirler buna dayanıyor."
        override val gapMistake = "Bu konuda bir soruyu yanlış cevapladın ya da boş bıraktın."
        override fun examExplain(term: String) = "“$term” kavramını kendi cümlelerinle açıkla."
        override fun examCompare(a: String, b: String) = "“$a” ile “$b” arasındaki ilişkiyi ya da farkı açıkla."
        override fun examApply(term: String) = "“$term” fikrini günlük hayattan bir örnekle nasıl uygularsın?"
        override fun oralFollowUp(point: String) = "Bir şey eksik kaldı: “$point” kısmını da açıklayabilir misin?"
        override val oralGood = "Güzel, ana noktaları yakaladın."
        override val oralPartial = "Kısmen doğru, ama bazı önemli noktalar eksik."
        override val oralMissed = "Bu cevap sorunun özünü kaçırıyor."
        override fun falseBecause(term: String, sentence: String) = "Yanlış. Kitapta bu ifade “$term” için geçiyor: $sentence"
        override val trueInBook = "Doğru; bu ifade kitapta geçiyor."
        override fun explainOwnWords(term: String) = "“$term” kavramını kendi cümlelerinle açıkla."
        override fun bookExplanation(explanation: String) = "Kitaptaki açıklama: $explanation"
        override fun define(term: String) = "Tanımla: $term"
        override val matchPrompt = "Kavramları açıklamalarıyla eşleştir."
        override fun orderPrompt(chapter: String) = "Bu ifadeleri “$chapter” bölümünde geçtikleri sıraya göre diz."
        override val orderExplanation = "Doğru sıra kitaptaki akışı izler."
        override val thisConcept = "bu kavram"
        override fun whatIs(term: String) = "“$term” nedir? Kendi cümlelerinle açıkla."

        override val dontKnowAnswers = setOf("bilmiyorum", "bilmiyorum.", "?")
        override val noProblem = "Sorun değil; birlikte bakalım."
        override val noAnswer = "Cevap verilmedi."
        override val correct = "Doğru."
        override val matchedAcceptable = "Kabul edilen cevaplardan biriyle eşleşti."
        override val coversMainPoints = "Ana noktaları kapsıyorsun."
        override val partlyCorrect = "Kısmen doğru; bazı noktalar eksik."
        override val missesMainPoints = "Cevap kitaptaki açıklamanın ana noktalarını kapsamıyor."
        override fun keywordRationale(covered: Int, total: Int) = "Anahtar kelime kapsamına göre yaklaşık değerlendirme ($covered/$total nokta)."
        override val generallyCorrect = "Cevabın genel olarak doğru."
        override val incompleteOrWrong = "Cevap eksik veya hatalı."
        override val orderCorrect = "Sıralama doğru."
        override fun correctOrder(order: String) = "Doğru sıra: $order"
        override val allMatchesCorrect = "Tüm eşleştirmeler doğru."
        override fun matchesCorrect(right: Int, total: Int) = "$right/$total eşleştirme doğru."
        override fun correctAnswer(answer: String) = "Doğru cevap: $answer"
        override fun emptyAnswer(answer: String) = "Cevap boş. Doğru cevap: $answer"
        override val trueWord = "Doğru"
        override val falseWord = "Yanlış"

        override fun teachStart(term: String) = "“$term” konusuna başlayalım. Önce ne bildiğini görelim."
        override fun teachDiagnose(term: String) = "“$term” sana ne ifade ediyor? Bir iki cümleyle anlat (bilmiyorsan “bilmiyorum” yazabilirsin)."
        override val styleNeedsAi = "\n\n(Benzetme ve tartışma üretmek için yapay zekâ sağlayıcısı gerekiyor; şimdilik kitaptan örnek gösteriyorum.)"
        override val knowsBasics = "Güzel, temel fikri biliyorsun. "
        override val stepByStep = "Hiç sorun değil, adım adım gidelim. "
        override fun bookExplains(explanation: String) = "Kitap bu kavramı şöyle açıklıyor:\n\n“$explanation”"
        override fun bookExample(sentence: String) = "\n\nKitaptan bir örnek:\n“$sentence”"
        override fun fillBlank(sentence: String) = "Boşluğu doldur: $sentence"
        override val keyWordQuestion = "Bu açıklamadaki en önemli kelime sence hangisi, neden?"
        override val correctClap = "Doğru! 👏"
        override fun notQuite(expected: String) = "Tam değil. Boşluğa “$expected” gelmeliydi; çünkü cümle bu kavramın tanımına dayanıyor."
        override fun anotherSentence(sentence: String) = "\n\nFarklı bir cümleyle tekrar bakalım:\n“$sentence”"
        override val applyQuestion = "Bu kavramı günlük hayatında ya da işinde nerede kullanabilirsin? Somut bir durum yaz."
        override val goodApplication = "Bu, kavramı uygulamaya dökmenin iyi bir yolu."
        override val beConcrete = "Biraz daha somut düşünmeyi dene: kim, nerede, ne zaman?"
        override fun explainToNovice(term: String) = "Son olarak: “$term” kavramını hiç bilmeyen birine kendi cümlelerinle nasıl anlatırdın?"
        override val explainedWell = "Harika, kavramı kendi cümlelerinle açıklayabildin. "
        override fun missedPoints(points: String) = "Açıklaman bazı noktaları kaçırıyor: $points. "
        override val addedToReview = "Bu kavramı tekrar kuyruğuna ekledim; unutma eğrine göre yeniden soracağım."

        override fun bookExamplesTitle(term: String) = "Kitaptan örnekler: $term"
        override fun howAppears(term: String) = "Bu örneklerde “$term” nasıl ortaya çıkıyor?"
        override val sameName = "İki kitapta aynı adla geçiyor."
        override val namesOverlap = "Adları büyük ölçüde örtüşüyor; ilişki yerel olarak tahmin edildi."

        override val dailyReview = "Günlük tekrar"
        override fun learn(chapter: String) = "Öğren: $chapter"
        override fun quiz(chapter: String) = "Quiz: $chapter"
        override val finalCheckpoint = "Kitap sonu kontrol noktası"
        override val checkpoint = "Kontrol noktası"
        override val practiceTask = "Gerçek hayatta uygulama görevi"
        override val reviewWeakChapter = "Zorlandığın bölümü tekrar et"

        override val quotaReached = "Günlük yapay zekâ kullanım sınırına ulaşıldı; yerel yöntem kullanıldı."
        override fun aiFailed(reason: String) = "Yapay zekâ yanıtı alınamadı ($reason); klasik yöntem kullanıldı."
        override val notEnoughConcepts = "Soru üretmek için yeterli kavram yok"
        override val storySourceUnverified = "Hikâyenin kitap kaynağı doğrulanamadı; üretilmiş örnek olarak işaretlendi."
        override fun quoteRemoved(term: String) = "“$term” için verilen alıntı kitapta bulunamadı; alıntı kaldırıldı."
        override fun sourceChapterMissing(n: Int) = "Kaynak bölüm $n bulunamadı."
        override val quoteUnverified = "Bir alıntı kitapta doğrulanamadı."
        override fun questionSkipped(issue: String) = "Soru atlandı: $issue"
        override val questionQuoteUnverified = "Bir sorunun kaynak alıntısı doğrulanamadı."
        override val unknownLinkSkipped = "Bilinmeyen kavramlara dayanan bir ilişki atlandı."
        override fun part(n: Int) = "Kısım $n"
        override val untitledBook = "Adsız kitap"
        override val ocrWarning = "Metin OCR ile çıkarıldı; tanıma hataları olabilir."
        override fun onlyFirstPages(n: Int) = "Yalnızca ilk $n sayfa işlendi."
        override val needTwoBooks = "En az iki kitapta kavram olmalı."
        override fun rememberHighlight(quote: String) = "Bu alıntıyla ilgili notunu hatırla:\n“$quote”"
        override fun yourNote(line: String) = "Notun: $line …"
        override fun couldNotGrade(reason: String) = "Değerlendirilemedi: $reason"

        override fun chaptersFailed(n: Int) = "$n bölüm analiz edilemedi."
        override val quizWrongOnly = "yanlışlar"
        override fun unsupportedClaims(items: String) = "Kitapta geçmeyen ifadeler: $items. Bunlar modelin çıkarımı ya da hatası olabilir."
        override val answerFromBook = "Kitapta sorunla en çok örtüşen cümleler:"
        override val answerNotFound = "Bu sorunun cevabını kitapta bulamadım. Farklı kelimelerle sormayı dene."
        override val answerUncited = "Cevap kitaptaki bir bölüme dayandırılamadı; dikkatle değerlendir."
        override val quizNearForgetting = "unutulmaya yakın"
        override val quizMixed = "karışık tekrar"
        override val quizFullBook = "tüm kitap"
        override fun aiError(e: AiException) = when (e) {
            is AiException.NotConfigured -> "Telefondaki yapay zekâ modeli henüz indirilmedi."
            is AiException.Network -> "Bağlantı kurulamadı."
            is AiException.Timeout -> "İstek zaman aşımına uğradı. Tekrar dene."
            is AiException.QuotaExceeded -> "Günlük yapay zekâ kullanım sınırına ulaşıldı."
            is AiException.Unauthorized -> "Oturumun sona erdi; yeniden giriş yap."
            is AiException.Server -> if (e.code == 507) "Telefonda yeterli boş bellek yok; açık uygulamaları kapatıp yeniden dene." else "Yapay zekâ modeli yanıt veremedi (${e.code})."
            is AiException.InvalidOutput -> "İçerik oluşturulamadı: ${e.message?.substringAfter(": ").orEmpty()}"
            else -> "Bir sorun oluştu."
        }

        override val opening = "Başlangıç"
        override val introduction = "Giriş"
        override fun chapter(n: Int) = "Bölüm $n"
        override fun pages(from: Int, to: Int) = "Sayfa $from–$to"
        override fun line(n: Int) = "satır $n"
        override fun page(n: Int) = "s. $n"
        override val pageAbbrev = "s."
        override val noHeadings = "Bölüm başlıkları bulunamadı; metin eşit büyüklükte kısımlara ayrıldı."
        override val pdfScanned = "PDF taranmış görünüyor; metin çıkarmak için OCR gerekli."
        override val pdfNoOutline = "PDF içinde içindekiler/başlık bulunamadı; metin yaklaşık 6.500 kelimelik bölümlere ayrıldı."
        override fun backMatterRemoved(pages: Int) = "İçindekiler, dizin ve kaynakça sayfaları ayıklandı ($pages sayfa)."
        override fun backMatterChapters(count: Int) = "İçindekiler, dizin veya kaynakça bölümü ayıklandı ($count)."
        override fun epubMissing(href: String) = "Eksik içerik: $href"
        override val epubNoText = "EPUB içinde okunabilir metin bulunamadı."
    }

    object En : CoreText() {
        override val localNote = "Created on this device by selecting sentences from the book (no AI was used). Turn on the AI provider for an interpretive summary."
        override val keyIdea = "Key idea"
        override val highlights = "Highlights"
        override val keyConcepts = "Key concepts"
        override fun quickTitle(book: String) = "$book – Quick summary"
        override fun chapterSummariesTitle(book: String) = "$book – Chapter summaries"
        override val executiveLocalNote = "An executive summary (problem, thesis, solution, evidence) needs interpretation; on-device mode shows chapter summaries."
        override fun keyIdeasTitle(book: String) = "$book – Key ideas"
        override val coOccurs = "appear together"
        override fun conceptMapTitle(book: String) = "$book – Concept map"
        override val conceptMapNote = "Links are based on concepts appearing in the same sentences; they do not imply cause and effect."
        override val sharedConceptsBody = "These chapters focus on shared concepts."
        override fun comparisonTitle(book: String) = "$book – Chapter comparison"
        override val noSharedTitle = "No shared concepts found"
        override val noSharedBody = "No clear shared concepts were detected between chapters."
        override val comparisonNote = "Deciding whether chapters support or contradict each other needs interpretation; on-device mode only shows shared concepts."

        override fun mcqPrompt(term: String) = "Which of the following matches the book's explanation of “$term”?"
        override fun inBook(explanation: String) = "In the book: $explanation"
        override fun blankAnswer(answer: String) = "The blank is “$answer”."
        override fun podcastWelcome(topic: String) = "Hello and welcome! Today we're talking about “$topic”. What is it about, in short?"
        override fun podcastAskTerm(term: String) = "So what exactly does “$term” mean?"
        override val podcastWhyMatters = "Why does that matter? Can you say a bit more?"
        override val podcastClosing = "To wrap up, what should our listeners remember?"
        override fun flashFront(term: String) = "What is “$term”?"
        override fun gapFromEarlier(chapterNumber: Int) = "Used in this chapter but explained in chapter $chapterNumber."
        override val gapCore = "A cornerstone of this chapter; the other ideas build on it."
        override val gapMistake = "You answered a question on this wrong or left it empty."
        override fun examExplain(term: String) = "Explain “$term” in your own words."
        override fun examCompare(a: String, b: String) = "Explain the relationship or the difference between “$a” and “$b”."
        override fun examApply(term: String) = "How would you apply “$term” in everyday life? Give an example."
        override fun oralFollowUp(point: String) = "One thing is missing: can you also explain “$point”?"
        override val oralGood = "Good, you covered the main points."
        override val oralPartial = "Partly right, but some important points are missing."
        override val oralMissed = "This answer misses the core of the question."
        override fun falseBecause(term: String, sentence: String) = "False. In the book this statement is about “$term”: $sentence"
        override val trueInBook = "True; this statement appears in the book."
        override fun explainOwnWords(term: String) = "Explain “$term” in your own words."
        override fun bookExplanation(explanation: String) = "The book's explanation: $explanation"
        override fun define(term: String) = "Define: $term"
        override val matchPrompt = "Match each concept with its explanation."
        override fun orderPrompt(chapter: String) = "Put these statements in the order they appear in “$chapter”."
        override val orderExplanation = "The correct order follows the book."
        override val thisConcept = "this concept"
        override fun whatIs(term: String) = "What is “$term”? Explain in your own words."

        override val dontKnowAnswers = setOf("i don't know", "i dont know", "dont know", "?")
        override val noProblem = "No problem; let's look at it together."
        override val noAnswer = "No answer given."
        override val correct = "Correct."
        override val matchedAcceptable = "Matched one of the accepted answers."
        override val coversMainPoints = "You cover the main points."
        override val partlyCorrect = "Partly correct; some points are missing."
        override val missesMainPoints = "The answer does not cover the main points of the book's explanation."
        override fun keywordRationale(covered: Int, total: Int) = "Approximate grading by keyword coverage ($covered/$total points)."
        override val generallyCorrect = "Your answer is broadly correct."
        override val incompleteOrWrong = "The answer is incomplete or incorrect."
        override val orderCorrect = "The order is correct."
        override fun correctOrder(order: String) = "Correct order: $order"
        override val allMatchesCorrect = "All matches are correct."
        override fun matchesCorrect(right: Int, total: Int) = "$right/$total matches correct."
        override fun correctAnswer(answer: String) = "Correct answer: $answer"
        override fun emptyAnswer(answer: String) = "No answer. Correct answer: $answer"
        override val trueWord = "True"
        override val falseWord = "False"

        override fun teachStart(term: String) = "Let's start with “$term”. First, let's see what you already know."
        override fun teachDiagnose(term: String) = "What does “$term” mean to you? Answer in a sentence or two (you can write “I don't know”)."
        override val styleNeedsAi = "\n\n(Analogies and debates need the AI provider; for now I'm showing an example from the book.)"
        override val knowsBasics = "Nice, you know the basic idea. "
        override val stepByStep = "No problem, let's go step by step. "
        override fun bookExplains(explanation: String) = "The book explains it like this:\n\n“$explanation”"
        override fun bookExample(sentence: String) = "\n\nAn example from the book:\n“$sentence”"
        override fun fillBlank(sentence: String) = "Fill in the blank: $sentence"
        override val keyWordQuestion = "Which word in this explanation matters most to you, and why?"
        override val correctClap = "Correct! 👏"
        override fun notQuite(expected: String) = "Not quite. The blank should be “$expected”, because the sentence rests on this concept's definition."
        override fun anotherSentence(sentence: String) = "\n\nLet's look at it again in another sentence:\n“$sentence”"
        override val applyQuestion = "Where could you use this concept in your daily life or work? Describe a concrete situation."
        override val goodApplication = "That's a good way to put the concept into practice."
        override val beConcrete = "Try to be a bit more concrete: who, where, when?"
        override fun explainToNovice(term: String) = "Finally: how would you explain “$term” in your own words to someone who has never heard of it?"
        override val explainedWell = "Great, you explained the concept in your own words. "
        override fun missedPoints(points: String) = "Your explanation misses a few points: $points. "
        override val addedToReview = "I added this concept to your review queue; I'll ask again based on your forgetting curve."

        override fun bookExamplesTitle(term: String) = "Examples from the book: $term"
        override fun howAppears(term: String) = "How does “$term” show up in these examples?"
        override val sameName = "Appears under the same name in both books."
        override val namesOverlap = "The names largely overlap; the link was estimated on this device."

        override val dailyReview = "Daily review"
        override fun learn(chapter: String) = "Learn: $chapter"
        override fun quiz(chapter: String) = "Quiz: $chapter"
        override val finalCheckpoint = "End-of-book checkpoint"
        override val checkpoint = "Checkpoint"
        override val practiceTask = "Real-life practice task"
        override val reviewWeakChapter = "Review the chapter you found hard"

        override val quotaReached = "Daily AI usage limit reached; the on-device method was used."
        override fun aiFailed(reason: String) = "No AI response ($reason); the classic method was used."
        override val notEnoughConcepts = "Not enough concepts to create questions"
        override val storySourceUnverified = "The story's source in the book could not be verified; it is marked as a generated example."
        override fun quoteRemoved(term: String) = "The quote given for “$term” was not found in the book and was removed."
        override fun sourceChapterMissing(n: Int) = "Source chapter $n was not found."
        override val quoteUnverified = "A quote could not be verified in the book."
        override fun questionSkipped(issue: String) = "Question skipped: $issue"
        override val questionQuoteUnverified = "A question's source quote could not be verified."
        override val unknownLinkSkipped = "A link based on unknown concepts was skipped."
        override fun part(n: Int) = "Part $n"
        override val untitledBook = "Untitled book"
        override val ocrWarning = "Text was extracted with OCR; there may be recognition errors."
        override fun onlyFirstPages(n: Int) = "Only the first $n pages were processed."
        override val needTwoBooks = "At least two books need concepts."
        override fun rememberHighlight(quote: String) = "Recall your note on this quote:\n“$quote”"
        override fun yourNote(line: String) = "Your note: $line …"
        override fun couldNotGrade(reason: String) = "Could not be graded: $reason"

        override val quizWrongOnly = "mistakes"
        override fun unsupportedClaims(items: String) = "Not found in the book: $items. These may be the model's inference or a mistake."
        override val answerFromBook = "The sentences in the book that best match your question:"
        override val answerNotFound = "I couldn't find the answer in the book. Try asking with different words."
        override val answerUncited = "The answer could not be tied to a passage in the book; treat it with care."
        override val quizNearForgetting = "about to be forgotten"
        override val quizMixed = "mixed review"
        override val quizFullBook = "whole book"
        override fun chaptersFailed(n: Int) = if (n == 1) "1 chapter could not be analysed." else "$n chapters could not be analysed."
        override fun aiError(e: AiException) = when (e) {
            is AiException.NotConfigured -> "The on-device AI model hasn't been downloaded yet."
            is AiException.Network -> "Couldn't connect."
            is AiException.Timeout -> "The request timed out. Try again."
            is AiException.QuotaExceeded -> "Daily AI usage limit reached."
            is AiException.Unauthorized -> "Your session expired; please sign in again."
            is AiException.Server -> if (e.code == 507) "Not enough free memory on the phone; close other apps and try again." else "The AI model couldn't respond (${e.code})."
            is AiException.InvalidOutput -> "Content couldn't be created: ${e.message?.substringAfter(": ").orEmpty()}"
            else -> "Something went wrong."
        }

        override val opening = "Opening"
        override val introduction = "Introduction"
        override fun chapter(n: Int) = "Chapter $n"
        override fun pages(from: Int, to: Int) = "Pages $from–$to"
        override fun line(n: Int) = "line $n"
        override fun page(n: Int) = "p. $n"
        override val pageAbbrev = "p."
        override val noHeadings = "No chapter headings were found; the text was split into equal parts."
        override val pdfScanned = "The PDF looks scanned; OCR is needed to extract text."
        override val pdfNoOutline = "The PDF has no outline or headings; the text was split into sections of about 6,500 words."
        override fun backMatterRemoved(pages: Int) = "Contents, index and bibliography pages were left out ($pages pages)."
        override fun backMatterChapters(count: Int) = "A contents, index or bibliography section was left out ($count)."
        override fun epubMissing(href: String) = "Missing content: $href"
        override val epubNoText = "No readable text was found in the EPUB."
    }
}
