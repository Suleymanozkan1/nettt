# Ücretsiz yapay zekâ: envanter, geçiş ve sonuçlar

Bu belge "Kitap Özetleyici – Ücretsiz AI Alternatifleri" ek görevinin raporudur. Amaç, BookMind AI'daki her
yapay zekâ özelliğinin ücretsiz, mümkün olduğunca yerel ve çevrimdışı çalışması; ücretli servislerin varsayılan
olarak tamamen devre dışı kalmasıdır.

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
| Kitap/bölüm özeti | Yerel LLM: **bilgisayardaki model** (Ollama, llama.cpp `llama-server`, LM Studio; OpenAI uyumlu API) veya **telefondaki model** (MediaPipe LLM Inference) | **TextRank** (yeni) ile kitaptaki cümleleri seçme; kaynak etiketli | MediaPipe Apache-2.0; modeller: Qwen2.5 1.5B/7B Apache-2.0, Gemma 3 "Gemma Terms of Use". Maliyet 0 |
| Uzun kitap işleme | Bölüm → parça (map) → bölüm özeti → kitap özeti (reduce). Parça boyutu modelin bağlamına göre ayarlanır (`LearningAi.chunkChars`), istem bağlamı aşarsa yalnızca kitap alıntıları kısaltılır (`fitToBudget`) | Bölüm başına TextRank | 0 |
| Anlamsal arama / RAG ("Kitaba sor", yeni) | **BM25** ile ilgili paragrafları bulma + yerel LLM'in yalnızca bu paragraflardan, kaynak numarasıyla cevaplaması | BM25 + soruyla en çok örtüşen kitap cümlelerini alıntılama (üretim yok) | 0. Gömme (embedding) modeli eklenmedi; gerekçe aşağıda |
| PDF metin çıkarımı | PdfBox-Android (değişmedi) | Metin yoksa "OCR gerekli" uyarısı | Apache-2.0 |
| Taranmış PDF / OCR | ML Kit Text Recognition, paketli Latin modeli (değişmedi) | Kullanıcıya OCR gereksinimi bildirilir | Ücretsiz, cihazda; ML Kit şartları |
| EPUB | jsoup ile ZIP/OPF/XHTML ayrıştırma (değişmedi) | — | MIT |
| Anahtar kelime / kavram | Yerel LLM (bölüm analizi) | Tanım kalıpları + terim frekansı (mevcut) | 0 |
| Dil algılama | Kural tabanlı (mevcut) + kullanıcının uygulama dili seçimi | — | 0 |
| Sesli okuma (TTS, yeni) | Android'in yerleşik TextToSpeech motoru (özet ve bölüm ekranında "Sesli dinle") | Ses yoksa düğme pasif | İşletim sistemi; uygulama veri göndermez |
| Ses → metin (STT) | Eklenmedi (uygulamada böyle bir özellik yok) | — | — |
| Çeviri | Eklenmedi; uygulama dili seçimi ve yerel modelin istenen dilde yazması yeterli görüldü | — | — |
| Kapak görseli | Görsel modeli yok: kitap kimliğinden üretilen degrade kapak (mevcut tasarım) | — | 0 |
| Özet kalite kontrolü | Yerel modelin çıktısı için **deterministik sadakat denetimi** (yeni): özetteki her sayı ve özel ad kitapta aranır, bulunamayanlar uyarı olarak gösterilir. Alıntı doğrulama (mevcut) | Aynı denetimler | 0 |
| Bulut yapay zekâ (Claude) | **Varsayılan derlemeden çıkarıldı.** Yalnızca `bookmind.enableCloudAi=true` ile derlenen sürümlerde görünür | — | Ücretli; kapalı |

### Güvenceler

- **Sessiz ücretli geri dönüş yok.** Seçilen yerel model çalışmazsa (`AiException`), `LearningAi` yapay zekâsız
  yönteme geçer ve ekranda "Yapay zekâ yanıtı alınamadı (…); yerel yöntem kullanıldı." uyarısı gösterilir. Bulut
  sağlayıcı varsayılan derlemede hiç oluşturulmaz (`BuildConfig.CLOUD_AI_ENABLED = false`).
- **Kitap metni dışarı çıkmaz.** "Bilgisayarımdaki model" adresi yalnızca yerel ağ adreslerini kabul eder
  (`LocalEndpoint`: localhost, 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA, `*.local`). İnternetteki bir sunucu
  hem ücretli olabileceği hem de metni dışarı göndereceği için reddedilir.
- **Uygulama model indirmez.** Telefondaki model için kullanıcı, lisansını kendisi kabul ettiği bir dosyayı seçer.
- **Ücretsiz modeller sayılmaz.** Günlük istek sınırı ve maliyet göstergesi yalnızca ücretli sağlayıcıya uygulanır
  (`AiProvider.free`).
- **Ağ yapılandırması:** Ollama/llama.cpp yerel ağda düz HTTP sunduğu için `cleartextTrafficPermitted=true`.
  Android IP aralığına göre izin veremediğinden kısıtlama kodda yapılır: yerel model istemcisi yalnızca özel ağ
  adreslerine, senkronizasyon istemcisi yalnızca `https://` adreslerine bağlanır.

### Neden gömme (embedding) modeli yok

Telefonda çok dilli bir cümle gömme modeli (ör. multilingual MiniLM) 100+ MB ek indirme ve ayrı bir çalışma zamanı
gerektirir. Tek kitap içinde soru-cevap için BM25'in Türkçe ek kırpmalı terimlerle yeterli sonuç verdiği testlerde
görüldü (`FreeAiTest`). Gerekirse bir sonraki adım olarak eklenebilir.

## 3. Donanım ve kurulum

| Seçenek | Donanım | Kurulum | Beklenen hız |
|---|---|---|---|
| Yapay zekâsız | Her telefon | Yok | Anlık |
| Bilgisayarımdaki model | Bilgisayar: 8 GB RAM ile 1.5–3B, 16 GB ile 7B (4-bit). GPU şart değil | `ollama pull qwen2.5:7b` → `OLLAMA_HOST=0.0.0.0 ollama serve`; uygulamada adres `http://<bilgisayar-ip>:11434`, model adı, "Bağlantıyı test et" | 4 çekirdekli CPU, 1.5B model: ~9 token/sn (aşağıdaki ölçüm) |
| Telefondaki model (deneysel) | arm64 telefon, 6 GB+ RAM önerilir | Hugging Face `litert-community/Qwen2.5-1.5B-Instruct` (Apache-2.0) veya `litert-community/Gemma3-1B-IT` (Gemma şartları) `.task` dosyası → Profil → Yapay zekâ → "Model dosyası seç" | Cihaza bağlı; ölçülmedi |

Önerilen modeller ve lisansları (Hugging Face kart verisinden doğrulandı):

| Model | Lisans | Not |
|---|---|---|
| Qwen2.5-1.5B-Instruct (GGUF / .task) | Apache-2.0 | Bu rapordaki ölçümlerde kullanıldı |
| Qwen2.5-7B-Instruct | Apache-2.0 | Bilgisayar için önerilen kalite seviyesi |
| Qwen2.5-3B-Instruct | "Qwen Research" (ticari olmayan) | **Önerilmez**: lisansı ticari kullanımı kısıtlar |
| Gemma 3 1B IT (.task) | Gemma Terms of Use | Kullanım kısıtları içerir; kullanıcı kendisi kabul eder |

## 4. Değerlendirme

<!-- EVALUATION -->

## 5. Testler

- `FreeAiTest` (10 test): yerel sunucu sağlayıcısının OpenAI uyumlu isteği (JSON modu, API anahtarı yok, maliyet 0),
  hata eşlemesi ve yeniden deneme, model listesi, yalnızca yerel ağ adresleri, telefondaki model için sohbet
  şablonları, istem kırpma, yerel model hatasında ücretli değil yerel yönteme geri dönüş, BM25 erişimi (Türkçe ekler),
  çıkarımsal cevap, sadakat denetimi, TextRank'in kararlılığı ve tekrar engeli.
- `LocalModelEvaluationTest`: gerçek bir yerel sunucuya karşı tüm hat; `BOOKMIND_LOCAL_LLM_URL` verilmediğinde atlanır.
- `AiPipelineTest`: günlük sınırın yalnızca ücretli sağlayıcıya uygulandığı doğrulandı.

## 6. Sınırlamalar

- Telefondaki model yolu derleniyor ve sarmalayıcı mantığı test edildi, ancak bu ortamda fiziksel cihaz olmadığı
  için MediaPipe ile gerçek bir modelin telefonda çalıştırılması **doğrulanmadı**. Bazı `.task` paketleri kendi
  istem şablonunu içerebilir; uygulama model adından şablon tahmin eder (Gemma, ChatML/Qwen, Llama 3, Phi).
- 1–2B parametreli modeller Türkçe özetlerde dil hataları yapabilir ve JSON'u bozabilir; bu durumda doğrulayıcı
  çıktıyı reddeder, bir kez düzeltme ister, olmazsa yapay zekâsız yönteme geçer. Kalite için bilgisayarda 7B model önerilir.
- Supabase Edge Function kodu (`supabase/functions/ai`) depoda kaldı ama varsayılan derleme onu hiç çağırmaz.
