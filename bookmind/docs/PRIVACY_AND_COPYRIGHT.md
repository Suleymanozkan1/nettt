# Gizlilik, güvenlik ve telif

## İlkeler

1. **Yerel öncelik.** Varsayılan modda kitap metni, notlar ve öğrenme geçmişi yalnızca cihazdadır. Hesap ve bulut
   yapay zekâ isteğe bağlıdır.
2. **Anahtarlar istemcide değil.** LLM sağlayıcı anahtarı yalnızca Supabase Edge Function secret'ıdır. Uygulama
   yalnızca herkese açık Supabase URL'si ve anon anahtarını içerir; yetki RLS ile veritabanında sağlanır.
3. **Kitaplar eğitim verisi değildir.** Kullanıcının kitapları model eğitimi için kullanılmaz, başka kullanıcılarla
   paylaşılmaz ve herkese açık bir içerik havuzuna dönüştürülmez.
4. **Gereksiz veri toplanmaz.** Analitik/izleme SDK'sı yoktur. Sunucuda yalnızca senkronizasyon için gereken
   tablolar ve AI kullanım defteri (görev, model, token, maliyet; içerik yok) tutulur. Öğretmen sohbetleri, AI
   önbelleği ve kullanım kayıtları cihazdan çıkmaz.

## Uygulanan önlemler

| Alan | Önlem |
|---|---|
| Yetkilendirme | Tüm sunucu tablolarında RLS (`user_id = auth.uid()`); bileşik `(id, user_id)` FK'ler başka kullanıcının satırına bağ kurmayı engeller; `user_id` istemciden alınmaz; anon rolün tablo izni yoktur. `supabase/tests/rls_test.sql` ile test edilir. |
| Dosya deposu | Özel `book-files` kovası, yol `<kullanıcı-id>/<kitap-id>/…`, yalnızca sahibi okuyup yazar; boyut sınırı 150 MB. |
| Oturum | Yenileme jetonu Android Keystore'daki AES-256-GCM anahtarıyla şifrelenip `noBackupFilesDir`'de tutulur. Keystore yoksa yalnızca bellekte. |
| Ağ | Yalnızca HTTPS (network security config, cleartext kapalı). Debug derlemede yalnızca yerel geliştirme adreslerine (10.0.2.2/localhost) izin. |
| Dosya yükleme | Sihirli bayt ile tür doğrulama (uzantıya güvenilmez), ikili/ZIP/çalıştırılabilir dosyalar reddedilir, 150 MB sınırı (akış sırasında da denetlenir), reddedilen içe aktarmada dosya silinir; EPUB girdisi başına 20 MB sınırı (zip bombası), yol normalleştirme; DRM/parola korumalı dosyalar açılmaz. |
| Prompt enjeksiyonu | Kitap metni ve kullanıcı cevabı etiketli **veri** olarak verilir, etiket benzerleri etkisizleştirilir; sistem ve sunucu koruma talimatı bu içerikteki talimatlara uyulmamasını söyler; sunucu görev beyaz listesi ve boyut sınırı uygular. |
| Kota ve maliyet | Sunucu: kullanıcı başına günlük istek sınırı (yarış güvenli). Uygulama: kullanıcı ayarlı günlük sınır, son 30 günün token/maliyet özeti. |
| Yedekleme | Android bulut yedeklemesi kapalıdır (kitaplar ve notlar özel içeriktir). Yedek: isteğe bağlı hesap senkronizasyonu ve ZIP dışa aktarma. Cihazdan cihaza aktarım açıktır. |
| Bildirimler | Varsayılan olarak kitap/not içeriği göstermez; kilit ekranında `VISIBILITY_PRIVATE`. |
| Silme | Kitap silme: dosya, bölüm metinleri, özetler, kavramlar, quizler, kartlar; diğer cihazlara tombstone ile yayılır. Hesap silme: `delete-account` fonksiyonu depodaki dosyaları siler ve auth kullanıcısını siler (tüm tablolar `on delete cascade`); ardından yerel veritabanı, dosyalar ve ayarlar temizlenir. |
| Dışa aktarma | Profil → "Verilerimi dışa aktar": tüm tablolar JSON + içe aktarılan kitap dosyaları, kullanıcının seçtiği konuma ZIP. |

## Telif hakkı

- Kullanıcıya içe aktarma ekranında yalnızca kullanma hakkı olan kitapları eklemesi hatırlatılır.
- **Özetler dönüştürücüdür.** AI prompt'ları uzun pasajların yeniden üretilmesini yasaklar ve açıklama/analiz
  ister; alıntılar en fazla 200 karakterdir ve kitapta doğrulanır.
- **Yerel motor** kitaptan seçilmiş kısa cümleler kullanır: bölüm başına 1–4 cümle; testler (`local summary is
  short relative to the book`) çıktının kaynağa göre kısa kaldığını doğrular. Bu içerik kullanıcının kendi
  cihazındaki kişisel çalışma notudur ve paylaşılmaz.
- Kitap içeriği başka kullanıcılara hiçbir yolla sunulmaz; senkronizasyon yalnızca aynı kullanıcının cihazları
  arasındadır.
- Üretilen içeriğin kaynağı mümkün olduğunca gösterilir; doğrulanamayan kaynak açıkça "doğrulanamadı" olarak
  işaretlenir; kitapta olmayan, yapay zekânın ürettiği örnekler "üretilmiş örnek (kitapta yok)" etiketlidir.
- **Demo içeriği:** "Öğrenmenin Bilimi" BookMind için özgün yazılmıştır, CC0 olarak serbesttir; başka bir eserden
  alıntı içermez.

## İçerik sağlayıcı şartları

- Claude API (Anthropic): ticari şartlar ve kullanım politikası geçerlidir; güvenlik sınıflandırıcısı redleri
  işlenir (sunucu tarafı yedek model, ardından cihaz üzerindeki motor).
- Google ML Kit metin tanıma: Google'ın ML Kit şartlarına tabidir; model cihazda çalışır, görüntüler gönderilmez.

## Kullanılan bağımlılıklar ve lisanslar

| Bileşen | Lisans | Not |
|---|---|---|
| AndroidX / Jetpack (Compose, Room, WorkManager, DataStore, Navigation, Lifecycle, AppCompat) | Apache 2.0 | |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache 2.0 | |
| Dagger / Hilt | Apache 2.0 | |
| OkHttp | Apache 2.0 | |
| jsoup | MIT | EPUB/XHTML ayrıştırma |
| PdfBox-Android (tom-roush) | Apache 2.0 | Geçişli BouncyCastle (MIT benzeri) — lint'in "trust-all TrustManager" uyarısı bu kütüphanenin içindeki, bizim ağ yığınımızın kullanmadığı bir sınıftan gelir |
| Google ML Kit Text Recognition | Google ML Kit şartları (kapalı kaynak) | Cihaz üzerinde OCR |
| Robolectric, JUnit, MockWebServer | MIT / EPL / Apache 2.0 | Yalnızca test |
| FSRS algoritması | open-spaced-repetition formülleri (MIT) | Kod BookMind'da yeniden yazıldı |
| Edge Functions: @anthropic-ai/sdk, @supabase/supabase-js | MIT | Sunucu |
