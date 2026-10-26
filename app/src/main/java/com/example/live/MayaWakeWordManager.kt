package com.example.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class MayaWakeWordManager(
    private val context: Context,
    private val onWakeWordDetected: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isPaused = false
    private val handler = Handler(Looper.getMainLooper())

    private val wakeWords = listOf(
        "ok maya", "okay maya", "hey maya", "maya", "hello maya", "hi maya",
        "ओके माया", "माया", "सुनो माया", "नमस्ते माया", "हे माया"
    )

    fun startListening() {
        if (isListening) return
        isPaused = false
        handler.post {
            initAndStartRecognizer()
        }
    }

    fun pauseListening() {
        isPaused = true
        handler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.e("MayaWakeWord", "Error pausing recognizer: ${e.message}")
            }
            isListening = false
        }
    }

    fun resumeListening() {
        if (!isPaused) return
        isPaused = false
        handler.post {
            initAndStartRecognizer()
        }
    }

    fun destroy() {
        isPaused = true
        isListening = false
        handler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e("MayaWakeWord", "Error destroying recognizer: ${e.message}")
            }
        }
    }

    private fun initAndStartRecognizer() {
        if (isPaused) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("MayaWakeWord", "Speech recognition not available on this device.")
            return
        }

        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d("MayaWakeWord", "Wake word listener active: listening for 'Ok Maya'...")
        } catch (e: Exception) {
            Log.e("MayaWakeWord", "Failed to start speech recognizer: ${e.message}")
            scheduleRestart(2000)
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
            }

            override fun onError(error: Int) {
                isListening = false
                if (!isPaused) {
                    // Automatically restart listening loop after a short quiet period
                    val delay = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        500L
                    } else {
                        1500L
                    }
                    scheduleRestart(delay)
                }
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                checkMatches(matches)
                if (!isPaused) {
                    scheduleRestart(400)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                checkMatches(matches)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun checkMatches(matches: List<String>?) {
        if (matches.isNullOrEmpty()) return

        for (phrase in matches) {
            val lower = phrase.lowercase().trim()
            Log.v("MayaWakeWord", "Heard text candidate: '$lower'")

            for (wakeWord in wakeWords) {
                if (lower.contains(wakeWord)) {
                    Log.i("MayaWakeWord", "WAKE WORD TRIGGERED: Detected '$wakeWord' in '$lower'!")
                    onWakeWordDetected(lower)
                    return
                }
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!isPaused) {
                initAndStartRecognizer()
            }
        }, delayMs)
    }
}
