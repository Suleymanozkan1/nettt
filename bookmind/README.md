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
- **Gizlilik:** varsayılan **cihaz üzerinde** çalışır; yapay zekâ isteğe bağlıdır ve yalnızca güvenli sunucu
  (Supabase Edge Function) üzerinden çağrılır, API anahtarı uygulamada yoktur. Hesap isteğe bağlıdır.

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
İlk açılışta onboarding, isteğe bağlı olarak özgün demo kitabı (**Öğrenmenin Bilimi**, CC0) ekler.

### Yapılandırma

| Nerede | Anahtar | Açıklama |
|---|---|---|
| `local.properties` veya ortam değişkeni | `bookmind.supabaseUrl` / `BOOKMIND_SUPABASEURL` | `https://<proje>.supabase.co` (debug derlemede yerel `http://10.0.2.2:54321` de kabul edilir) |
| `local.properties` veya ortam değişkeni | `bookmind.supabaseAnonKey` / `BOOKMIND_SUPABASEANONKEY` | Supabase anon (public) anahtarı |
| Edge Function secret | `ANTHROPIC_API_KEY` | Yalnızca sunucuda. Uygulamaya **asla** konmaz |
| Edge Function secret | `AI_MODEL` (varsayılan `claude-opus-5-5`), `AI_DAILY_REQUEST_LIMIT` (150) | İsteğe bağlı |

Uygulama içi ayarlar (Profil sekmesi): yapay zekâ modu (cihaz üzerinde / bulut / mock), günlük AI istek sınırı,
tekrar algoritması (FSRS/SM-2), hedef hatırlama oranı, günlük yeni kart sayısı, tema, dil (TR/EN), spoiler koruması.

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
./gradlew :core:test                 # 86 birim testi (FSRS, ayrıştırıcılar, AI hattı, planlayıcı, senkron istemcisi)
./gradlew :app:testDebugUnitTest     # 35 test: Room/migration, işleme hattı, depolar, senkronizasyon, Compose UI (Robolectric + Hilt)
./gradlew :app:lintDebug
bash scripts/test-supabase-sql.sh    # migration'lar + RLS testleri (PostgreSQL 16)
(cd supabase/functions && deno test --allow-env tests/)   # Edge Function testleri
APK=app-release.apk bash scripts/android-smoke.sh          # emülatörde release APK duman testi (CI'da çalışır)
```

Sonuçlar ve kapsam: [docs/TEST_REPORT.md](docs/TEST_REPORT.md).

## Derleme

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # R8 küçültme + kaynak küçültme açık
```

Release derlemesi şu an **debug anahtarıyla** imzalanır (yerel denemeler için). Mağaza yayını için
`app/build.gradle.kts` içindeki `signingConfig`'i kendi keystore'unuzla değiştirin ve Play için App Bundle
(`bundleRelease`) kullanın (ABI bölmesi indirme boyutunu ciddi azaltır).

## Belgeler

- [Mimari](docs/ARCHITECTURE.md)
- [Veri modeli](docs/DATA_MODEL.md)
- [AI hattı](docs/AI_PIPELINE.md)
- [Öğrenme ve aralıklı tekrar](docs/LEARNING_AND_SPACED_REPETITION.md)
- [Gizlilik ve telif](docs/PRIVACY_AND_COPYRIGHT.md)
- [Test raporu](docs/TEST_REPORT.md)
- [Final uygulama raporu](docs/FINAL_IMPLEMENTATION_REPORT.md)

## Lisanslar

Kullanılan bağımlılıklar ve lisansları uygulama içinde (Profil → Hakkında) ve
[docs/PRIVACY_AND_COPYRIGHT.md](docs/PRIVACY_AND_COPYRIGHT.md) içinde listelenir. Demo kitap BookMind için özgün
yazılmıştır ve CC0'dır.
