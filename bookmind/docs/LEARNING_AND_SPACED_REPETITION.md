# Öğrenme ve aralıklı tekrar

## Tekrar edilen öğeler

Her öğenin (`learning_items`) kendi hafıza durumu (`review_states`) ve tam geçmişi (`review_logs`) vardır;
herkese aynı gün bildirim gönderen basit bir sistem değildir.

| Kaynak | Öğe türü | Ne zaman oluşur |
|---|---|---|
| Kitap analizi | `CONCEPT` (kurguda `CHARACTER_RELATION`) | Her bölümün kavramları için; yeni kart olarak (günlük yeni kart sınırıyla tanıtılır) |
| Quiz'de yanlış cevap | `WRONG_ANSWER` (soru ile birlikte) | Hemen vadesi gelir |
| Kişisel not | `USER_NOTE` | Kullanıcı "tekrar kuyruğuna ekle" dediğinde |
| Öğretmen oturumu | `CONCEPT` | Oturum sonunda; anlaşılmadıysa hemen vadeli |

`DATE_EVENT`, `FORMULA`, `PRINCIPLE`, `CASE`, `KEY_IDEA` türleri veri modelinde vardır; şu an otomatik üretilmez
(bkz. final rapor).

## Algoritma: FSRS-4.5 (varsayılan)

`core/srs/FsrsScheduler.kt` — open-spaced-repetition projesinin yayımladığı formüllerden yazıldı (MIT):

- Hatırlanabilirlik: `R(t, S) = (1 + 19/81 · t/S)^-0.5` (S gün sonra %90).
- Aralık: `I = S/(19/81) · (r^(1/-0.5) − 1)`, hedef hatırlama oranı `r` ayarlanabilir (0,75–0,95; varsayılan 0,9).
- İlk kararlılık ve zorluk derecelendirmeye göre; hatırlama sonrası kararlılık
  `S·(1 + e^w8·(11−D)·S^−w9·(e^(w10(1−R))−1)·ceza·bonus)`; unutma sonrası
  `min(S, w11·D^−w12·((S+1)^w13−1)·e^(w14(1−R)))`; zorluk ortalamaya dönüşlü, 1..10 arasında.
- "Hiç hatırlayamadım" → 10 dk sonra yeniden öğrenme (aynı oturumda tekrar gelir); diğerleri gün cinsinden,
  en fazla 100 yıl; isteğe bağlı deterministik ±%5 dağıtma.
- Varsayılan 17 ağırlık; `algorithmVersion = "fsrs-4.5"` her duruma yazılır.

**SM-2** (`Sm2Scheduler`) açıklanabilir alternatif olarak ayarlardan seçilebilir; durum alanları ortak olduğundan
algoritma değiştirmek veri kaybettirmez. Algoritmalar `SpacedRepetitionScheduler` arayüzünün arkasındadır; UI
yalnızca `preview()` ile düğme altındaki "x gün sonra" etiketlerini alır.

## Kullanıcı geri bildirimi + gerçek cevap

Dört düğme: *Hiç hatırlayamadım (1)*, *Zor hatırladım (2)*, *Doğru hatırladım (3)*, *Çok kolaydı (4)*.
`RatingResolver` nesnel kanıtı önceler:

- Yanlış cevap (veya açık uçluda skor < 0,5) → her zaman 1.
- Doğru ama kullanıcı "hatırlayamadım" dedi → 2 (tahmin etmiş olabilir; unutma sayılmaz).
- İpucu kullanıldıysa veya kısmi puan (< 0,8) → en fazla 2.
- 25 sn'den uzun süren "çok kolay" → 3.
- Yaklaşık (düşük güvenli) puanlar kesin kanıt değil, skor olarak işlenir.

Her kayıtta saklananlar: zaman, cevap, ölçülen doğruluk, süre, kullanıcı beyanı, nihai derece, planlanan aralık,
önceki tekrardan geçen gün, ipucu, hatırlama düzeyi, tekrardan önceki tahmini hatırlanabilirlik, kaynak (tekrar/quiz),
algoritma ayrıntıları.

## Unutma eğrisi ekranı (İlerleme)

- Bugün / gecikmiş / önümüzdeki 14 günün tekrar tahmini, kitap bazında hatırlama, harcanan süre, hedefe ulaşılan
  günler, başarılar.
- **Ölçülen** (cevaplardan): genel başarı, ilk denemede doğru, gecikmeli hatırlama (≥1 gün), ipucusuz hatırlama,
  düzey bazında oranlar. 10 cevaptan azsa yüzde yerine "x/y (az veri)" gösterilir (Wilson aralığı hesaplanır).
- **Tahmin** (algoritma): ortalama hatırlanabilirlik ve tahmini eğri — kesikli çizgi, "kesin sonuç değildir" notu.
- **Beyan**: "çok kolay" işaretleme oranı.
- **Kalıcı** sayılma kuralı: ≥21 gün sonra, ipucusuz, tanıma dışı bir düzeyde doğru cevap. Kitabı okumak, özeti
  görmek veya çoktan seçmeliyi doğru işaretlemek tek başına "öğrenildi" sayılmaz.
- **Zayıf** kavram: son 5 cevapta en az 3 cevap ve <%60 doğruluk ya da ≥2 hata.

## Aktif hatırlama düzeyleri

Soru türü → düzey: tanıma (çoktan seçmeli, D/Y, eşleştirme), hatırlama (boşluk, kısa cevap, tanım, sıralama),
açıklama (açık uçlu, kendi cümlelerinle, hata bulma), transfer (senaryo, gerçek hayat), uygulama (hesaplama).
Düzeyler ayrı saklanır; tanımada başarılı ama hatırlama/açıklamada ≥25 puan geride olan kullanıcıya ipucusuz
hatırlama ve açıklama görevleri önerilir.

## Öğrenme planı

`StudyPlanner`: günlük süre (5/10/20/30 dk) bütçesi asla aşılmaz; ön koşul kavram içeren bölümler önce gelir;
bölüm başına öğrenme oturumu (≈120 kelime/dk) ve quiz, her gün başında kısa tekrar, her 3 bölümde kontrol
noktası, sonda gerçek hayat görevi. Kaçırılan görevler bugünden itibaren bütçeyi aşmadan ileri kaydırılır
(hiçbir şey silinmez, tek güne yığılmaz). Son 14 günde doğruluğu <%60 olan bölümlere ek tekrar eklenir.

## Günlük hedef, seri ve oyunlaştırma

- Günlük hedef, tamamlanan çalışma oturumlarının **yerel takvim gününe** düşen dakikalarıdır; açık unutulan oturum
  90 dk ile sınırlanır.
- Seri: her 7 günlük seride bir koruma hakkı (en fazla 2); kaçırılan bir gün seriyi bozmaz.
- XP kaliteye göre: tanıma 2, hatırlama 4, açıklama 6, transfer/uygulama 8; gecikmeli doğru hatırlamaya +2/+4/+6;
  ipucuyla yarısı; yanlışa 1 (denemek de değerlidir); hızlı cevaplamaya ekstra puan yoktur. Seviye:
  100·n·(n+1)/2 toplam XP. Rozetler: ilk tekrar, 7/30 gün, 100 tekrar, ilk kitap, kalıcı hafıza, anlatıcı,
  uygulayıcı, not ustası. Kaybetme korkusuna dayalı mekanik kullanılmaz.

## Bildirimler

Yerel saat ve seçili günlerde; içerik türleri (bugünün tekrarları, gecikmiş, günlük hedef, devam eden kitap),
yoğunluk (günde bir veya + akşam hatırlatması — yalnızca hedef tamamlanmadıysa ve tekrar varsa), tatil modu,
içerik gizliliği (varsayılan kapalı; kilit ekranında özel). İzin reddedilirse uygulama aynen çalışır.
