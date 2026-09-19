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
import android.media.MediaRecorder
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
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * AudioCaptureService - Dịch âm thanh thiết bị và micro thời gian thực
 *
 * =====================================================================
 * ĐỘNG CƠ NHẬN DẠNG:
 *   Vosk Offline AI (vosk-model-small-en-us-0.15)
 *   - 100% Offline, không tốn chi phí, không cần API Key, không giới hạn.
 *   - Nhận diện trực tiếp từ luồng sóng âm PCM 16kHz của AudioRecord.
 *   - Trả về kết quả Realtime (partial) và câu hoàn chỉnh (final).
 *
 * ĐỘNG CƠ DỊCH THUẬT:
 *   Google ML Kit On-Device Translation (Offline 100%).
 * =====================================================================
 *
 * Hỗ trợ Android 10 đến Android 14/15
 */
class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaProjection: MediaProjection? = null
    private var translationEngine: TranslationEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    // Vosk Engine
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var isVoskReady = false
    private var isInitializingVosk = false

    private var srcLang = "en"
    private var tgtLang = "vi"
    private var isSystemAudio = false

    private val SAMPLE_RATE = 16000

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
        startForegroundSafe(hasMediaProjection = false)
        try {
            translationEngine = TranslationEngine(this)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo TranslationEngine: ${e.message}")
        }
        initVoskModel()
    }

    private fun initVoskModel() {
        if (isVoskReady || isInitializingVosk) return
        isInitializingVosk = true
        Log.d(TAG, "Đang khởi tạo Vosk Offline AI Model...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "vosk-model-en")
                val markerFile = File(modelDir, ".ready")
                val finalMdl = File(modelDir, "am/final.mdl")

                if (!markerFile.exists() || !finalMdl.exists() || finalMdl.length() < 1000000L) {
                    withContext(Dispatchers.Main) {
                        FloatingOverlayService.updateSubtitles(
                            "Đang nạp mô hình AI Vosk (lần đầu)...",
                            "Vui lòng chờ khoảng 3-5 giây"
                        )
                    }

                    if (modelDir.exists()) modelDir.deleteRecursively()
                    modelDir.mkdirs()

                    val modelFiles = listOf(
                        "am/final.mdl",
                        "conf/mfcc.conf",
                        "conf/model.conf",
                        "graph/disambig_tid.int",
                        "graph/Gr.fst",
                        "graph/HCLr.fst",
                        "graph/phones/word_boundary.int",
                        "ivector/final.dubm",
                        "ivector/final.ie",
                        "ivector/final.mat",
                        "ivector/global_cmvn.stats",
                        "ivector/online_cmvn.conf",
                        "ivector/splice.conf",
                        "README"
                    )

                    for ((index, relPath) in modelFiles.withIndex()) {
                        val outFile = File(modelDir, relPath)
                        outFile.parentFile?.mkdirs()
                        assets.open("model-en-us/$relPath").use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        val percent = ((index + 1) * 100) / modelFiles.size
                        withContext(Dispatchers.Main) {
                            FloatingOverlayService.updateSubtitles(
                                "Đang chuẩn bị mô hình AI: $percent%",
                                "Chỉ cần giải nén 1 lần duy nhất"
                            )
                        }
                    }

                    markerFile.createNewFile()
                }

                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Đang khởi động Vosk Engine...",
                        "Chuẩn bị hoàn tất..."
                    )
                }

                voskModel = Model(modelDir.absolutePath)
                voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                isVoskReady = true
                isInitializingVosk = false
                Log.d(TAG, "✅ Vosk Offline Model đã sẵn sàng từ ${modelDir.absolutePath}!")

                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Vosk AI Offline đã sẵn sàng",
                        "Đang lắng nghe âm thanh thiết bị/micro..."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi giải nén/nạp Vosk Model: ${e.message}", e)
                isInitializingVosk = false
                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Lỗi nạp Vosk AI: ${e.localizedMessage}",
                        "Vui lòng thử khởi động lại ứng dụng"
                    )
                }
            }
        }
    }

    private fun startForegroundSafe(hasMediaProjection: Boolean) {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (hasMediaProjection) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startForeground: ${e.message}, fallback standard")
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
                    startMicRecognition()
                }
            }

            ACTION_UPDATE_LANG -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: srcLang
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: tgtLang
                restartRecognition()
            }

            ACTION_USE_SYSTEM_AUDIO -> {
                isSystemAudio = true
                stopMicRecognition()
                if (mediaProjection != null) {
                    startSystemAudioCapture()
                } else {
                    Log.w(TAG, "Chưa có MediaProjection, không thể chuyển sang system audio")
                }
            }

            ACTION_USE_MIC -> {
                isSystemAudio = false
                stopCaptureLoop()
                stopAudioRecord()
                startMicRecognition()
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
                startMicRecognition()
                return
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

            // Android 14: Cập nhật Foreground Service với MEDIA_PROJECTION sau khi đã có quyền
            startForegroundSafe(hasMediaProjection = true)

            // BẮT BUỘC TRÊN ANDROID 14: Đăng ký callback trước khi dùng
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection đã dừng")
                    stopAudioRecord()
                    mediaProjection = null
                    startForegroundSafe(hasMediaProjection = false)
                }
            }, Handler(Looper.getMainLooper()))

            isSystemAudio = true
            startSystemAudioCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động MediaProjection: ${e.message}, fallback micro", e)
            isSystemAudio = false
            startMicRecognition()
        }
    }

    // ===================== SYSTEM AUDIO PIPELINE ========================

    /**
     * Thu âm thanh nội bộ thiết bị (Internal Audio) qua Android 10+ AudioPlaybackCapture
     * và đưa trực tiếp vào Vosk Recognizer.
     */
    private fun startSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) {
            Log.w(TAG, "System audio yêu cầu Android 10+ và MediaProjection, fallback micro")
            startMicRecognition()
            return
        }

        stopCaptureLoop()
        stopAudioRecord()

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuffer * 4, 8192)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord không khởi tạo được, fallback micro")
                audioRecord?.release()
                audioRecord = null
                startMicRecognition()
                return
            }

            audioRecord!!.startRecording()
            Log.d(TAG, "✅ Bắt đầu thu âm thanh hệ thống (${SAMPLE_RATE}Hz mono PCM16) qua Vosk")

            captureJob = serviceScope.launch(Dispatchers.IO) {
                runVoskAudioLoop(bufferSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo AudioRecord hệ thống: ${e.message}, fallback micro", e)
            startMicRecognition()
        }
    }

    /**
     * Thu âm thanh từ Microphone và đưa vào Vosk Recognizer (hoặc SpeechRecognizer cho tiếng Việt)
     */
    private fun startMicRecognition() {
        stopCaptureLoop()
        stopAudioRecord()

        if (srcLang.startsWith("en", ignoreCase = true)) {
            // Khi nguồn là tiếng Anh: Dùng AudioRecord Mic đưa vào Vosk (100% Offline)
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = maxOf(minBuffer * 4, 8192)

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    Log.d(TAG, "✅ Đang thu Micro qua Vosk AI Offline")
                    captureJob = serviceScope.launch(Dispatchers.IO) {
                        runVoskAudioLoop(bufferSize)
                    }
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi AudioRecord Mic: ${e.message}")
            }
        }

        // Fallback sang Android SpeechRecognizer (hỗ trợ tiếng Việt tốt)
        startAndroidSpeechRecognizer()
    }

    /**
     * Vòng lặp đọc âm thanh PCM trực tiếp và nạp vào Vosk Recognizer
     */
    /**
     * Vòng lặp đọc âm thanh PCM trực tiếp và nạp vào Vosk Recognizer.
     * Tối ưu hóa độ trễ siêu thấp: đọc chunk 1280 mẫu (80ms tại 16kHz)
     * và tái sử dụng bộ đệm bộ nhớ (0ms GC pause).
     */
    private suspend fun runVoskAudioLoop(bufferSize: Int) {
        val chunkSize = 800 // 50ms tại 16kHz (nhận diện tức thời, giảm độ trễ tối đa)
        val readBuffer = ShortArray(chunkSize)
        val pcmBytes = ByteArray(chunkSize * 2)
        Log.d(TAG, "🔄 Bắt đầu vòng lặp Vosk PCM siêu tốc (50ms latency)")

        while (serviceScope.isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val shortsRead = audioRecord?.read(readBuffer, 0, chunkSize) ?: -1
            if (shortsRead <= 0) {
                delay(5)
                continue
            }

            val recognizer = voskRecognizer
            if (recognizer == null || !isVoskReady) {
                delay(20)
                continue
            }

            // Chuyển ShortArray sang Little-Endian ByteArray với mảng cấp phát sẵn (0ms GC)
            var byteIdx = 0
            for (i in 0 until shortsRead) {
                val s = readBuffer[i].toInt()
                pcmBytes[byteIdx++] = (s and 0xFF).toByte()
                pcmBytes[byteIdx++] = ((s shr 8) and 0xFF).toByte()
            }

            val byteLen = shortsRead * 2
            if (recognizer.acceptWaveForm(pcmBytes, byteLen)) {
                val resultJson = recognizer.result
                val text = parseVoskText(resultJson)
                if (text.isNotBlank()) {
                    Log.d(TAG, "Vosk Result: $text")
                    handleRecognizedText(text, isFinal = true)
                }
            } else {
                val partialJson = recognizer.partialResult
                val partial = parseVoskPartial(partialJson)
                if (partial.isNotBlank()) {
                    handleRecognizedText(partial, isFinal = false)
                }
            }
        }
        Log.d(TAG, "🛑 Kết thúc vòng lặp Vosk PCM")
    }

    private fun parseVoskText(jsonStr: String): String {
        return try {
            JSONObject(jsonStr).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseVoskPartial(jsonStr: String): String {
        return try {
            JSONObject(jsonStr).optString("partial", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
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

    // ===================== ANDROID SPEECH RECOGNIZER =====================

    private fun startAndroidSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "SpeechRecognizer không khả dụng trên thiết bị")
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
                        serviceScope.launch {
                            delay(350)
                            startListeningIntent()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0]
                            handleRecognizedText(text, isFinal = true)
                        }
                        serviceScope.launch {
                            delay(150)
                            startListeningIntent()
                        }
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

    private fun stopMicRecognition() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng SpeechRecognizer: ${e.message}")
        }
    }

    private fun restartRecognition() {
        if (isSystemAudio) {
            Log.d(TAG, "Cập nhật ngôn ngữ system audio: $srcLang → $tgtLang")
        } else {
            startMicRecognition()
        }
    }

    private var lastTranslatedTime = 0L
    private var lastPartialTranslated = ""

    /**
     * Cắt văn bản dài tại ranh giới từ để tránh gửi đoạn quá dài cho API dịch.
     * Ưu tiên cắt tại dấu câu (. , ! ? ;), nếu không có thì cắt tại khoảng trắng gần nhất.
     * Giới hạn: 120 ký tự — đủ ngắn để ML Kit xử lý nhanh dưới 50ms.
     */
    private fun truncateForTranslation(text: String, maxLen: Int = 120): String {
        if (text.length <= maxLen) return text
        // Tìm dấu câu gần cuối nhất trong phạm vi maxLen
        val sub = text.substring(0, maxLen)
        val punctIdx = sub.lastIndexOfAny(charArrayOf('.', '!', '?', ',', ';'))
        if (punctIdx > maxLen / 2) return sub.substring(0, punctIdx + 1).trim()
        // Cắt tại khoảng trắng gần nhất
        val spaceIdx = sub.lastIndexOf(' ')
        return if (spaceIdx > 0) sub.substring(0, spaceIdx).trim() else sub.trim()
    }

    private fun handleRecognizedText(originalText: String, isFinal: Boolean) {
        serviceScope.launch {
            try {
                var translatedText: String? = null
                val now = System.currentTimeMillis()

                if (isFinal) {
                    // Cắt câu quá dài trước khi dịch để tránh lag
                    val textToTranslate = truncateForTranslation(originalText)
                    translatedText = translationEngine?.translate(textToTranslate, srcLang, tgtLang)
                    lastPartialTranslated = ""
                } else {
                    // Debounce 120ms (giảm từ 200ms) — đủ nhanh cho realtime mà không spam API
                    if (originalText != lastPartialTranslated &&
                        (now - lastTranslatedTime > 120 || originalText.length > lastPartialTranslated.length + 5)) {
                        lastTranslatedTime = now
                        lastPartialTranslated = originalText
                        val textToTranslate = truncateForTranslation(originalText)
                        translatedText = translationEngine?.translate(textToTranslate, srcLang, tgtLang)
                    }
                }

                // Cập nhật trực tiếp lên cửa sổ nổi nếu đang mở
                if (FloatingOverlayService.instance != null) {
                    FloatingOverlayService.updateSubtitles(originalText, translatedText)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi xử lý dịch: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Thu Âm Thanh Dịch Thuật (Vosk AI)",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Realtime Translator (Vosk AI)")
            .setContentText("Đang dịch âm thanh thiết bị & micro 100% Offline")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopMicRecognition()
            stopCaptureLoop()
            stopAudioRecord()
            voskRecognizer?.close()
            translationEngine?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi onDestroy: ${e.message}")
        }
        serviceScope.cancel()
    }
}
