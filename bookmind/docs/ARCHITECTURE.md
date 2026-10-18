# Mimari

## Genel bakış

```
┌──────────────────────────── Android uygulaması (app) ─────────────────────────────┐
│ Compose UI (Material 3, Navigation Compose)                                        │
│   ↕ ViewModel (StateFlow, viewModelScope)                                          │
│ Repository katmanı: Library, Study, Quiz, Review, Note, Insights, Graph, Plan,     │
│                     Account, Settings(DataStore), User                             │
│   ↕                         ↕                         ↕                            │
│ Room (SQLite, FK + index)  BookFileStore (app-private) WorkManager                 │
│   ↑ SQLite tetikleyicileri → sync_outbox          (işleme, hatırlatma, senkron)     │
└──────────────┬───────────────────────────────────────────┬─────────────────────────┘
               │ core (saf Kotlin, JVM)                    │ HTTPS (kullanıcı JWT'si)
               │  srs/ text/ ai/ quiz/ learning/ sync/     ▼
               │                                 Supabase: Postgres (RLS), Auth, Storage,
               │                                 Edge Functions: ai → Claude API, delete-account
```

**Neden iki modül?** Öğrenme mantığının tamamı (FSRS, ayrıştırıcılar, AI doğrulama, puanlama, plan, hatırlatma
hesabı, analitik, Supabase REST istemcisi) Android'den bağımsız `core` modülündedir. Bu sayede saniyeler içinde
çalışan saf JVM birim testleriyle doğrulanır ve UI/depolama değişikliklerinden etkilenmez (Clean Architecture'ın
"domain" katmanına karşılık gelir). `app` modülü yalnızca platform işlerini (Room, WorkManager, dosya, UI, Hilt)
yapar.

## Katmanlar

| Katman | Sorumluluk | Örnek sınıflar |
|---|---|---|
| UI | Durum gösterimi, kullanıcı etkileşimi; iş mantığı yok | `ui/**/…Screen.kt`, `BookMindApp` (nav) |
| ViewModel | Ekran durumu, coroutine kapsamı; gezinme olayları durum olarak yayınlanır | `HomeViewModel`, `QuizSessionViewModel` |
| Repository | İş akışları, işlem (transaction) sınırları, AI çağrıları | `QuizRepository`, `ReviewRepository` |
| Veri | Room DAO'ları, dosya deposu, DataStore, Supabase istemcisi | `BookMindDatabase`, `BookFileStore` |
| Core | Saf algoritmalar ve sözleşmeler | `FsrsScheduler`, `LearningAi`, `EpubParser` |

Önemli kural: gezinme (navigate) yalnızca ana iş parçacığında, `LaunchedEffect` içinde ViewModel durumuna tepki
olarak yapılır. (Composition kapsamındaki coroutine'ler testlerde arka plan iş parçacığında devam edebildiği için
bir hata bu kurala göre düzeltildi.)

## Kitap işleme hattı

```
URI/metin ─► BookFileStore.import (akış, SHA-256, sihirli bayt, 150 MB) ─► books + book_sources
        └► WorkScheduler.processBook (benzersiz iş, ön planda, depolama kısıtı)
             └► BookProcessor.process
                  1. extract: TextExtractor (PDF/EPUB/TXT/MD; OCR isteğe bağlı)
                     → bölümler dosyaya (atomik yazma), chapters satırları (deterministik id "<book>:<i>")
                     → aynı içerik (contentHash) varsa önceki analiz korunur
                  2. analyzeBook (tür, ana fikir, hedefler, ön koşullar)
                  3. her bölüm: Chunker → LearningAi.analyzeChapter → birleştirme → tek transaction'da
                     kavramlar + tekrar kartları + bölüm READY
             Hata: bölüm FAILED, kitap PARTIAL; iptal: durum korunur, sonraki çalıştırma kaldığı yerden sürer.
```

- **Devam edebilirlik:** her bölüm kendi transaction'ında tamamlanır. Uygulama yeniden başlarken
  `BookMindApp.onCreate` yarım kalan kitapları tekrar kuyruğa alır.
- **Tekrarsızlık:** kartlar `dedupeKey` (`concept:<book>:<normalize başlık>`) ile benzersizdir; yeniden analizde
  mevcut kart (ve geçmişi) yeni kavram satırına bağlanır.
- **Büyük dosyalar:** PDF'ler PdfBox'ta geçici dosya destekli bellekle, EPUB'lar giriş giriş okunur; içe aktarma
  akış halinde kopyalanır.

## Yapay zekâ katmanı

`LearningAi` tek giriş noktasıdır: sağlayıcı seçimi (yerel / uzak / mock), kota, önbellek, yeniden deneme, JSON
çıkarma ve doğrulama, yerel motora geri düşme. Ayrıntı: [AI_PIPELINE.md](AI_PIPELINE.md).

## Senkronizasyon (yerel öncelikli)

- Her senkronize tabloda `AFTER INSERT/UPDATE/DELETE` SQLite tetikleyicileri `sync_outbox`'a satır ekler
  (`SyncTriggers`). Böylece FK kademeli silmeleri dahil hiçbir değişiklik kaçmaz.
- `SyncWorker` (6 saatte bir + istek üzerine, ağ kısıtıyla) → `SyncEngine.sync()`:
  - **Push:** outbox sırayla; tablolar FK sırasında; satırlar SQLite'tan genel olarak okunup snake_case JSON'a
    çevrilir; `user_id` gönderilmez (sunucu JWT'den doldurur). Kitap dosyaları özel Storage kovasına yüklenir.
  - **Pull:** tablo başına sunucu zamanı imleçleri (`server_updated_at`), 5 dk örtüşme; değişebilir satırlar
    last-writer-wins (`updatedAt`/`deletedAt`), tekrar durumları "daha çok tekrar görmüş taraf", ekleme-yalnız
    geçmiş (tekrar kayıtları, cevaplar, XP) birleşim. Çekilen satırların tetikleyici kayıtları aynı transaction'da
    silinir (yankı yok).
  - Giriş yapınca tüm yerel kütüphane FK sırasıyla kuyruğa alınır (`enqueueSnapshot`).
  - Başka cihazdan gelen kitapların metni, indirilen kaynak dosyadan yeniden çıkarılır; analiz yeniden yapılmaz.
- Silinen kitaplar `deletedAt` tombstone ile diğer cihazlara yayılır.

## Bildirimler

`ReminderScheduler` (saf fonksiyon) kullanıcının yerel saatine, seçili günlere ve tatil moduna göre bir sonraki
zamanı hesaplar (yaz saati boşluklarını ileri kaydırır). `ReminderWorker` çalışınca ne söyleneceğine karar verir
(bugünkü/gecikmiş tekrar, hedef, devam eden kitap) ve kendini yeniden planlar. Saat dilimi değişince
(`ACTION_TIMEZONE_CHANGED`) yeniden hesaplanır. Android'in Doze/pil kısıtları nedeniyle dakika garantisi verilmez.

## Bağımlılık enjeksiyonu

Hilt: `AppModule` (Room, OkHttp, BackendConfig, WorkManager), `AiModule` (`LearningAi`: sağlayıcı ayara göre her
çağrıda seçilir; önbellek ve kota Room'da), `BindingsModule` (saat, oturum deposu). Worker'lar `@HiltWorker`.
Testlerde `@TestInstallIn` ile bellek içi veritabanı kullanılır.

## Teknik kararlar

| Karar | Gerekçe |
|---|---|
| FSRS-4.5'i formüllerden yazmak (kütüphane yerine) | Yayımlanmış formüller kısa ve iyi tanımlı; JVM için bakımı yapılan resmi bir Kotlin paketi yok. Saf ve test edilebilir; algoritma arayüzün arkasında, SM-2 alternatifiyle. |
| ML Kit (cihaz üzerinde) OCR, Tesseract değil | tess-two/Tesseract Android sarmalayıcıları bakımsız ve büyük; ML Kit Latin modeli Türkçe karakterleri tanır, ağ gerektirmez. Kapalı kaynak lisans şartları belgelendi. |
| PdfBox-Android | Apache 2.0, saf Java, metin + anahat (outline) okuma; Android'in PdfRenderer'ı yalnızca görüntü verir (OCR için kullanılıyor). |
| Supabase | Postgres + RLS + Auth + Storage + Edge Functions tek yerde; RLS veritabanı düzeyinde yetkilendirme sağlar. |
| Room şemasından üretilen sunucu şeması | İstemci-sunucu sütun uyumsuzluğu imkânsızlaşır; CI denetler. |
| Tetikleyici tabanlı outbox | Elle kayıt unutulduğunda sessiz veri kaybı yerine veritabanı garantisi. |
| Bulut AI isteğe bağlı, varsayılan yerel motor | Gizlilik, maliyet ve çevrimdışı çalışma; yerel motor uydurmaz, yalnızca kitaptan seçer ve etiketlenir. |
| Hikâye/benzetme gibi yaratıcı içerik yalnızca AI ile | Yerel motor bunları üretemez; dürüstçe "kitaptan örnek" gösterir. |
