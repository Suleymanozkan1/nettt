package com.bookmind.app.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.core.ai.study.PodcastLine

/**
 * Where a position in the dialogue falls. The phone's TTS engine reports no durations, so time is estimated from
 * the text (about 14 characters per second at 1×); seeking jumps to the nearest word boundary.
 */
class PodcastTimeline(private val lines: List<PodcastLine>) {
    private val starts: List<Long>
    private val lengths: List<Long> = lines.map { msFor(it.text.length + PAUSE_CHARS) }
    val totalMs: Long

    init {
        var t = 0L
        starts = lines.indices.map { i -> t.also { t += lengths[i] } }
        totalMs = t
    }

    /** Position in ms (at 1×) of [char] within line [line]. */
    fun positionOf(line: Int, char: Int): Long =
        if (line >= lines.size) totalMs else starts[line] + msFor(char.coerceIn(0, lines[line].text.length))

    /** The line and the word-aligned character offset at [positionMs]. */
    fun locate(positionMs: Long): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        val p = positionMs.coerceIn(0, totalMs)
        val line = starts.indexOfLast { it <= p }.coerceAtLeast(0)
        if (line >= lines.size) return lines.size to 0
        val text = lines[line].text
        val raw = ((p - starts[line]) * CHARS_PER_SECOND / 1_000).toInt().coerceIn(0, text.length)
        // Back up to the start of the word so speech never starts mid-word.
        val word = text.lastIndexOf(' ', (raw - 1).coerceAtLeast(0)).let { if (it < 0 || raw == 0) 0 else it + 1 }
        return if (raw >= text.length - 1) (line + 1) to 0 else line to word
    }

    companion object {
        const val CHARS_PER_SECOND = 14.0
        private const val PAUSE_CHARS = 6
        fun msFor(chars: Int): Long = (chars * 1_000 / CHARS_PER_SECOND).toLong()
    }
}

/**
 * Plays a two-person podcast script with the phone's own text-to-speech engine: free, offline once the voice is
 * installed, and nothing leaves the device. Each speaker gets a voice profile (a different installed voice when
 * the phone has two for the language, otherwise a different pitch and pace). Lines are spoken one at a time, so
 * the transcript can follow along, and pause / ±10 s / speed changes restart from the current word.
 */
class PodcastPlayer(context: Context, private val lines: List<PodcastLine>) {
    enum class State { LOADING, READY, UNAVAILABLE }

    private data class Profile(val voice: Voice?, val pitch: Float, val rate: Float)

    var state by mutableStateOf(State.LOADING)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Index of the line being spoken (or about to be). */
    var line by mutableIntStateOf(0)
        private set
    /** Character offset reached within [line], for highlighting the spoken word. */
    var char by mutableIntStateOf(0)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    /** True when both speakers have their own installed voice, not only a different pitch. */
    var distinctVoices by mutableStateOf(false)
        private set

    val timeline = PodcastTimeline(lines)
    val positionMs: Long get() = timeline.positionOf(line, char)

    private val main = Handler(Looper.getMainLooper())
    private var profiles = mapOf("A" to Profile(null, 1.05f, 1.0f), "B" to Profile(null, 0.82f, 0.95f))
    /** Offset within the current line where the last utterance started; range callbacks are relative to it. */
    private var lineStart = 0
    private var generation = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            state = if (status == TextToSpeech.SUCCESS) State.READY else State.UNAVAILABLE
            if (state == State.READY) configure()
        }
    }

    private fun configure() {
        val result = tts.setLanguage(AppLanguage.locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) { state = State.UNAVAILABLE; return }
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == AppLanguage.locale.language && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedBy { it.name }
        val a = voices.firstOrNull { it.name.contains("female", true) } ?: voices.firstOrNull()
        val b = voices.firstOrNull { it != a && it.name.contains("male", true) && !it.name.contains("female", true) } ?: voices.firstOrNull { it != a }
        distinctVoices = a != null && b != null
        profiles = mapOf(
            "A" to Profile(a, if (distinctVoices) 1.0f else 1.08f, 1.0f),
            "B" to Profile(b ?: a, if (distinctVoices) 1.0f else 0.8f, 0.94f),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { if (utteranceId == current()) advance() } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { main.post { if (utteranceId == current()) advance() } }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                main.post { if (utteranceId == current()) char = lineStart + start }
            }
        })
    }

    private fun current() = "line$line#$generation"

    private fun speakCurrent() {
        if (line >= lines.size) { playing = false; line = lines.size; char = 0; return }
        val l = lines[line]
        val p = profiles[if (l.speaker == "A") "A" else "B"]!!
        p.voice?.let { runCatching { tts.voice = it } }
        tts.setPitch(p.pitch)
        tts.setSpeechRate(p.rate * speed)
        lineStart = char.coerceIn(0, l.text.length)
        generation++
        tts.speak(l.text.substring(lineStart), TextToSpeech.QUEUE_FLUSH, null, current())
    }

    private fun advance() {
        if (!playing) return
        line++
        char = 0
        speakCurrent()
    }

    fun play() {
        if (state != State.READY || lines.isEmpty()) return
        if (line >= lines.size) { line = 0; char = 0 }
        playing = true
        speakCurrent()
    }

    fun pause() {
        playing = false
        generation++
        tts.stop()
    }

    fun toggle() = if (playing) pause() else play()

    /** Jumps by [deltaMs] (e.g. ±10 s at the current speed) and keeps playing if it was playing. */
    fun seekBy(deltaMs: Long) = seekTo(positionMs + (deltaMs * speed).toLong())

    fun seekTo(positionMs: Long) {
        val (l, c) = timeline.locate(positionMs)
        val wasPlaying = playing
        if (wasPlaying) { generation++; tts.stop() }
        line = l.coerceAtMost(lines.size)
        char = c
        if (wasPlaying) { if (line < lines.size) speakCurrent() else playing = false }
    }

    /** Jumps to the start of line [index] (tapping a transcript line). */
    fun jumpTo(index: Int) = seekTo(timeline.positionOf(index, 0))

    fun changeSpeed(value: Float) {
        speed = value
        if (playing) { generation++; tts.stop(); speakCurrent() }
    }

    fun release() {
        playing = false
        runCatching { tts.stop(); tts.shutdown() }
    }

    companion object {
        val SPEEDS = listOf(1f, 1.25f, 1.5f)
    }
}
