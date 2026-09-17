package dev.repochat.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.repochat.R

/**
 * Pure (unit-testable) merge of recognized speech into the composer text:
 * appends with a space, preserves trailing whitespace, ignores empty speech.
 */
object VoiceInputMerger {

    fun merge(existing: String, spoken: String): String {
        val spokenTrim = spoken.trim()
        if (spokenTrim.isEmpty()) return existing
        if (existing.isBlank()) return spokenTrim
        return if (existing.endsWith(" ") || existing.endsWith("\n")) {
            existing + spokenTrim
        } else {
            "$existing $spokenTrim"
        }
    }
}

/** Whether this device can do in-app speech recognition at all. */
enum class VoiceAvailability { AVAILABLE, UNAVAILABLE }

/**
 * In-app voice input backed by [SpeechRecognizer] (no cloud API key — uses
 * the device's speech service). Partial results surface live; the final
 * result is appended into the composer via [VoiceInputMerger]. Must be
 * created and used from the main thread (Compose guarantees this).
 */
class VoiceInputState internal constructor(
    private val context: Context,
    private val onResult: (String) -> Unit,
    val availability: VoiceAvailability,
    private val requestPermission: () -> Unit,
) {
    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf<String?>(null)
        private set
    var errorEvent by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    /** Mic tap: request permission when needed, otherwise start listening. */
    fun start() {
        if (isListening || availability == VoiceAvailability.UNAVAILABLE) return
        errorEvent = null
        if (!hasPermission()) {
            requestPermission()
            return
        }
        startListening()
    }

    internal fun startListening() {
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        isListening = true
        partialText = null
        rec.startListening(intent)
    }

    internal fun onPermissionDenied() {
        isListening = false
        errorEvent = context.getString(R.string.chat_voice_permission)
    }

    fun stop() {
        isListening = false
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
            // recognizer already dead — nothing to stop
        }
    }

    fun consumeError() {
        errorEvent = null
    }

    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
            // ignore double-destroy
        }
        recognizer = null
        isListening = false
        partialText = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            partialText = null
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            isListening = false
            partialText = null
            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                return // user said nothing — silently reset
            }
            errorEvent = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    context.getString(R.string.chat_voice_permission)
                else -> context.getString(R.string.chat_voice_error)
            }
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            partialText = null
            if (text.isNotBlank()) onResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialText = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

/**
 * Remembers a [VoiceInputState] wired to the device speech service and the
 * RECORD_AUDIO runtime permission flow. [onResult] always points at the
 * latest composition's lambda (no stale captures).
 */
@Composable
fun rememberVoiceInput(
    onResult: (String) -> Unit,
): VoiceInputState {
    val context = LocalContext.current
    val available = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            VoiceAvailability.AVAILABLE
        } else {
            VoiceAvailability.UNAVAILABLE
        }
    }
    val latestOnResult by rememberUpdatedState(onResult)

    // Late reference so the permission callback can reach the state that is
    // created after the launcher itself.
    var stateRef by remember { mutableStateOf<VoiceInputState?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            stateRef?.startListening()
        } else {
            stateRef?.onPermissionDenied()
        }
    }

    val state = remember(available) {
        VoiceInputState(
            context = context,
            onResult = { latestOnResult(it) },
            availability = available,
            requestPermission = {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
    }
    LaunchedEffect(state) { stateRef = state }

    DisposableEffect(Unit) {
        onDispose { state.destroy() }
    }
    return state
}
