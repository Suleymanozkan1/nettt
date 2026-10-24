# Test raporu

Tarih: 2026-10-24. Ortam: Linux, JDK 21, Android SDK 36, Robolectric 4.16 (API 35/32), PostgreSQL 16.15,
Deno 2.9.6. Aynı paketler GitHub Actions'ta (`.github/workflows/bookmind.yml`) her push'ta çalışır.

## Özet

| Paket | Komut | Test | Sonuç |
|---|---|---|---|
| Core birim testleri | `./gradlew :core:test` | 130 | ✅ 130/130 |
| Uygulama (Robolectric: Room, hat, depolar, senkron, Compose UI) | `./gradlew :app:testDebugUnitTest` | 52 (+5 isteğe bağlı) | ✅ 52/52 |
| SQL migration + RLS | `bash scripts/test-supabase-sql.sh` | 9 senaryo grubu | ✅ |
| Edge Functions | `deno test --allow-env tests/` (+ `deno check`) | 8 | ✅ 8/8 |
| Android lint | `./gradlew :app:lintDebug` | — | ✅ 0 hata, 70 uyarı (aşağıda) |
| Derleme | `assembleDebug`, `assembleRelease` (R8, işlemci türü başına APK + evrensel) | — | ✅ arm64 release 23 MB, evrensel 53 MB (cihaz içi model motoru kaldırıldı) |
| Gerçek Gemini API | — | — | ⚠️ çalıştırılmadı (bu ortamda API anahtarı yok); istemci sahte sunucuyla test edildi, bkz. [GEMINI.md](GEMINI.md#test) |
| Emülatörde release APK duman testi | `scripts/android-smoke.sh` (CI, API 34) | 1 akış | bkz. "Cihaz testi" |

## Birim testleri (core, 130)

| Sınıf | Adet | Kapsam |
|---|---|---|
| `FsrsSchedulerTest` | 10 | Yeni kart aralıkları (Good=4 g, Easy=14 g), R(S,S)=0,9, ardışık iyi tekrarla büyüyen aralık, unutmada kararlılık düşüşü + lapse, zorluk sınırları, geç tekrarın daha çok güçlendirmesi, hedef oranın aralığa etkisi, deterministik fuzz, önizleme, yeni kartta R yok |
| `Sm2AndResolverTest` | 6 | SM-2 1→6→15 ilerlemesi ve unutma; derece birleştirme: yanlış=AGAIN, doğru+“hatırlayamadım”=HARD, ipucu/kısmi puan sınırı, yavaş cevap, öz-değerlendirmeli kartlar |
| `OutputLanguageTest` | 4 | İngilizce demo kitaptan İngilizce kavramlar; yerel özet, soru, öğretmen, notlandırma, plan, ayrıştırıcı yedek başlıkları ve kaynak etiketlerinin seçilen dili izlemesi; Türkçenin varsayılan kalması |
| `ParsersTest` | 17 | Markdown bölümleri, satır içi biçim temizleme, TR/EN başlık tespiti ve içindekiler eleme, başlıksız metinde sentetik bölümler, PDF anahattı/başlık sayfaları/taranmış PDF (OCR gerekli)/tire birleştirme/tekrarlayan üst bilgi, cümle bölme (kısaltma, ondalık), parçalama ofsetleri, sihirli bayt tespiti, UTF-8 BOM ve Windows-1254, Türkçe büyük/küçük harf, dil tespiti, SHA-256, kaynak etiketi |
| `EpubParserTest` | 5 | Meta veri, spine sırası, nav başlıkları, NCX/başlık geri düşmesi, iç içe öğe tekrarlamama, script temizliği, DRM reddi, eksik container, yol normalleştirme |
| `FreeAiTest` | 9 | Bağlam bütçesine göre kırpma, model hatasında uyarılı klasik yönteme düşme, kesilen JSON'un kurtarılması, gevşek adlandırılmış soru türleri, BM25 (Türkçe ekler), çıkarımsal cevap, sadakat denetimi, TextRank |
| `AiPipelineTest` | 13 | JSON çıkarma (çit, metin, dize içi parantez, kesik), bozuk çıktı → InvalidOutput, enjeksiyon etiketlerinin etkisizleşmesi, geçersiz/belirsiz soruların ayıklanması, uydurma alıntının silinmesi, puan sıkıştırma, mock ile uçtan uca, önbellek, geçici hatada yeniden deneme, geçersiz JSON'da düzeltici deneme, kalıcı hatada yerel motora düşme, yeniden denenmeyen hatalar, kota/sağlayıcı yokken yerel motor, özet kaynak doğrulaması |
| `LocalEngineTest` | 12 | Tanım kalıplarından kavramlar (5 bölümün hepsi), "denir" kalıbı, anahtar cümleler, üretilen soruların yapısal geçerliliği ve kendi anahtarıyla doğru cevaplanması, tohum determinizmi, sezgisel puanlama, kaynaklı ve etiketli özetler, özetin kaynağa göre kısalığı (telif), öğretmen akışı, tür tahmini, kitaplar arası bağlar |
| `RemoteAiProviderTest` | 4 | Oturum jetonu gönderimi (sağlayıcı anahtarı yok), kullanım ayrıştırma, 429/401/5xx/HTML eşlemesi, ağ hatasının yeniden denenebilirliği, oturumsuz istek yapılmaması |
| `QuizScorerTest` | 5 | Çoktan seçmeli/DY, yazımda aksan-ek-yazım hatası toleransı, sayısal karşılaştırma, sıralama/eşleştirmede kısmi puan |
| `LearningTest` | 8 | Günlük hedef (yerel gün, gece yarısını geçen ve açık unutulan oturum), seri koruması, kaliteye dayalı XP/seviye/rozet, plan bütçesi + ön koşul sırası + kontrol noktası, kaçırılan günlerin kaydırılması ve uyarlama, hatırlatma (yerel saat, gün seçimi, tatil, akşam), yaz saati boşluğu ve saat dilimi değişimi, analitikte tanıma/hatırlama ayrımı ve az veri |
| `StudyAiTest` | 7 | "Sonraki ders" modülleri: modelsiz (klasik yöntem) akıllı not, iki sesli podcast (A/B sırayla), hafıza kartları, ön bilgi ve hata kaynaklı bilgi boşlukları, boşluk doldurma (cevap seçeneklerde), A–D test, D/Y, yazılı sınav + iyi/kötü/boş cevap puanlaması, sözlü sınavda tek ek soru; dağınık model çıktısının kurtarılması (çitli çıplak dizi, "Sunucu/Uzman" → A/B, "C"/seçenek metni → indeks, "Doğru/Yanlış" → boolean); bozuk çıktıda uyarılı klasik yöntem; 1024 bağlamda istemin %60'ı aşmaması ve TextRank paketleme; JsonSanitizer (BOM, çit, sondaki virgüller, dize içi parantez, kesik dizi) |
| `GeminiClientTest` | 7 | Gemini REST istemcisi sahte sunucuya karşı: anahtar başlıkta (URL'de değil), Android kısıtlama başlıkları, sıcaklık yok, `thinkingLevel`, JSON modu; düşünce parçalarının ayrılması ve token sayımı; 429'da `retryDelay` / `Retry-After` / üstel bekleme; deneme sınırı; günlük kotanın beklenmeden bildirilmesi (Pasifik gece yarısı); 400/403/engellenen/boş cevap; kesik cevap işareti; sağlayıcının JSON istemesi |
| `BookSummarizerTest` | 10 | 5.000–8.000 kelimelik parçalar, uzun bölümün eşit bölünmesi, paragrafsız metnin cümlelerden bölünmesi, bölüm başına ~%10, 2.000–4.000 kelimelik genel özet, kısa kitapta orantılı özet, yarıda kesilip kalan isteklerle devam, aynı kitabın yeniden işlenmemesi (ayar değişse bile), eksik bölümün bir kez daha istenmesi, bozuk cevapta kayıt yapılmaması, kitap içi talimat enjeksiyonu, ayar doğrulama |
| `BackMatterFilterTest` | 6 | TR/EN başlıklar; PDF içindekiler/dizin/kaynakça sayfalarının ayıklanması ve kitabın ortasındaki yıllı sayfanın korunması; başlıksız PDF'in 5.000–8.000 kelimelik bölümleri; PDF ana hattındaki "Kaynaklar"; Markdown "İçindekiler/References"; EPUB guide/landmarks ve nav belgesi |
| `SyncTest` | 7 | Supabase giriş/kayıt, upsert başlıkları, değişen satır sorgusu, HTTP hataları, LWW ve tombstone, tekrar durumu birleştirme |

## Uygulama testleri (Robolectric, 52)

| Sınıf | Adet | Kapsam |
|---|---|---|
| `DatabaseTest` | 3 | **Migration 1→2** (`MigrationTestHelper` + Room ile açma), FK kademeli silme, kavram silinince kartın korunması, dedupe benzersizliği |
| `PipelineTest` | 9 | Demo kitabın uçtan uca işlenmesi (5 bölüm, ≥10 kavram, kart sayısı), **tekrar çalıştırmada çoğalmama ve yarıda kalan bölümden devam**, silinen bölüm metninin analiz kaybetmeden yeniden çıkarılması, aynı kitabın iki kez eklenmesi, dosya içe aktarma doğrulamaları (çalıştırılabilir, boş, ZIP; reddedilende iz kalmaması), EPUB, AI zaman aşımında yerel motora düşme ve etiket, mock AI yolu, kitap silme ve tombstone |
| `LearningFlowTest` | 12 | Tekrar kuyruğu (yeni kart sınırı, 4 gün sonra vade, kayıt, XP), unutma ve aynı gün geri gelme, tüm özet türleri + düzenleme, quiz oluşturma/puanlama/yanlışların kuyruğa girmesi/“sadece yanlışlar” modu, öğretmen oturumunun tamamlanması, hikâye etiketi, notun karta dönüşmesi, plan bütçesi ve kaydırma, kitaplar arası bağ, analitik (ölçüm/tahmin ayrımı), dışa aktarma ZIP içeriği ve tüm verinin silinmesi, hatırlatmanın yerel saatle planlanması ve iptali |
| `SyncEngineTest` | 5 | Push: `user_id` gönderilmemesi, JSON boolean, dosya yükleme, FK sırası; pull: LWW (yeni uzak kazanır, eski uzak yerel düzenlemeyi ezmez), yerel profile sahiplik, **çekilen satırların geri gönderilmemesi**, sunucu zamanlı imleç + örtüşme; tetikleyicilerin tüm yazma yollarını (kademeli silme dahil) yakalaması; giriş anlık görüntüsünün FK sırası; hesapsız senkron yok |
| `AppUiTest` (Hilt + Compose) | 8 | Yapay zekâ asistanı (anahtarsız derlemede Gemini durumu, gizlilik bilgisi, kitap sayfasındaki açıklama); "Kitaba sor": çevrimdışı, kaynaklı cevap; Onboarding → Bugün (hedef, hatırlatma, demo kitap), kütüphane → kitap → hızlı özet (yerel köken etiketi), günlük tekrar (cevabı göster → derecelendir → ilerleme + kayıt), quiz oluşturma ve başlatma, bildirim ayarının kaydı, boş kütüphane durumu ve elle giriş doğrulaması |
| `StudyHubUiTest` (Hilt + Compose) | 6 (+1 isteğe bağlı) | Kitap → "Sonraki ders" kartı → ÖĞREN / PRATİK YAP / SINAV sekmeleri ve kart ızgarası ("ÖNERİLEN" rozeti); hafıza kartı çevirme ve "Tekrar et" ile tekrar kuyruğuna ekleme; akıllı metin, podcast transkripti, bilgi boşlukları; A–D testin bitirilmesi ve dersin tamamlanması, D/Y anında açıklama, alıştırma; yazılı sınav ayarı → cevap → tek tek puanlama → karne (zayıf cevaplar kuyruğa); konuşma tanıma olmayan telefonda yazarak sözlü sınav → sözlü puan; podcast zaman çizelgesinde kelime/satır atlama |
| `BookSummaryTest` | 7 | Gemini özeti (sahte model): her bölüm ~%10 + genel özet, telefonda kayıt, silinip yeniden eklenen kitabın gönderilmemesi, kota beklemesi ve kaldığı yerden devam, 503/401 hataları, anahtarsız ve yapay zekâ kapalı durum, işlenen kitabın arka planda bir kez kuyruğa girmesi, "tüm verimi sil" |
| `BookSummaryUiTest` (Hilt + Compose) | 2 | Kitap sayfasındaki özet kartı: başlatma → kuyrukta → hazır → okuma ekranı (genel özet + bölümler, Markdown); kota beklemesi açıklaması |

## SQL / RLS testleri (`supabase/tests/rls_test.sql`)

Supabase'in `auth`/`storage` şemalarının minimal taklitleri üzerinde gerçek PostgreSQL 16:
1) `user_id`'nin JWT'den varsayılanla atanması; 2) başka kullanıcının satırını görme/güncelleme/silme engeli;
3) başka kullanıcının kitabına bileşik FK ile bağ kurma ve başkası adına yazma engeli; 4) anon erişim yok;
5) `server_updated_at`'in sunucu tarafından korunması, `SET NULL`'ın sahipliği koruması, kademeli silme;
6) profilin çağırana bağlanması; 7) depoda yalnızca kendi klasörüne yazma/okuma; 8) kota fonksiyonunun
istemciye kapalı olması ve sınırda `null` dönmesi; 9) hesap silmenin tüm verileri silmesi.

## Edge Function testleri (`supabase/functions/tests/ai_core_test.ts`)

Koruma prompt'unun eklenmesi, görev başına effort, `fallbacks: "default"` + beta başlığı, `temperature`/
`thinking` gönderilmemesi, doğrulama (bilinmeyen görev, 413, sınırlar), kota aşımında 429 + UTC gece yarısı,
reddin 422 olarak bildirilmesi, yedek modelin kendi fiyatıyla hesaplanması, SDK hata eşlemesi ve defter kaydı,
maliyet tablosu. Gerçek Claude API'ye istek atan test **yoktur** (API anahtarı yok; bkz. sınırlar).

## Özel durumlar

| Durum | Nerede test edildi |
|---|---|
| Bozuk/boş dosya, desteklenmeyen tür | `PipelineTest.file import…`, `ParsersTest.file type sniffing…` |
| OCR gerektiren PDF | `ParsersTest.scanned pdf is flagged for OCR` (tespit); OCR'ın kendisi cihaz gerektirir — test edilmedi |
| Çok büyük kitap | Boyut sınırı ve akış kopyalama kodda; büyük dosya ile **otomatik test yok** |
| Boş AI yanıtı, eksik JSON, bozuk JSON | `AiPipelineTest` (4 test), Edge `empty_response` |
| İnternet kesintisi | `RemoteAiProviderTest.network failures…`, `AiPipelineTest.transient errors…`, `PipelineTest.AI failures fall back…` |
| Geçmiş tekrar tarihi, saat dilimi değişimi | `LearningFlowTest.review queue…` (4 gün sonra), `LearningTest.reminders handle DST…` |
| Aynı kitabın iki kez eklenmesi | `PipelineTest.importing the same book twice…` |
| Tekrar sırasında uygulamanın kapanması | Her derecelendirme tek transaction'da yazılır; oturum ayrılırken bağımsız kapsamda kapatılır. Kapanmayı simüle eden **otomatik test yok** |
| Migration hatası | `DatabaseTest.migration 1 to 2…` (başarılı yol; doğrulama başarısızlığı Room tarafından fırlatılır) |
| İşlemin yarıda kesilmesi | `PipelineTest.processing is idempotent and resumes…` |

## Cihaz testi

`scripts/android-smoke.sh`, CI'da API 34 emülatöründe **R8'li release APK** ile: kurulum → onboarding →
demo kitabın arka planda (WorkManager + Hilt worker) işlenmesi → kütüphane → kitap → hızlı özet → ders merkezi →
kitap sayfasındaki Gemini özet kartı (CI derlemesinde anahtar olmadığından "anahtar yok" durumu); logcat'te çökme
denetimi. CI'ya Gemini anahtarı bilerek konmadı: anahtar indirilebilir APK'nın içine yazılırdı. Bu oturumun ortamında KVM olmadığından emülatör yerelde çalıştırılamadı; CI'da çalışır.

İlk CI çalıştırmasının bulguları (320×640 dp küçük ekranlı emülatör):
- Release APK kuruldu ve soğuk başlatma 3,6 sn'de tamamlandı; logcat'te çökme yok.
- **Gerçek UI hatası bulundu ve düzeltildi:** sabit `Row` içindeki seçim çipleri (onboarding hedef/saat, plan,
  ayarlar, bildirim yoğunluğu/tatil) dar ekranda taşıyor, son çip dikey olarak eziliyordu → `FlowRow` ile satır
  kaydırma.
- Betik hatası düzeltildi: "Start" metni önce "Start with a sample book" onay kutusunu eşliyordu; artık tam eşleşme
  öncelikli.

## Lint uyarıları (70)

- 64 × `PluralsCandidate`: sayı içeren İngilizce metinler (ör. "1 chapters"). Türkçede çoğul eki gerekmediği için
  işlev bozmaz; İngilizce için `plurals`'a çevrilmeli (kalan iş).
- 3 × `TrustAllX509TrustManager`: PdfBox-Android'in geçişli BouncyCastle jar'ı içinde; uygulamanın ağ yığını
  (OkHttp, platform güven deposu) bu sınıfı kullanmaz.
- 2 × `ObsoleteSdkInt` (gereksiz sürüm kontrolü ve `mipmap-anydpi-v26` klasörü; işlev etkisi yok).
- 1 × `AppBundleLocaleChanges`: uygulama içi dil değişimi; Play App Bundle ile yayında dil kaynakları için
  `bundle { language { enableSplit = false } }` gerekir (kalan iş).

## Bilinen test açıkları

- Gerçek Supabase projesine ve gerçek Claude API'ye karşı uçtan uca test yapılmadı (kimlik bilgisi yok).
- ML Kit OCR ve PdfBox ile gerçek PDF dosyaları cihazda test edilmedi (PDF yapı mantığı birim testli).
- Erişilebilirlik (TalkBack, kontrast) için manuel denetim yapılmadı.

## Görsel doğrulama (ekran görüntüsü turu)

`ScreenshotTour` (Robolectric yerel grafik, varsayılan olarak atlanır) uygulamayı Türkçe, İngilizce ve koyu temada baştan sona
gezer: dil seçimi, onboarding, Bugün, Kütüphane, kitap detayı, kavramlar, hızlı özet, quiz kurulumu/soru/geri bildirim, tekrar
kartı ve değerlendirme, İlerleme, Profil. İngilizce turda ekranda Türkçe metin kalmadığı görsel olarak ve CI emülatör testinde
(`scripts/android-smoke.sh`, İngilizce özet ekranında Türkçe metin aranır) doğrulanır.
