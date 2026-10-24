# Gemini ile kitap özeti

BookMind'ın yapay zekâsı artık **Google Gemini API** üzerinden çalışır; telefona model indiren eski cihaz içi
(LiteRT-LM) yapı kaldırıldı. Anahtarı olmayan bir derleme ya da yapay zekâsı kapatılmış uygulama her şeyi eskisi
gibi telefonda, klasik yöntemlerle (TextRank, BM25, kural tabanlı soru üretimi) yapar.

## Kurulum

1. [Google AI Studio](https://aistudio.google.com/apikey)'dan bir API anahtarı al.
2. Anahtarı **koda değil**, şu iki yerden birine koy (ikisi de git'e girmez):
   - `bookmind/local.properties` içine: `gemini.apiKey=AIza...` (`local.properties` `.gitignore`'da)
   - veya ortam değişkeni olarak: `export GEMINI_API_KEY=AIza...`
3. Uygulamayı derle: `./gradlew :app:assembleRelease`. Anahtar derleme sırasında `BuildConfig.GEMINI_API_KEY`'e
   yazılır.

Anahtarsız derleme sorunsuz çalışır: Profil → Yapay zekâ asistanı "Bu sürümde Gemini anahtarı yok" der, kitap
sayfasındaki özet kartı da bunu açıklar.

## Yapılandırma: `bookmind/ai-config.properties`

Model adı ve özet oranları tek dosyada; değiştirip yeniden derlemen yeterli (yanlış değerde derleme hata verir):

| Ayar | Varsayılan | Anlamı |
|---|---|---|
| `gemini.model` | `gemini-3.8-flash` | Kullanılan model. [Resmi model listesine](https://ai.google.dev/gemini-api/docs/models) göre (Ekim 2026) en yeni kararlı Flash modeli; daha ucuzu `gemini-3.5-flash-lite`. |
| `gemini.thinkingLevel` | `low` | Modelin cevap öncesi "düşünme" düzeyi: `low`, `medium`, `high` (3.8 Flash'ta `minimal` yok). Düşünme token'ları çıktı olarak ücretlendirilir. |
| `gemini.maxRetries` | `6` | 429 / 5xx / ağ hatasında üstel bekleme ile yeniden deneme sayısı (arka plan özeti). |
| `summary.chapterRatio` | `0.10` | Bölüm özeti, bölümün yaklaşık bu oranı kadar (%10). |
| `summary.overallMinWords` / `overallMaxWords` | `2000` / `4000` | Genel özetin kelime aralığı. |
| `summary.chunkMinWords` / `chunkMaxWords` | `5000` / `8000` | Kitabın API'ye kaç kelimelik parçalarla gönderileceği. |

## Özet nasıl çıkarılır

1. **Kitap yükleme** (değişmedi, eksikleri tamamlandı): telefondan dosya seçici (Storage Access Framework) ile PDF,
   EPUB, TXT veya Markdown seçilir.
   - **PDF:** metin cihazda PdfBox-Android ile sayfa sayfa çıkarılır. Bölümler önce PDF'in içindekiler ağacından, yoksa
     sayfa başındaki "Bölüm 3 / Chapter III" gibi başlıklardan alınır. Hiç başlık yoksa metin **yaklaşık 6.500 kelimelik
     eşit bölümlere** ayrılır (5.000–8.000 aralığında).
   - **EPUB:** zip olarak açılır; `container.xml` → OPF → spine sırası. Her HTML belgesi temiz metne çevrilir, bölüm
     başlıkları EPUB'ın kendi içindekiler listesinden (nav/NCX) gelir.
   - **Ayıklananlar:** her sayfada tekrar eden üst/alt bilgi ve sayfa numaraları; **içindekiler, dizin ve kaynakça**
     (başlığından — "İçindekiler", "Dizin", "Kaynakça", "Contents", "Index", "References" … — ya da satır yapısından:
     sayfa numarasıyla biten satırlar, "terim, 12, 45" satırları, "Soyad, A. (1999)" kaynakları). Yapıya bakarak
     ayıklama yalnızca kitabın başında/sonunda yapılır; EPUB'da kitabın kendi `guide`/`landmarks` işaretleri de kullanılır.
   - **Taranmış PDF:** sayfa başına çok az metin çıkarsa kitap "OCR gerekli" olarak işaretlenir ve kullanıcıya
     bildirilir; kitap sayfasından cihaz içi ML Kit OCR başlatılabilir.
   - Büyük dosyalar WorkManager ile arka planda (ön plan bildirimiyle) işlenir; ilerleme çubuğu metin çıkarımını da
     gösterir. Uygulama kapanırsa iş kaldığı bölümden devam eder.
2. **Parçalama** (`ChunkPlanner`): bölüm sınırları korunur. 8.000 kelimeden uzun bölüm paragraf (gerekirse cümle)
   sınırından eşit parçalara bölünür; kısa ardışık bölümler aynı isteğe girer ama her biri ayrı özetlenir.
3. **Bölüm özetleri:** her istek, parçadaki her bölüm için yaklaşık %10 uzunlukta, ana fikirleri, önemli örnekleri ve
   kavramları (kısa tanımlarıyla) koruyan bir Markdown özet ister. Metin **PDF olarak değil, düz metin olarak**
   gönderilir ve "veri" olarak işaretlenir; içindeki talimatlar dikkate alınmaz. Eksik kalan bölüm için bir kez daha
   istenir.
4. **Genel özet:** bölüm özetleri sırayla birleştirilip 2.000–4.000 kelimelik tek bir özet yazdırılır (uzun kitaplar
   üst sınıra yaklaşır; özeti 2.000 kelimeden kısa olacak kadar kısa kitaplar orantılı olarak daha kısa bir özet alır).
   Çok kısa ya da kesik gelirse bir kez yeniden istenir.
5. **Kayıt:** sonuç telefonda `files/book-summaries/<kitap metninin SHA-256'sı>.json` olarak saklanır ve **her istekten
   sonra** güncellenir. Aynı metin bir daha asla gönderilmez — kitap silinip yeniden eklense bile. "Tüm verilerimi sil"
   bu dosyaları da siler; "Yeniden özetle" eskisini bilerek atar.

Yeni eklenen kitap işlendikten sonra özet kendiliğinden kuyruğa girer (internet bağlantısı gerektirir). Kitap
sayfasındaki kart ilerlemeyi ("Bölümler özetleniyor: 3 / 12"), limit beklemesini ve hataları gösterir; hazır bölüm
özetleri, genel özet bitmeden de okunabilir.

## Hata ve limit (429) davranışı

- **429 (hız sınırı), 408, 5xx, ağ hatası:** üstel bekleme (2 sn, 4 sn, 8 sn … en fazla 2 dk, %25 rastgele sapma) ile
  yeniden denenir; sunucu `RetryInfo.retryDelay` ya da `Retry-After` gönderirse o süre beklenir.
- **Günlük kota bitti** (`…PerDay…`): boşuna denenmez; kota Pasifik saatiyle gece yarısı sıfırlandığından iş o saate
  ertelenir, kartta "05:55 civarında kaldığı yerden devam edecek" yazar.
- **Geçersiz anahtar (401/403), hatalı istek (400):** yeniden denenmez, kartta açıklanır.
- Deneme hakları biterse iş WorkManager'ın üstel geri çekilmesiyle (1 dk'dan başlayarak) en fazla 5 kez daha
  çalıştırılır; her seferinde kaldığı yerden devam eder.
- Etkileşimli özellikler (kitaba sor, öğretmen, quiz, ders modülleri) Gemini'yi kısa yeniden denemelerle kullanır;
  ulaşılamazsa sonuç klasik yöntemle gelir ve uyarıyla etiketlenir. İçe aktarma sırasındaki bölüm bölüm kavram
  çıkarımı, her parça için ayrı istek yapmamak üzere klasik yöntemde kalır.

## Gizlilik ve kullanım şartları

- Gemini açıkken özetlenen bölümlerin metni ve sorulan sorular Google'a gönderilir. Uygulama bunu Yapay zekâ
  asistanı ekranında ve özet kartında söyler; yapay zekâ kapatılırsa hiçbir kitap metni telefondan çıkmaz.
- [Gemini API şartları](https://ai.google.dev/gemini-api/terms): **ücretsiz kotada** Google gönderilen içeriği
  ürünlerini geliştirmek için kullanabilir ve insanlar inceleyebilir; **ücretli kotada** kullanmaz, yalnızca kötüye
  kullanımı tespit için sınırlı süre saklar. API'yi kullananların **18 yaşından büyük** olması gerekir; **AB, İsviçre
  ve Birleşik Krallık'taki** kullanıcılara sunulan uygulamalar yalnızca ücretli kotayı kullanabilir. Uygulamayı
  dağıtmadan önce bunlara göre karar ver.
- `generateContent` durumsuz kullanılır (konuşma sunucuda tutulmaz).

## Güvenlik: anahtar APK'nın içindedir

`local.properties`'teki anahtar git'e girmez ama derlenen APK'nın içine yazılır; APK'yı açan biri onu çıkarabilir.
Bunu sınırlamak için:

- Google Cloud Console → API anahtarı → **Uygulama kısıtlaması: Android uygulamaları**, paket adı
  `com.bookmind.app` ve imzalama sertifikasının SHA-1'i. Uygulama her istekle `X-Android-Package` ve
  `X-Android-Cert` başlıklarını gönderir; Google'ın API anahtarı belgelerine göre kısıtlı anahtarlar bu başlıkları
  ister, kısıtlamayı ekledikten sonra gerçek bir cihazla denemek gerekir.
- **API kısıtlaması:** anahtarı yalnızca Generative Language API ile sınırla; AI Studio'da harcama/kota sınırı koy.
- Herkese dağıtılacak bir sürümde en güvenli yol anahtarı sunucuda tutmaktır: projedeki isteğe bağlı Supabase Edge
  Function vekili (`bookmind.enableCloudAi=true`) bunun için hazır.

## Test

- `GeminiClientTest` (yerel sahte sunucu, MockWebServer): istek biçimi (anahtar başlıkta, URL'de değil; sıcaklık yok;
  `thinkingLevel`; JSON modu), düşünce parçalarının cevaptan ayrılması, 429'da `retryDelay`/`Retry-After`/üstel
  bekleme, deneme sınırı, günlük kotanın beklenmeden bildirilmesi ve sıfırlanma saati, 400/403/engellenen/boş cevap.
- `BookSummarizerTest`: 5.000–8.000 kelimelik parçalar, uzun bölümün eşit bölünmesi, paragrafsız metin, bölüm başına
  ~%10, 2.000–4.000 kelimelik genel özet, kısa kitapta orantılı özet, yarıda kesilip devam, aynı kitabın yeniden
  işlenmemesi, eksik bölümün yeniden istenmesi, bozuk cevapta boşluklu kayıt yapılmaması, kitaptaki talimat enjeksiyonu.
- `BackMatterFilterTest`: içindekiler/dizin/kaynakça ayıklama (PDF sayfaları, PDF ana hatları, Markdown, EPUB
  landmarks), ortadaki yıllı tarih sayfasının korunması, başlıksız PDF'in 5.000–8.000 kelimelik bölümleri.
- `BookSummaryTest` / `BookSummaryUiTest` (Robolectric): kayıt, silinip yeniden eklenen kitabın gönderilmemesi, kota
  beklemesi ve devam, hatalar, anahtarsız/kapalı durum, arka plan kuyruğu, kart ve okuma ekranı.
- **Gerçek Gemini API ile çalıştırılmadı:** bu oturumda API anahtarı yoktu; istek ve cevap biçimi resmi belgelere
  göre yazıldı ve sahte sunucuyla test edildi. İlk gerçek denemede bir kitabı özetleyip sonucu kontrol et.
