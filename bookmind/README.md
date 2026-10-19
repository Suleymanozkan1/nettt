# BookMind AI

Kitapları içe aktaran, analiz eden, özetleyen, kullanıcıya öğreten, sınav hazırlayan ve öğrenilenleri unutma
eğrisine göre tekrar ettiren Android uygulaması. Amaç kitapları hızlı tüketmek değil; kitaptan edinilen bilgiyi
**anlamak, hatırlamak ve uygulamak**.

> Bu dizin `nettt` deposunun içinde bağımsız bir projedir. Depodaki diğer proje (Gölge Kuklacı oyunu) ile kod
> paylaşmaz.

## Özellikler (kısa)

- **İçe aktarma:** PDF (PdfBox-Android), taranmış PDF için cihaz üzerinde OCR (ML Kit), EPUB 2/3, TXT, Markdown,
  elle metin girişi. Dosyalar sihirli baytlarla doğrulanır, 150 MB sınırı, SHA-256 ile yinelenen kitap tespiti.
- **Analiz:** bölüm/sayfa sınırları korunur, uzun bölümler parçalanır, arka planda (WorkManager) işlenir; işlem
  yarıda kalırsa kaldığı bölümden devam eder, hazır bölümler hemen kullanılabilir.
- **Özetler:** hızlı, ayrıntılı, yönetici, kavram haritası, en önemli fikirler, bölüm karşılaştırması, bölüm özeti;
  uzunluk ayarı, kaynak referansları (bölüm/sayfa/kısa alıntı, kitapta doğrulanmış), kullanıcı düzenlemesi.
- **Yapay zekâ öğretmen:** seviye ölçme → anlatım → kontrol sorusu → transfer → kendi cümlelerinle açıklama;
  5 seviye, 10 anlatım tarzı; cevapları arka arkaya vermez.
- **Quiz:** 13 soru türü, mod (standart, sadece yanlışlar, unutulmaya yakın, karışık, tüm kitap), süre sınırı,
  spoiler koruması; kural tabanlı ve rubrikli değerlendirme, güven düzeyi; yanlışlar tekrar kuyruğuna girer.
- **Aralıklı tekrar:** FSRS-4.5 (varsayılan) ve SM-2; 4 düğmeli geri bildirim gerçek cevapla birleştirilir;
  her öğenin tam geçmişi; tahmin ve ölçüm ayrı gösterilir.
- **Kişiselleştirme:** öğrenme planı (eksik günleri suçluluk yaratmadan kaydırır), aktif hatırlama düzeyleri,
  hikâye/örnek üretici, kişisel notlar ve vurgular, kitaplar arası bilgi grafiği, oyunlaştırma (kaliteye dayalı XP,
  esnek seri), analitik, bildirimler (yerel saat dilimi, gün seçimi, tatil modu, gizli içerik).
- **Telefonda çalışan ücretsiz yapay zekâ:** Profil → Yapay zekâ asistanı (veya ana ekrandaki kart) üzerinden tek
  dokunuşla açık bir model indirilir (önerilen: **Gemma 4 E2B**, 2,6 GB, Apache-2.0; 12 GB RAM'li telefonlarda Gemma 4
  E4B, 4 GB'lık telefonlarda Qwen3 0.6B). Model Google'ın LiteRT-LM motoruyla tamamen telefonda, internetsiz çalışır;
  başka bir uygulama, hesap, API anahtarı ya da ücret gerekmez ve kitap metni telefondan çıkmaz. Model inene kadar
  (veya yapay zekâ kapalıyken) klasik yöntemler kullanılır (TextRank, BM25, kurallar). Ücretli bulut yapay zekâ
  varsayılan derlemeden çıkarılmıştır. Ayrıntılar: [docs/FREE_AI.md](docs/FREE_AI.md).
- **Kitaba sor ve sesli dinle:** kitapla soru-cevap (BM25 + telefondaki model ya da kitaptan alıntı) ve telefonun
  yerleşik sesli okuması.
- **Gizlilik:** kitap metni telefonundan çıkmaz; yapay zekâ için tek ağ bağlantısı modelin bir kez indirilmesidir. Hesap isteğe bağlıdır.

Ayrıntılar: [docs/FINAL_IMPLEMENTATION_REPORT.md](docs/FINAL_IMPLEMENTATION_REPORT.md).

## Proje yapısı

```
bookmind/
  core/        Saf Kotlin (JVM): FSRS/SM-2, metin/EPUB/PDF yapı çözümleme, AI katmanı (prompt, doğrulama,
               yeniden deneme, önbellek, yerel motor), quiz puanlama, plan, hatırlatma, analitik, Supabase istemcisi
  app/         Android uygulaması: Room, WorkManager, Hilt, Compose UI, senkronizasyon
  supabase/    SQL migration'ları (RLS), Edge Functions (ai, delete-account), testler
  scripts/     Şema üretici, SQL/RLS test koşucusu, emülatör duman testi
  docs/        Mimari, veri modeli, AI hattı, öğrenme/tekrar, gizlilik ve telif, test raporu, final rapor
```

## Gereksinimler

- JDK 17+ (21 ile test edildi), Android SDK 36 (build-tools 36.0.0), Gradle wrapper (8.14.3) dahil
- İsteğe bağlı: Supabase CLI, Deno 2.x (Edge Function testleri), PostgreSQL 15+ (SQL testleri)

## Kurulum ve çalıştırma

```sh
cd bookmind
cp local.properties.example local.properties   # sdk.dir'i ayarla; Supabase alanları isteğe bağlı
./gradlew :app:installDebug                      # bağlı cihaza/emülatöre kurar
```

Supabase alanları boşsa uygulama **yalnızca yerel modda** çalışır: içe aktarma, analiz (cihaz üzerindeki motor),
özet, quiz, öğretmen, tekrar, notlar, plan ve bildirimler tam çalışır; hesap ve bulut yapay zekâ kapalıdır.
İlk açılışta onboarding önce **dili** sorar (Türkçe varsayılan, İngilizce seçilebilir); arayüz, cihaz üzerinde üretilen içerik, yapay zekâ çıktısı, plan başlıkları, uyarılar ve bildirimler tek bu dili kullanır, ekranda iki dil karışmaz. İsteğe bağlı olarak özgün demo kitabını seçilen dilde ekler (**Öğrenmenin Bilimi** / **The Science of Learning**, CC0).

### Yapılandırma

| Nerede | Anahtar | Açıklama |
|---|---|---|
| `local.properties` veya ortam değişkeni | `bookmind.supabaseUrl` / `BOOKMIND_SUPABASEURL` | `https://<proje>.supabase.co` (debug derlemede yerel `http://10.0.2.2:54321` de kabul edilir) |
| `local.properties` veya ortam değişkeni | `bookmind.supabaseAnonKey` / `BOOKMIND_SUPABASEANONKEY` | Supabase anon (public) anahtarı |
| `local.properties` | `bookmind.enableCloudAi=true` | **Ücretli** bulut yapay zekâyı derlemeye ekler. Varsayılan: kapalı |
| Edge Function secret | `ANTHROPIC_API_KEY` | Yalnızca bulut açık derlemelerde ve sunucuda. Uygulamaya **asla** konmaz |
| Edge Function secret | `AI_MODEL` (varsayılan `claude-opus-5-5`), `AI_DAILY_REQUEST_LIMIT` (150) | İsteğe bağlı |

Uygulama içi ayarlar (Profil sekmesi): yapay zekâ asistanı (model indirme, model seçimi, aç/kapat; debug derlemede
mock), tekrar algoritması (FSRS/SM-2), hedef hatırlama oranı, günlük yeni kart sayısı, tema, dil (TR/EN), spoiler koruması.

### Supabase (isteğe bağlı bulut)

```sh
cd bookmind
supabase start                                  # yerel geliştirme
supabase db reset                               # migration'ları uygular
cp supabase/.env.example supabase/.env           # ANTHROPIC_API_KEY'i doldur (git'e eklenmez)
supabase functions serve --env-file supabase/.env
# Üretim:
supabase link --project-ref <ref> && supabase db push
supabase secrets set --env-file supabase/.env
supabase functions deploy ai && supabase functions deploy delete-account
```

Room şeması değişirse sunucu tablolarını yeniden üret:
`python3 scripts/gen_supabase_schema.py app/schemas/com.bookmind.app.data.db.BookMindDatabase/<v>.json`
(CI üretilen dosyanın güncel olduğunu denetler).

## Test

```sh
./gradlew :core:test                 # 100 birim testi (FSRS, ayrıştırıcılar, AI hattı, yerel modeller, BM25, planlayıcı, senkron istemcisi)
./gradlew :app:testDebugUnitTest     # 42 test (+4 isteğe bağlı ekran görüntüsü turu): Room/migration, işleme hattı, depolar, senkronizasyon, Compose UI (Robolectric + Hilt)
./gradlew :app:lintDebug
bash scripts/test-supabase-sql.sh    # migration'lar + RLS testleri (PostgreSQL 16)
(cd supabase/functions && deno test --allow-env tests/)   # Edge Function testleri
APK=app-x86_64-release.apk bash scripts/android-smoke.sh         # emülatörde release APK duman testi (CI'da çalışır)
```

Sonuçlar ve kapsam: [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

## Derleme

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # R8 küçültme + kaynak küçültme açık; işlemci türü başına APK:
                                 # app-arm64-v8a-release.apk (telefonlar), app-universal-release.apk (her cihaz)
```

Release derlemesi şu an **debug anahtarıyla** imzalanır (yerel denemeler için). Mağaza yayını için
`app/build.gradle.kts` içindeki `signingConfig`'i kendi keystore'unuzla değiştirin ve Play için App Bundle
(`bundleRelease`) kullanın (ABI bölmesi indirme boyutunu ciddi azaltır).

## Belgeler

- [Mimari](docs/ARCHITECTURE.md)
- [Veri modeli](docs/DATA_MODEL.md)
- [AI hattı](docs/AI_PIPELINE.md)
- [Ücretsiz yapay zekâ: envanter, geçiş, değerlendirme](docs/FREE_AI.md)
- [Öğrenme ve aralıklı tekrar](docs/LEARNING_AND_SPACED_REPETITION.md)
- [Gizlilik ve telif](docs/PRIVACY_AND_COPYRIGHT.md)
- [Test raporu](docs/TEST_REPORT.md)
- [Final uygulama raporu](docs/FINAL_IMPLEMENTATION_REPORT.md)

## Lisanslar

Kullanılan bağımlılıklar ve lisansları uygulama içinde (Profil → Hakkında) ve
[docs/PRIVACY_AND_COPYRIGHT.md](docs/PRIVACY_AND_COPYRIGHT.md) içinde listelenir. Demo kitap BookMind için özgün
yazılmıştır ve CC0'dır.

### Tasarım ve metinler

- Tasarım sistemi `ui/theme/Theme.kt` (marka renkleri, açık/koyu tema, Plus Jakarta Sans + Lora yazı tipleri, şekiller) ve
  `ui/components/Components.kt` (kartlar, kahraman kartı, üretilmiş kitap kapakları, halka/çubuk grafikler, seçim çipleri,
  düğmeler) içindedir. Duvar kâğıdından gelen dinamik renk bilerek kullanılmaz.
- Yazı tipleri SIL Open Font License 1.1 ile dağıtılır (`app/src/main/assets/licenses/`).
- Tüm arayüz metinleri `scripts/gen_strings.py` içinde TR/EN çiftleri olarak tutulur; `python3 scripts/gen_strings.py
  app/src/main/res app/src/main/java` iki `strings.xml` dosyasını üretir ve eksik/kullanılmayan anahtarları raporlar.
- Çekirdek modülün ürettiği metinler (`core/.../i18n/CoreText.kt`) aynı dil ayarını izler.
- Ekran görüntüleri: `./gradlew :app:testDebugUnitTest --tests '*ScreenshotTour*' -Dbookmind.screenshots=<klasör>`
  (Robolectric yerel grafik; TR, EN ve koyu tema).
