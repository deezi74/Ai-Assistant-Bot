package dev.pocketagent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Text-to-speech for replies; splits long text so the engine's length limit never cuts it off. */
class Speaker(context: Context) {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var ready = false
    private var pending: Pair<String, () -> Unit>? = null
    private var onDone: (() -> Unit)? = null
    private var lastId = ""

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            pending?.let { (text, done) -> speak(text, done) }
            pending = null
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) finish()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                _speaking.value = false
            }
        })
    }

    var rate: Float = 1.0f
        set(value) {
            field = value
            tts.setSpeechRate(value)
        }

    var pitch: Float = 1.0f
        set(value) {
            field = value
            tts.setPitch(value)
        }

    private fun finish() {
        _speaking.value = false
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    fun speak(text: String, done: () -> Unit = {}) {
        val clean = forSpeech(text)
        if (clean.isBlank()) {
            done()
            return
        }
        if (!ready) {
            pending = clean to done
            return
        }
        tts.stop()
        onDone = done
        tts.language = Locale.getDefault()
        val chunks = chunk(clean)
        chunks.forEachIndexed { i, part ->
            val id = "u${System.nanoTime()}_$i"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        onDone = null
        pending = null
        tts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    companion object {
        /** Drops markdown, links and code so replies sound natural. */
        fun forSpeech(text: String): String = text
            .replace(Regex("```[\\s\\S]*?```"), " (code shown on screen) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("https?://\\S+"), "the link on screen")
            .replace(Regex("[*_#>|~]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        private fun chunk(text: String, max: Int = 3500): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > max) {
                val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 } ?: max
                out += rest.substring(0, cut + 1)
                rest = rest.substring(cut + 1).trimStart()
            }
            if (rest.isNotBlank()) out += rest
            return out
        }
    }
}

/** On-device speech recognition for hands-free voice mode (needs RECORD_AUDIO). Use from the main thread. */
class Listener(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data class Listening(val partial: String) : State
        data class Heard(val text: String) : State
        data class Failed(val message: String, val silent: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = State.Listening("")
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = State.Listening(text)
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = if (text.isBlank()) State.Failed("I didn't catch that.", silent = true) else State.Heard(text)
            }

            override fun onError(error: Int) {
                val silent = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                _state.value = State.Failed(
                    when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a connection on this phone."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy. Tap to try again."
                        else -> if (silent) "I didn't hear anything." else "Speech recognition error $error."
                    },
                    silent
                )
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        )
    }

    fun stop() {
        recognizer?.run {
            runCatching { cancel() }
            runCatching { destroy() }
        }
        recognizer = null
        _state.value = State.Idle
    }
}
