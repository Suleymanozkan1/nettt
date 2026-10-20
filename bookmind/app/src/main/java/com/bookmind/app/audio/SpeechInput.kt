package com.bookmind.app.audio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bookmind.app.data.settings.AppLanguage

/**
 * Speech-to-text for spoken answers with the phone's own recognizer. The fully on-device recognizer is used when
 * the phone has one (Android 12+); otherwise the system recognizer is asked to stay offline. Typing the answer
 * always works as well, so a phone without speech recognition can still take the oral exam.
 */
class SpeechInput(private val context: Context) {
    enum class State { IDLE, LISTENING, PROCESSING, UNAVAILABLE }

    var state by mutableStateOf(if (SpeechRecognizer.isRecognitionAvailable(context)) State.IDLE else State.UNAVAILABLE)
        private set
    /** Words recognised so far (partial results while listening, then the final text). */
    var text by mutableStateOf("")
        private set
    /** Microphone level 0..1 for the listening animation. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<Int?>(null)
        private set

    /** True when recognition runs completely on the phone. */
    val onDevice: Boolean = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    private var recognizer: SpeechRecognizer? = null
    private var onFinal: ((String) -> Unit)? = null

    private fun create(): SpeechRecognizer? = runCatching {
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)
    }.getOrNull()?.apply { setRecognitionListener(listener) }

    /** Starts listening; [onResult] gets the final text (empty when nothing was understood). Call on the main thread. */
    fun start(onResult: (String) -> Unit) {
        if (state == State.UNAVAILABLE) return
        val r = recognizer ?: create()?.also { recognizer = it } ?: run { state = State.UNAVAILABLE; return }
        onFinal = onResult
        text = ""
        error = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, AppLanguage.locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Answers are a few sentences: allow short thinking pauses before the recognizer stops.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        state = State.LISTENING
        runCatching { r.startListening(intent) }.onFailure { state = State.IDLE; error = SpeechRecognizer.ERROR_CLIENT }
    }

    /** Stops listening and delivers what was heard so far. */
    fun stop() {
        if (state == State.LISTENING) { state = State.PROCESSING; runCatching { recognizer?.stopListening() } }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        if (state != State.UNAVAILABLE) state = State.IDLE
        onFinal = null
    }

    fun release() {
        onFinal = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun finish(result: String) {
        if (state != State.UNAVAILABLE) state = State.IDLE
        level = 0f
        val callback = onFinal
        onFinal = null
        callback?.invoke(result)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { if (state == State.LISTENING) state = State.PROCESSING }
        override fun onError(code: Int) {
            // "No match" / "speech timeout" just mean nothing was said; keep any partial text.
            if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) error = code
            if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) error = code
            finish(text)
        }
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text = it }
            finish(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { text = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
