# Ücretsiz yapay zekâ: envanter, geçiş ve sonuçlar

Bu belge "Kitap Özetleyici – Ücretsiz AI Alternatifleri" ek görevinin raporudur. Amaç, BookMind AI'daki her
yapay zekâ özelliğinin ücretsiz ve çevrimdışı çalışması, ücretli servislerin varsayılan olarak tamamen devre dışı
kalmasıdır. Son hâlinde yapay zekâ **tamamen telefonda** çalışır: kullanıcı uygulama içinden tek dokunuşla açık bir
model indirir; Ollama, ayrı bir uygulama, hesap veya API anahtarı gerekmez.

## 1. Değişiklik öncesi envanter

Kod değiştirilmeden önce çıkarılan liste (commit `6ad208f`):

| Bileşen | Ne yapıyordu | Ücret / dış servis |
|---|---|---|
| `RemoteAiProvider` → Supabase Edge Function `ai` → Anthropic Claude (`claude-opus-5-5`) | Analiz, özet, quiz, öğretmen, notlandırma, hikâye, kavram bağlantısı | **Ücretli** (token başına). API anahtarı sunucuda (`ANTHROPIC_API_KEY`), uygulamada yok. Varsayılan mod değildi; Supabase yapılandırılmış ve giriş yapılmış olmalıydı. |
| `LocalHeuristicEngine` | Yapay zekâsız özet (frekans tabanlı cümle seçimi), kavram çıkarımı, soru üretimi, anahtar kelime notlandırma, senaryolu öğretmen | Ücretsiz, cihazda |
| `MockAiProvider` | Geliştirme/test için sahte sağlayıcı | Ücretsiz |
| ML Kit Text Recognition (paketli model) | Taranmış PDF için OCR | Ücretsiz, cihazda, ağ yok |
| PdfBox-Android, jsoup | PDF ve EPUB metin çıkarımı | Ücretsiz (Apache-2.0 / MIT) |
| `LanguageDetector` | Dil tespiti (stop-word oranı) | Ücretsiz, kural tabanlı |
| Supabase (Auth, PostgREST, Storage) | İsteğe bağlı hesap ve senkronizasyon | Supabase'in ücretsiz katmanı; yapay zekâ değil. Varsayılan derlemede yapılandırılmamış. |
| Anlamsal arama, RAG, çeviri, TTS, STT, görsel üretimi | Yoktu | — |

Uygulamaya gömülü API anahtarı yoktu. Tek ücretli parça bulut yapay zekâ yoluydu.

## 2. Yapılan geçiş

| Özellik | Ücretsiz birincil seçenek | Yapay zekâsız yedek | Lisans / maliyet |
|---|---|---|---|
| Kitap/bölüm özeti, kavram çıkarımı, quiz, öğretmen, notlandırma | **Telefondaki model**: Google **LiteRT-LM** motoru + uygulama içinden indirilen açık model (önerilen Gemma 4 E2B) | **TextRank** ile kitaptaki cümleleri seçme, tanım kalıplarıyla kavram, kitaptan soru üretimi | LiteRT-LM Apache-2.0; Gemma 4 E2B/E4B ve Qwen3 0.6B Apache-2.0. Maliyet 0 |
| Uzun kitap işleme | Bölüm → parça (map) → bölüm analizi → kitap özeti (reduce). Parça boyutu modelin bağlamına göre (`LearningAi.chunkChars`); her istekte istem, cevap için yer bırakacak şekilde kısaltılır (`AiProvider.inputBudgetFor`, `fitToBudget`) | Bölüm başına TextRank | 0 |
| Anlamsal arama / RAG ("Kitaba sor") | **BM25** ile ilgili paragrafları bulma + modelin yalnızca bu paragraflardan, kaynak numarasıyla cevaplaması | BM25 + soruyla en çok örtüşen kitap cümlelerini alıntılama | 0. Gömme modeli eklenmedi (aşağıda) |
| PDF metin çıkarımı | PdfBox-Android | Metin yoksa "OCR gerekli" uyarısı | Apache-2.0 |
| Taranmış PDF / OCR | ML Kit Text Recognition (paketli Latin modeli) | OCR gereksinimi bildirilir | Ücretsiz, cihazda |
| EPUB | jsoup ile ZIP/OPF/XHTML ayrıştırma | — | MIT |
| Dil algılama | Kural tabanlı + kullanıcının uygulama dili | — | 0 |
| Sesli okuma (TTS) | Android'in yerleşik TextToSpeech motoru ("Sesli dinle") | Ses yoksa düğme pasif | İşletim sistemi |
| Özet kalite kontrolü | **Sadakat denetimi**: özetteki sayı ve özel adlar kitapta aranır; alıntı doğrulama; kesilen JSON'un tamamlanmış kısmının kurtarılması (`AiJson.repairTruncated`) | Aynı denetimler | 0 |
| Kapak görseli | Kitap kimliğinden üretilen degrade kapak | — | 0 |
| Bulut yapay zekâ (Claude) | **Varsayılan derlemeden çıkarıldı**; yalnızca `bookmind.enableCloudAi=true` ile derlenen sürümlerde görünür | — | Ücretli; kapalı |
| ~~Bilgisayardaki model (Ollama/llama.cpp)~~ | **Kaldırıldı**: kullanıcı ek kurulum istemedi | — | — |

### Model indirme (uygulama içinden)

- **Katalog** (`ModelCatalog`): her model Hugging Face `litert-community` deposunda **belirli bir sürüme (commit)
  sabitlenmiş** URL, bayt boyutu ve SHA-256 ile tanımlıdır. Hesap/oturum gerektirmeyen (gated olmayan) depolar seçildi.
- **Telefona göre öneri:** RAM ≥ 5 GB (satışta "6 GB") → Gemma 4 E2B; ≥ 10 GB → E4B da seçilebilir; 2,5–5 GB → Qwen3 0.6B;
  daha az → yapay zekâ önerilmez, klasik yöntemler kullanılır. Belleğe sığmayan model seçilemez.
- **İndirme** (`ModelDownloadWorker` + `ModelDownloader`): WorkManager ön plan işi, bildirimde ilerleme; bağlantı
  koparsa `.part` dosyası korunur ve HTTP Range ile **kaldığı yerden devam eder** (8 deneme, üstel bekleme); sunucu
  aralığı yok sayarsa baştan başlar. Bitince boyut ve **SHA-256** doğrulanır; tutmazsa dosya silinir. Mobil veride
  indirme öncesi onay istenir. Boş alan yetersizse başlamaz ve gereken alanı söyler.
- **Çalıştırma** (`OnDeviceModels`): LiteRT-LM `Engine` CPU arka ucuyla açılır (her arm64 telefonda çalışır), ilk
  açılıştaki ağırlık önbelleği silinebilir önbellek klasörüne yazılır; her istek geçmişsiz yeni bir konuşmadır,
  "düşünme" modu kapalıdır. Motor 3 dakika kullanılmazsa bellekten çıkarılır.
- **Neden JSON kısıtlı üretim yok:** LiteRT-LM'in `ResponseFormat` ile dilbilgisi kısıtlı çıktısı Gemma 4'te bozuk
  çıktıya yol açtı (`{"summary":": ...` döngüsü, 5000 karakterde kesilme). Kısıtsız üretim temiz JSON verdi; hat çıktıyı
  ayıklayıp doğruluyor, geçersizse bir kez düzeltme istiyor.

### Güvenceler

- **Sessiz ücretli geri dönüş yok.** Model bir şeyi üretemezse (`AiException`) `LearningAi` yapay zekâsız yönteme geçer
  ve ekranda "Yapay zekâ yanıtı alınamadı (…); klasik yöntem kullanıldı." uyarısı görünür. Bulut sağlayıcı varsayılan
  derlemede hiç oluşturulmaz (`BuildConfig.CLOUD_AI_ENABLED = false`).
- **Kitap metni telefondan çıkmaz.** Yapay zekâ için tek ağ bağlantısı modelin indirilmesidir; istekte kullanıcıya ya da
  kitaba ait hiçbir veri yoktur. Ağ yapılandırması yeniden **yalnızca HTTPS**'tir.
- **Ücretsiz modeller sayılmaz.** Günlük istek sınırı ve maliyet göstergesi yalnızca ücretli sağlayıcıya uygulanır.
- **Donanıma zorlama yok.** Belleği yetmeyen telefonda büyük model seçilemez; bellek yetmezse (OOM) anlaşılır bir
  uyarıyla klasik yönteme dönülür.

### Neden gömme (embedding) modeli yok

Çok dilli bir cümle gömme modeli ayrı bir indirme ve çalışma zamanı ister. Tek kitap içinde soru-cevapta BM25'in
Türkçe ek kırpmalı terimlerle yeterli sonuç verdiği testlerde görüldü (`FreeAiTest`).

## 3. Donanım ve modeller

| Model | Dosya | Lisans | Önerilen telefon | Not |
|---|---|---|---|---|
| **Gemma 4 E2B** (önerilen) | 2,6 GB `.litertlm` | Apache-2.0 | 6 GB+ RAM, arm64 | Model kartına göre S26 Ultra CPU: ~41–47 token/sn, ~1,4–1,7 GB bellek. Türkçesi güçlü |
| Gemma 4 E4B | 3,7 GB | Apache-2.0 | 12 GB+ RAM | En iyi kalite, daha yavaş |
| Qwen3 0.6B | 0,6 GB | Apache-2.0 | 3–4 GB RAM | Hafif; kalite belirgin düşük |

Lisanslar Hugging Face kart verisinden (`cardData.license`) doğrulandı; depoların hiçbiri giriş/onay istemiyor.
Değerlendirilip **seçilmeyenler:** Gemma 3 1B (Gemma şartları + giriş gerekli), Qwen2.5 3B (ticari olmayan lisans),
Qwen2.5 1.5B (Apache-2.0 ama Gemma 4 E2B'den daha fazla bellek kullanıyor ve Türkçesi daha zayıf, önceki ölçüm aşağıda),
Phi-4-mini (3,9 GB; Türkçe zayıf), LFM2.5 (özel lisans).

## 4. Değerlendirme

Aynı iki demo kitapta (Türkçe "Öğrenmenin Bilimi", İngilizce "The Science of Learning"; 5'er bölüm) eski yöntem,
yeni yapay zekâsız yöntem ve **telefona indirilen modelin kendisi** karşılaştırıldı. Model, uygulamadaki motorun
masaüstü sürümüyle (LiteRT-LM `litertlm-jvm` 0.18.0, aynı `.litertlm` dosyası, CPU) çalıştırıldı; ölçüm ortamı 4
çekirdekli sunucu CPU'su (GPU/NPU yok), dolayısıyla **süreler telefondakinden uzundur** (model kartı: S26 Ultra CPU'da
~41–47 token/sn; bu sunucuda ~9 token/sn).

Komut: `BOOKMIND_LITERTLM_MODEL=/yol/gemma-4-E2B-it.litertlm BOOKMIND_LITERTLM_CONTEXT=8192 ./gradlew :core:test --tests '*LocalModelEvaluationTest*'`
(rapor: `core/build/reports/local-model-evaluation-<model>.md`, ham çıktılar `…-raw.txt`).

Ölçütler: *kavram kapsamı* = kitabın 5 ana kavramından özette geçen sayısı; *tekrar* = özet cümleleri arasındaki ortalama
Jaccard benzerliği (düşük daha iyi); *kitapta olmayan sayı/isim* = sadakat denetiminin bulduğu desteksiz öğeler;
*doğrulanan alıntı* = modelin verdiği kaynak alıntılarından kitapta birebir bulunanlar.

### Gemma 4 E2B (önerilen model)

Model: `gemma4-e2b`, bağlam 8192 token, LiteRT-LM (CPU), yükleme 1425 ms

#### Öğrenmenin Bilimi (tr)

| Yöntem | Kelime | Kavram kapsamı (/5) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |
|---|---|---|---|---|---|---|
| Eski: frekans (yapay zekâsız) | 107 | 5 | 0.043 | 0 | — | 56 ms |
| Yeni: TextRank (yapay zekâsız) | 103 | 5 | 0.048 | 0 | — | 22 ms |
| Telefon modeli (gemma4-e2b, map-reduce) | 445 | 5 | 0.038 | 1 | 0/5 | 696257 ms |

- Bölüm analizi: 5/5 bölüm modelle, kalan yapay zekâsız yöntemle (geçersiz çıktı → otomatik yerel yedek).
- Özet uyarıları: Bir alıntı kitapta doğrulanamadı.; Kitapta geçmeyen ifadeler: Ebbinghaus'un, Eğrisinin, Yönetimi. Bunlar modelin çıkarımı ya da hatası olabilir.
- Quiz: 3 geçerli soru, yapay zekâ: true (125177 ms)
- Soru-cevap ("Aralıklı tekrar neden işe yarar?"): yapay zekâ: true, kaynak: [0, 2], kitapta: true (14535 ms)

> Aralıklı tekrar, bir bilgiyi giderek uzayan aralıklarla yeniden çalışmaya dayanır. Bu yöntemle, bir kavram ilk gün, sonra üç gün sonra, bir hafta sonra ve bir ay sonra tekrar edilir. Bu şekilde, bilgi unutulmaya başladığı anda tekrar edilmesi sayesinde bilgiyi daha iyi korur.

Model özetinden:

> Bu bölüm, bilginin zaman içinde ne kadar hızlı unutulduğunu gösteren bir modeli ele alır. Hermann Ebbinghaus'un kendi deneyleriyle ortaya koyduğu bu eğri, bilginin unutulmasının rastgele olmadığını, aksine bilginin anlamlılığına, önceki bilgilerle ilişkisine ve duygusal öneme bağlı olarak belirli bir düzen içinde gerçekleştiğini gösterir. Temel çıkarım, bilgiyi unutma sürecini yönetmenin anahtarı, bilgiyi doğru zamanlarda tekrar etmektir. Unutma eğrisi, bilginin zaman içindeki unutulma hızını modeller. Unutma hızı; bilginin anlamlılığına, bağlantılara ve duygusal öneme bağlıdır. Bilgiyi doğru …

#### The Science of Learning (en)

| Yöntem | Kelime | Kavram kapsamı (/5) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |
|---|---|---|---|---|---|---|
| Eski: frekans (yapay zekâsız) | 160 | 5 | 0.040 | 0 | — | 11 ms |
| Yeni: TextRank (yapay zekâsız) | 140 | 5 | 0.036 | 0 | — | 9 ms |
| Telefon modeli (gemma4-e2b, map-reduce) | 656 | 5 | 0.030 | 0 | 0/5 | 621246 ms |

- Bölüm analizi: 5/5 bölüm modelle, kalan yapay zekâsız yöntemle (geçersiz çıktı → otomatik yerel yedek).
- Özet uyarıları: A quote could not be verified in the book.; Not found in the book: 100. These may be the model's inference or a mistake.
- Quiz: 3 geçerli soru, yapay zekâ: true (103765 ms)
- Soru-cevap ("Why does spaced repetition work?"): yapay zekâ: true, kaynak: [1, 2], kitapta: true (16410 ms)

> Spaced repetition works because it involves reviewing information at gradually increasing intervals, such as on the first day, then three days later, a week later, and finally a month later. This timing ensures that each review occurs just as the information is about to be forgotten, which helps to catch the information just before it is lost. Furthermore, successful reviews extend how long the information remains in memory, which is the foundation of spaced repetition.

Model özetinden:

> This chapter introduces the forgetting curve, which shows how quickly learned material decays over time. Hermann Ebbinghaus first documented this by observing that most information learned quickly is lost shortly after initial study. The rate at which information fades is not constant; it is influenced by the material's relevance, how well it connects to prior knowledge, and its emotional impact. The key takeaway is that to combat this natural decay, strategic review is essential. The forgetting curve models the rapid loss of learned information. Forgetting rates depend on information meaning,…

### Qwen3 0.6B (hafif model, 3–4 GB RAM'li telefonlar), Türkçe kitap

| Yöntem | Kelime | Kavram kapsamı (/5) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |
|---|---|---|---|---|---|---|
| Eski: frekans (yapay zekâsız) | 107 | 5 | 0.043 | 0 | — | 56 ms |
| Yeni: TextRank (yapay zekâsız) | 103 | 5 | 0.048 | 0 | — | 15 ms |
| Telefon modeli (qwen3-06b, map-reduce) | 204 | 5 | 0.031 | 6 | 0/0 | 377104 ms |

- Bölüm analizi: 5/5 bölüm modelle, kalan yapay zekâsız yöntemle (geçersiz çıktı → otomatik yerel yedek).
- Özet uyarıları: Kitapta geçmeyen ifadeler: 0, 1, 2, 3, 4, Diferençli. Bunlar modelin çıkarımı ya da hatası olabilir.
- Quiz: 4 geçerli soru, yapay zekâ: true (143961 ms)
- Soru-cevap ("Aralıklı tekrar neden işe yarar?"): yapay zekâ: true, kaynak: [0, 2], kitapta: true (23273 ms)

Hafif model de bütün görevleri yapay zekâyla tamamladı (bölüm analizleri, ilk ölçümde şema karışıklığı yüzünden 0/5 iken
`LenientChapterAnalysis` ile 5/5). Ancak özette yazım hataları ("snavdan"), kitapta olmayan bir ifade ("Diferençli") ve
özet malzemesinden kopyalanan "[0] Kavramlar:" işaretleri görüldü; sadakat denetimi bunları işaretledi. Bu yüzden yalnızca
Gemma 4'ün sığmadığı telefonlarda önerilir.

### Yorum

- **Gemma 4 E2B** her iki kitapta 5/5 bölümü ilk denemede geçerli JSON ile analiz etti, özet 5/5 kavramı kapsadı ve
  üç yöntemin **en düşük tekrar** oranını verdi (TR 0.038, EN 0.030). Özet kitabın cümlelerini kopyalamak yerine kendi
  cümleleriyle, akıcı ve dil bilgisi doğru Türkçe yazıldı. Quiz soruları ve kaynaklı soru-cevap yapay zekâyla üretildi;
  "Kitaba sor" cevabı 15–16 sn sürdü (bu sunucuda).
- **Dürüst gözlemler:** Özetlerdeki kaynak alıntıları birebir değil (0/5 doğrulandı) — doğrulayıcı bunları kaldırıp
  uyarı gösteriyor, bölüm bağlantıları kalıyor. İngilizce özette kitapta geçmeyen "100" sayısı işaretlendi (modelin
  çıkarımı). Ayrıntılı özet çıktı sınırına (2000 token) dayandı; kesilen JSON'un tamamlanmış bölümleri kurtarıldı.
- **Bu ölçümle düzeltilen hatalar:** (1) LiteRT-LM'in JSON kısıtlı üretimi Gemma 4'te bozuk çıktı verdiği için kapatıldı;
  (2) özet çıktı sınırı yükseltildi ve istem bütçesi her isteğin cevap uzunluğuna göre hesaplanıyor; (3) model bir soru
  türünü "pairs" diye adlandırınca bütün quiz reddediliyordu — türler artık eş anlamlılarıyla eşleniyor, okunamayan tek
  soru atılıyor ve yalnızca kullanıcının seçtiği türler tutuluyor; (4) sadakat denetimi Türkçe ekli adları
  ("Ebbinghaus'un") yanlışlıkla "kitapta yok" sayıyordu; (5) yapay zekâsız kavram çıkarımı tekrar eden fiil
  biçimlerini ("öğrenilen") kavram sanıyordu.

### Önceki ölçüm: Qwen2.5 1.5B (llama.cpp, bilgisayar modu — kaldırıldı)

#### Öğrenmenin Bilimi (tr)

| Yöntem | Kelime | Kavram kapsamı (/5) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |
|---|---|---|---|---|---|---|
| Eski: frekans (yapay zekâsız) | 107 | 5 | 0.043 | 0 | — | 25 ms |
| Yeni: TextRank (yapay zekâsız) | 103 | 5 | 0.048 | 0 | — | 13 ms |
| Yerel LLM (qwen2.5-1.5b, map-reduce) | 150 | 5 | 0.061 | 0 | 0/0 | 627477 ms |

- Bölüm analizi: 5/5 bölüm yerel modelle, kalan yerel motorla (geçersiz çıktı → otomatik yerel yedek).
- Özet uyarıları: yok
- Quiz: 4 geçerli soru, yapay zekâ: true (127226 ms)
- Soru-cevap ("Aralıklı tekrar neden işe yarar?"): yapay zekâ: true, kaynak: [0], kitapta: false (18255 ms)

> Aralıklı tekrar, bir bilgiyi giderek uzayan aralıklarla yeniden çalışmaya dayanan bir öğrenme yöntemidir. Her tekrar bilgiyi unutulmaya yaklaştığı anda yakalar.

Yerel LLM özetinden:

> Unutma eğrisi, bilgimizin zaman içinde kaybolan hızını gösterir. Ebbinghaus' deneyleri, bilgimizin hemen sonraki saatlerde kaybı olduğunu ve kaybın yavaşlantığını görebiliriz. Unutma eğrisi: Bilgimizin zaman içinde kaybolan hızını gösterir Ebbinghaus' deneyleri: Bilgimizin hemen sonraki saatlerde kaybı olduğunu ve kaybın yavaşlantığını görebiliriz Aralıklı tekrar, bir bilgiyi giderek uzayan aralıklarla yeniden çalışmaya dayanan bir öğrenme yöntemidir. Toplu çalışma ile aralıklı çalışma arasındaki fark birçok deneyde ölçülmüştür. Aralıklı tekrar: Aralıklarla yeniden çalışmak Toplu çalışma vs. A…

#### The Science of Learning (en)

| Yöntem | Kelime | Kavram kapsamı (/5) | Tekrar (Jaccard ort.) | Kitapta olmayan sayı/isim | Doğrulanan alıntı | Süre |
|---|---|---|---|---|---|---|
| Eski: frekans (yapay zekâsız) | 160 | 5 | 0.040 | 0 | — | 6 ms |
| Yeni: TextRank (yapay zekâsız) | 140 | 5 | 0.036 | 0 | — | 6 ms |
| Yerel LLM (qwen2.5-1.5b, map-reduce) | 246 | 5 | 0.043 | 0 | 0/5 | 465969 ms |

- Bölüm analizi: 4/5 bölüm yerel modelle, kalan yerel motorla (geçersiz çıktı → otomatik yerel yedek).
- Özet uyarıları: A quote could not be verified in the book.
- Quiz: 4 geçerli soru, yapay zekâ: true (100634 ms)
- Soru-cevap ("Why does spaced repetition work?"): yapay zekâ: true, kaynak: [0], kitapta: true (11740 ms)

> Spaced repetition works by reviewing information at gradually increasing intervals, catching it just as it is about to be forgotten. This method helps in consolidating the memory and improving recall over time.

Yerel LLM özetinden:

> The forgetting curve, observed by Hermann Ebbinghaus, describes how quickly learned information fades over time. He measured his memory of nonsense syllables and found that most was lost in the first hours and then slowed down thereafter. Ebbinghaus's Forgetting Curve: Most forgotten within the first hours Nonsense Syllables as a Measure: Ebbinghaus used these to observe forgetting patterns Spaced repetition is a learning method that reviews information at gradually increasing intervals, catching it just before forgetting. It uses stability and difficulty values to improve recall. Stability: H…

#### Yorum

- **TextRank, eski frekans yöntemine göre** aynı kapsamı daha kısa özetle veriyor (TR 107→103, EN 160→140 kelime) ve
  İngilizcede tekrarı azaltıyor (0.040→0.036). Türkçede tekrar 0.043→0.048 ile pratikte aynı. İki yöntem de yalnızca kitap
  cümlelerini seçtiği için uydurma içermez; fark küçük ama hız ve kararlılık korunuyor.
- **Yerel 1.5B model** iki kitapta da 5/5 kavramı kapsadı, uydurma sayı/isim üretmedi ve kendi cümleleriyle akıcı bir özet
  yazdı; ayrıca 4 geçerli quiz sorusu ve kaynaklı cevap üretti. Bedeli süre: CPU'da bir kitap özeti 8–10 dakika (arka plan
  işi olarak çalışır).
- **Zayıf yanlar (dürüst gözlem):** Türkçe çıktıda dil bilgisi hataları var ("yavaşlantığını"); İngilizcede model kaynak
  alıntılarını birebir değil özetleyerek verdiği için 5 alıntının hiçbiri doğrulanamadı — doğrulayıcı bunları yakalayıp
  kaldırdı ve uyarı gösterdi. İngilizcede 1 bölüm analizi geçersiz JSON nedeniyle otomatik olarak yapay zekâsız yönteme
  düştü. Türkçe soru-cevapta model doğru, kaynaklı cevap verdiği hâlde `inBook=false` döndü (küçük modelin alan
  karıştırması). Bu zayıflıklar ve kullanıcının ek kurulum istememesi üzerine bilgisayar modu kaldırıldı; telefonda
  Türkçesi çok daha iyi olan Gemma 4 E2B'ye geçildi.

## 5. Testler

- `FreeAiTest`: telefondaki model sağlayıcısı (maliyet 0, model adına göre önbellek anahtarı, isteğe göre istem bütçesi,
  cevabın bağlamın yarısını geçmemesi, hata eşleme, "düşünme" bloklarının temizlenmesi), istem kırpma, model hatasında
  ücretli değil yerel yönteme düşme, **kesilen JSON'un kurtarılması**, BM25 (Türkçe ekler), çıkarımsal cevap, sadakat
  denetimi, TextRank.
- `ModelDownloadTest` (uygulama): tam indirme + ilerleme + SHA-256, **kaldığı yerden devam** (Range başlığı), aralığı yok
  sayan sunucuda baştan başlama, hata durumunda kısmi dosyanın korunması, bozuk dosyanın reddi; katalogdaki her modelin
  Apache-2.0, sürüme sabitlenmiş URL ve geçerli SHA-256 taşıması; RAM'e göre öneri.
- `AppUiTest.ai_assistant_offers_a_free_on_device_model_from_home_and_profile`: ana ekran kartı, "Şimdi değil", Profil →
  Yapay zekâ asistanı, model listesi, 8 GB telefonda E4B'nin "bellek az" uyarısı, model seçimi indirme başlatmaz.
- `LocalEngineTest`: tekrar eden Türkçe fiil biçimleri ("öğrenilen", "kullanılabilmesidir") kavram sayılmaz.
- `LocalModelEvaluationTest`: **gerçek model dosyası** ile tüm hat, uygulamadaki motorun masaüstü sürümüyle
  (`litertlm-jvm`); `BOOKMIND_LITERTLM_MODEL` verilmezse atlanır.

## 6. Sınırlamalar

- Model indirme, motor ve tüm yapay zekâ hattı JVM'de gerçek modelle test edildi; ancak bu ortamda fiziksel Android
  telefon olmadığı için **telefonda** çalıştırma (bellek, hız, ısınma) doğrulanmadı. Hız rakamları model kartından
  alınmıştır; bu sunucudaki 4 çekirdekli CPU ölçümleri telefondan yavaştır.
- Model ilk kullanımda belleğe yüklenir (birkaç saniye); orta seviye telefonlarda bir bölüm analizi 30–90 sn sürebilir.
  Kitap işleme arka planda, bildirimle sürer.
- Model indirildikten önce eklenmiş kitapların bölüm analizleri klasik yöntemle yapılmış olarak kalır; özet, quiz,
  öğretmen ve "Kitaba sor" her istekte modeli kullanır.
- Qwen3 0.6B hafif telefonlar içindir; kalitesi Gemma 4'e göre belirgin düşüktür.
- Supabase Edge Function kodu (`supabase/functions/ai`) depoda kaldı ama varsayılan derleme onu hiç çağırmaz.
