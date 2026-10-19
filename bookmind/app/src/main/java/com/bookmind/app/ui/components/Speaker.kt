package com.bookmind.app.ui.components

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.core.text.Sentences

/**
 * Read-aloud with the phone's built-in text-to-speech engine: free, offline when the voice is installed, and
 * nothing is sent to a server by the app. Long texts are queued in sentence-aligned chunks below the engine limit.
 */
class Speaker(context: Context) {
    enum class State { LOADING, READY, SPEAKING, UNAVAILABLE }

    var state by mutableStateOf(State.LOADING)
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        state = if (status == TextToSpeech.SUCCESS) State.READY else State.UNAVAILABLE
        if (state == State.READY) configure()
    }

    private fun configure() {
        val result = tts.setLanguage(AppLanguage.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) state = State.UNAVAILABLE
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { state = State.SPEAKING }
            override fun onDone(utteranceId: String?) { if (utteranceId == LAST) state = State.READY }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { state = State.READY }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { state = State.READY }
        })
    }

    fun speak(text: String) {
        if (state == State.UNAVAILABLE || state == State.LOADING) return
        tts.stop()
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtMost(3_500)
        val chunks = mutableListOf(StringBuilder())
        Sentences.split(text).map { it.text }.ifEmpty { listOf(text) }.forEach { s ->
            if (chunks.last().length + s.length + 1 > limit) chunks += StringBuilder()
            chunks.last().append(s.take(limit)).append(' ')
        }
        chunks.forEachIndexed { i, c ->
            tts.speak(c.toString(), TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) LAST else "part$i")
        }
    }

    fun stop() { tts.stop(); if (state == State.SPEAKING) state = State.READY }
    fun shutdown() = tts.shutdown()

    private companion object { const val LAST = "last" }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}

/** Top-bar button: read [text] aloud / stop. Hidden while the engine loads; disabled when no voice exists. */
@Composable
fun ListenButton(speaker: Speaker, text: () -> String) {
    when (speaker.state) {
        Speaker.State.LOADING -> Unit
        Speaker.State.UNAVAILABLE -> IconButton({}, enabled = false) { Icon(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.listen_unavailable)) }
        Speaker.State.SPEAKING -> IconButton(speaker::stop) { Icon(Icons.Filled.Stop, stringResource(R.string.listen_stop)) }
        Speaker.State.READY -> IconButton({ speaker.speak(text()) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.listen)) }
    }
}
