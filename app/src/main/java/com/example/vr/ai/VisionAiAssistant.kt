package com.example.vr.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import com.example.vr.CameraPassthroughManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class VisionAiPhase {
    IDLE,
    LISTENING,
    CAPTURING,
    SENDING_IMAGE,
    SPEAKING,
    ERROR
}

data class VisionAiState(
    val phase: VisionAiPhase = VisionAiPhase.IDLE,
    val isPanelOpen: Boolean = false,
    val isListening: Boolean = false,
    val isAiCameraActive: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasCachedImage: Boolean = false,
    val isFollowUpMode: Boolean = false,
    val lastQuestion: String = "",
    val lastAnswer: String = "",
    val errorMessage: String? = null,
    val capturedFrameInfo: String = "",
    val turnCount: Int = 0
)

class VisionAiAssistant(
    private val context: Context,
    private val scope: CoroutineScope,
    private val cameraManager: CameraPassthroughManager,
    private val visionRepository: VisionAiRepository = FreeLlmVisionService(context)
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VisionAiState())
    val state: StateFlow<VisionAiState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady: Boolean = false
    private var pendingTtsUtterance: String? = null

    private var cachedImageBase64: String? = null
    private val conversationHistory = mutableListOf<VisionChatTurn>()
    private var activeRequestJob: Job? = null
    private var listenTimeoutJob: Job? = null
    private var pendingCaptureNewFrame: Boolean = true

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = textToSpeech
                    val langResult = tts?.setLanguage(Locale.getDefault())
                    if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                        langResult == TextToSpeech.LANG_NOT_SUPPORTED
                    ) {
                        tts?.setLanguage(Locale.US)
                    }
                    tts?.setSpeechRate(1.02f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = true)
                        }

                        override fun onDone(utteranceId: String?) {
                            _state.value = _state.value.copy(
                                isSpeaking = false,
                                phase = if (_state.value.phase == VisionAiPhase.SPEAKING) {
                                    VisionAiPhase.IDLE
                                } else {
                                    _state.value.phase
                                }
                            )
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _state.value = _state.value.copy(isSpeaking = false)
                        }
                    })
                    isTtsReady = true
                    pendingTtsUtterance?.let { queued ->
                        pendingTtsUtterance = null
                        speakText(queued)
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to initialize TextToSpeech", e)
        }
    }

    fun openPanelOnly() {
        _state.value = _state.value.copy(isPanelOpen = true)
    }

    fun closePanel() {
        stopListeningInternal()
        stopSpeaking()
        _state.value = _state.value.copy(
            isPanelOpen = false,
            isListening = false,
            isAiCameraActive = false,
            phase = VisionAiPhase.IDLE
        )
    }

    fun onMicPermissionDenied() {
        val msg = "Microphone permission was denied. Please allow microphone access to speak a question."
        _state.value = _state.value.copy(
            isPanelOpen = true,
            phase = VisionAiPhase.ERROR,
            isListening = false,
            isAiCameraActive = false,
            errorMessage = msg
        )
        speakText(msg)
    }

    fun triggerWhatAmISeeing(
        hasMicPermission: Boolean,
        isCameraActive: Boolean
    ) {
        startVoiceVisionFlow(
            captureNewFrame = true,
            hasMicPermission = hasMicPermission,
            isCameraActive = isCameraActive
        )
    }

    fun triggerFollowUpQuestion(
        hasMicPermission: Boolean,
        isCameraActive: Boolean
    ) {
        val hasCached = cachedImageBase64 != null
        startVoiceVisionFlow(
            captureNewFrame = !hasCached,
            hasMicPermission = hasMicPermission,
            isCameraActive = isCameraActive
        )
    }

    /**
     * Explicit user-triggered fallback prompt when device SpeechRecognizer is unavailable
     * or the user gazes at "Quick Describe View". Never runs without user action.
     */
    fun triggerExplicitQuickPrompt(
        prompt: String,
        useCachedImageIfAvailable: Boolean,
        isCameraActive: Boolean
    ) {
        val captureNew = !useCachedImageIfAvailable || cachedImageBase64 == null
        if (captureNew && !isCameraActive) {
            reportError("Camera is not active. Grant camera permission and try again.")
            return
        }
        stopListeningInternal()
        stopSpeaking()
        _state.value = _state.value.copy(
            isPanelOpen = true,
            isFollowUpMode = !captureNew,
            errorMessage = null
        )
        executeVisionQuery(question = prompt, captureNewFrame = captureNew)
    }

    private fun startVoiceVisionFlow(
        captureNewFrame: Boolean,
        hasMicPermission: Boolean,
        isCameraActive: Boolean
    ) {
        _state.value = _state.value.copy(
            isPanelOpen = true,
            errorMessage = null
        )

        if (!hasMicPermission) {
            onMicPermissionDenied()
            return
        }

        if (captureNewFrame && !isCameraActive) {
            reportError("Camera passthrough is not active. Please grant camera access first.")
            return
        }

        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                reportError(
                    "Speech recognition is unavailable on this device. Use 'Describe View' below or install speech services."
                )
                return@post
            }

            stopListeningInternal()
            stopSpeaking()
            pendingCaptureNewFrame = captureNewFrame

            _state.value = _state.value.copy(
                phase = VisionAiPhase.LISTENING,
                isPanelOpen = true,
                isListening = true,
                isAiCameraActive = false,
                isFollowUpMode = !captureNewFrame,
                errorMessage = null
            )

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    speechRecognizer = it
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() {
                        listenTimeoutJob?.cancel()
                    }

                    override fun onError(error: Int) {
                        listenTimeoutJob?.cancel()
                        _state.value = _state.value.copy(isListening = false)
                        destroySpeechRecognizerOnMain()

                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "I didn't catch your question. Gaze at Follow-Up or press Volume Down to try again."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Listening timed out before hearing a question. Try again when ready."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Microphone permission denied. Please grant audio access."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Speech recognition failed due to a network error or timeout."
                            SpeechRecognizer.ERROR_AUDIO ->
                                "Microphone audio recording error. Make sure no other app is using the mic."
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Speech recognizer is busy. Please wait a second and try again."
                            else ->
                                "Could not recognize speech (code $error). Try again or use Describe View."
                        }
                        reportError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        listenTimeoutJob?.cancel()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spoken = matches?.firstOrNull()?.trim().orEmpty()
                        _state.value = _state.value.copy(isListening = false)
                        destroySpeechRecognizerOnMain()

                        if (spoken.isEmpty()) {
                            reportError("No spoken question detected. Try again.")
                        } else {
                            executeVisionQuery(
                                question = spoken,
                                captureNewFrame = pendingCaptureNewFrame
                            )
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        if (!partial.isNullOrEmpty()) {
                            _state.value = _state.value.copy(lastQuestion = partial)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                recognizer.startListening(intent)

                // Safety timeout if speech service hangs silently
                listenTimeoutJob?.cancel()
                listenTimeoutJob = scope.launch {
                    delay(LISTEN_SAFETY_TIMEOUT_MS)
                    if (_state.value.isListening) {
                        stopListeningInternal()
                        reportError("Listening timed out. Press Volume Down or try again.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SpeechRecognizer", e)
                _state.value = _state.value.copy(isListening = false)
                reportError("Failed to start speech recognition: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun executeVisionQuery(question: String, captureNewFrame: Boolean) {
        activeRequestJob?.cancel()
        activeRequestJob = scope.launch {
            _state.value = _state.value.copy(
                phase = if (captureNewFrame) VisionAiPhase.CAPTURING else VisionAiPhase.SENDING_IMAGE,
                isPanelOpen = true,
                isListening = false,
                isAiCameraActive = true,
                isFollowUpMode = !captureNewFrame,
                lastQuestion = question,
                errorMessage = null
            )

            var imageBase64 = cachedImageBase64
            if (captureNewFrame || imageBase64 == null) {
                val captureResult = cameraManager.captureJpegFrame1024()
                val frame = captureResult.getOrNull()
                if (frame == null) {
                    val err = captureResult.exceptionOrNull()?.message
                        ?: "Unable to capture camera frame."
                    _state.value = _state.value.copy(isAiCameraActive = false)
                    reportError(err)
                    return@launch
                }

                imageBase64 = Base64.encodeToString(frame.jpegBytes, Base64.NO_WRAP)
                cachedImageBase64 = imageBase64
                conversationHistory.clear()
                val kb = (frame.jpegBytes.size / 1024).coerceAtLeast(1)
                _state.value = _state.value.copy(
                    hasCachedImage = true,
                    capturedFrameInfo = "${frame.width}×${frame.height} JPEG (${kb} KB)",
                    turnCount = 0
                )
            }

            _state.value = _state.value.copy(
                phase = VisionAiPhase.SENDING_IMAGE,
                isAiCameraActive = true
            )

            val historySnapshot = conversationHistory.toList()
            val result = visionRepository.askAboutImage(
                imageJpegBase64 = imageBase64,
                history = historySnapshot,
                question = question
            )

            _state.value = _state.value.copy(isAiCameraActive = false)

            result.onSuccess { answer ->
                conversationHistory.add(VisionChatTurn(role = "user", text = question))
                conversationHistory.add(VisionChatTurn(role = "assistant", text = answer))
                _state.value = _state.value.copy(
                    phase = VisionAiPhase.SPEAKING,
                    lastAnswer = answer,
                    errorMessage = null,
                    turnCount = conversationHistory.count { it.role == "assistant" }
                )
                speakText(answer)
            }.onFailure { throwable ->
                val message = throwable.message ?: "Unable to analyze the image."
                reportError(message)
            }
        }
    }

    fun toggleSpeakOrRepeat() {
        if (_state.value.isSpeaking) {
            stopSpeaking()
        } else {
            val textToRead = _state.value.errorMessage?.takeIf { it.isNotBlank() }
                ?: _state.value.lastAnswer.takeIf { it.isNotBlank() }
                ?: "No answer yet. Look at What Am I Seeing or press Volume Down to ask a question."
            speakText(textToRead)
        }
    }

    private fun reportError(message: String) {
        _state.value = _state.value.copy(
            phase = VisionAiPhase.ERROR,
            isPanelOpen = true,
            isListening = false,
            isAiCameraActive = false,
            errorMessage = message
        )
        speakText(message)
    }

    private fun speakText(text: String) {
        if (text.isBlank()) return
        if (!isTtsReady) {
            pendingTtsUtterance = text
            return
        }
        try {
            val utteranceId = "visionbox_tts_${System.currentTimeMillis()}"
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } catch (e: Exception) {
            Log.e(TAG, "TextToSpeech speak failed", e)
        }
    }

    fun stopSpeaking() {
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }
        _state.value = _state.value.copy(isSpeaking = false)
    }

    fun pauseAndCancelTransientOperations() {
        stopListeningInternal()
        activeRequestJob?.cancel()
        stopSpeaking()
        _state.value = _state.value.copy(
            isListening = false,
            isAiCameraActive = false,
            phase = VisionAiPhase.IDLE
        )
    }

    private fun stopListeningInternal() {
        listenTimeoutJob?.cancel()
        mainHandler.post {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
            destroySpeechRecognizerOnMain()
        }
    }

    private fun destroySpeechRecognizerOnMain() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    fun release() {
        pauseAndCancelTransientOperations()
        mainHandler.post {
            destroySpeechRecognizerOnMain()
        }
        try {
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }
        textToSpeech = null
        isTtsReady = false
    }

    companion object {
        private const val TAG = "VisionAiAssistant"
        private const val LISTEN_SAFETY_TIMEOUT_MS = 16_000L
    }
}
