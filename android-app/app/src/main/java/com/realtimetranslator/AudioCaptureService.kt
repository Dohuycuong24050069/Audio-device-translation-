package com.realtimetranslator

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * AudioCaptureService - Dịch âm thanh thiết bị và micro thời gian thực
 * Tương thích hoàn toàn Android 10 đến Android 14/15
 */
class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaProjection: MediaProjection? = null
    private var translationEngine: TranslationEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null

    private var srcLang = "en"
    private var tgtLang = "vi"
    private var isSystemAudio = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE_LANG = "ACTION_UPDATE_LANG"
        const val ACTION_USE_SYSTEM_AUDIO = "ACTION_USE_SYSTEM_AUDIO"
        const val ACTION_USE_MIC = "ACTION_USE_MIC"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        const val EXTRA_SRC_LANG = "EXTRA_SRC_LANG"
        const val EXTRA_TGT_LANG = "EXTRA_TGT_LANG"

        private const val CHANNEL_ID = "AudioCaptureChannel"
        private const val NOTIFICATION_ID = 102
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithProperTypes()
        try {
            translationEngine = TranslationEngine(this)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo TranslationEngine: ${e.message}")
        }
    }

    private fun startForegroundWithProperTypes() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startForeground với types: ${e.message}, fallback standard")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Lỗi startForeground fallback: ${e2.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: "en"
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: "vi"

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    initMediaProjectionSafe(resultCode, resultData)
                } else {
                    isSystemAudio = false
                    startMicrophoneRecognition()
                }
            }

            ACTION_UPDATE_LANG -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: srcLang
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: tgtLang
                restartRecognition()
            }

            ACTION_USE_SYSTEM_AUDIO -> {
                isSystemAudio = true
                if (mediaProjection != null) {
                    startSystemAudioCapture()
                }
            }

            ACTION_USE_MIC -> {
                isSystemAudio = false
                startMicrophoneRecognition()
            }
        }

        return START_STICKY
    }

    /**
     * Khởi tạo MediaProjection an toàn tuyệt đối trên Android 14
     */
    private fun initMediaProjectionSafe(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                startMicrophoneRecognition()
                return
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            // BẮT BUỘC TRÊN ANDROID 14: Phải đăng ký callback trước khi dùng, nếu không sẽ crash IllegalStateException!
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection đã dừng")
                    stopAudioRecord()
                    mediaProjection = null
                }
            }, Handler(Looper.getMainLooper()))

            isSystemAudio = true
            startSystemAudioCapture()
        } catch (e: SecurityException) {
            Log.e(TAG, "Lỗi bảo mật Android 14 MediaProjection: ${e.message}, fallback micro", e)
            isSystemAudio = false
            startMicrophoneRecognition()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động MediaProjection: ${e.message}, fallback micro", e)
            isSystemAudio = false
            startMicrophoneRecognition()
        }
    }

    /**
     * Thu âm thanh nội bộ thiết bị (Internal Audio) qua Android 10+ AudioPlaybackCapture
     */
    private fun startSystemAudioCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()

                val bufferSize = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (bufferSize > 0) {
                    audioRecord = AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(config)
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(bufferSize * 2)
                        .build()

                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        audioRecord?.startRecording()
                        Log.d(TAG, "Bắt đầu thu âm thanh hệ thống thành công")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi AudioRecord hệ thống: ${e.message}", e)
            }
        }

        // Song song nhận diện giọng nói
        startMicrophoneRecognition()
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi giải phóng AudioRecord: ${e.message}")
        }
    }

    /**
     * Nhận dạng giọng nói liên tục (STT)
     */
    private fun startMicrophoneRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị này")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.w(TAG, "Speech error code: $error")
                        serviceScope.launch {
                            delay(600)
                            startListeningIntent()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            handleRecognizedText(text, isFinal = true)
                        }
                        startListeningIntent()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            handleRecognizedText(text, isFinal = false)
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            startListeningIntent()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo SpeechRecognizer: ${e.message}")
        }
    }

    private fun startListeningIntent() {
        try {
            val locale = if (srcLang.startsWith("en", ignoreCase = true)) Locale.US else Locale("vi", "VN")
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startListening: ${e.message}")
        }
    }

    private fun restartRecognition() {
        try {
            speechRecognizer?.stopListening()
            startListeningIntent()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi restart: ${e.message}")
        }
    }

    /**
     * Xử lý đoạn văn bản vừa nhận diện được:
     * Dịch và gửi sang FloatingOverlayService
     */
    private fun handleRecognizedText(originalText: String, isFinal: Boolean) {
        serviceScope.launch {
            try {
                val translatedText = if (isFinal) {
                    translationEngine?.translate(originalText, srcLang, tgtLang) ?: ""
                } else {
                    "..."
                }

                val intent = Intent(this@AudioCaptureService, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_UPDATE_TEXT
                    putExtra(FloatingOverlayService.EXTRA_ORIGINAL_TEXT, originalText)
                    putExtra(FloatingOverlayService.EXTRA_TRANSLATED_TEXT, translatedText)
                }
                startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý dịch: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Thu Âm Thanh Dịch Thuật",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Realtime Translator")
            .setContentText("Đang dịch âm thanh thiết bị & micro real-time")
            .setSmallIcon(R.drawable.bg_bubble)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            speechRecognizer?.destroy()
            translationEngine?.close()
            stopAudioRecord()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi onDestroy: ${e.message}")
        }
        serviceScope.cancel()
    }
}
