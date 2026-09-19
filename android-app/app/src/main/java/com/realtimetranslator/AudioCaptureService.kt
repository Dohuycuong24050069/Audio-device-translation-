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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/**
 * AudioCaptureService - Chuyên thu và dịch âm thanh NỘI BỘ THIẾT BỊ (Internal Audio Only)
 *
 * - KHÔNG thu Micro ngoài môi trường.
 * - Chỉ thu âm thanh phát ra trực tiếp từ các app: YouTube, TikTok, Netflix, Game, Phim, v.v.
 * - Sử dụng Android 10+ AudioPlaybackCaptureConfiguration + MediaProjection.
 * - Nhận diện giọng nói: Vosk Offline AI (50ms latency).
 * - Dịch thuật: Google Neural Engine + Google ML Kit On-Device fallback (chuẩn xác cao).
 */
class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaProjection: MediaProjection? = null
    private var translationEngine: TranslationEngine? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null

    // Vosk Engine
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var isVoskReady = false
    private var isInitializingVosk = false

    private var srcLang = "en"
    private var tgtLang = "vi"

    private val SAMPLE_RATE = 16000

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE_LANG = "ACTION_UPDATE_LANG"

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
        Log.d(TAG, "Đang nạp mô hình Vosk Offline...")

        serviceScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "vosk-model-en")
                val markerFile = File(modelDir, ".ready")
                val finalMdl = File(modelDir, "am/final.mdl")

                if (!markerFile.exists() || !finalMdl.exists() || finalMdl.length() < 1000000L) {
                    withContext(Dispatchers.Main) {
                        FloatingOverlayService.updateSubtitles(
                            "Đang chuẩn bị mô hình AI...",
                            "Vui lòng chờ khoảng vài giây"
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
                    }

                    markerFile.createNewFile()
                }

                voskModel = Model(modelDir.absolutePath)
                voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat())
                isVoskReady = true
                isInitializingVosk = false
                Log.d(TAG, "✅ Vosk Offline Model đã sẵn sàng!")

                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Hệ thống sẵn sàng",
                        "Đang lắng nghe âm thanh thiết bị..."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi nạp Vosk Model: ${e.message}", e)
                isInitializingVosk = false
                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Lỗi nạp AI: ${e.localizedMessage}",
                        "Vui lòng thử mở lại ứng dụng"
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    0
                }
                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Lỗi startForeground: ${e2.message}")
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
                    Log.w(TAG, "Không có quyền MediaProjection, không thể thu âm thanh thiết bị")
                    FloatingOverlayService.updateSubtitles(
                        "Cần cấp quyền quay/chụp màn hình",
                        "Để thu âm thanh phát ra từ bên trong máy"
                    )
                }
            }

            ACTION_UPDATE_LANG -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: srcLang
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: tgtLang
            }
        }

        return START_STICKY
    }

    /**
     * Khởi tạo MediaProjection an toàn tuyệt đối trên Android 10-14+
     */
    private fun initMediaProjectionSafe(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (projectionManager == null) {
                Log.e(TAG, "MediaProjectionManager null")
                return
            }

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            startForegroundSafe(hasMediaProjection = true)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection đã dừng")
                    stopAudioRecord()
                    mediaProjection = null
                    startForegroundSafe(hasMediaProjection = false)
                }
            }, Handler(Looper.getMainLooper()))

            startSystemAudioCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi động MediaProjection: ${e.message}", e)
        }
    }

    // ===================== SYSTEM AUDIO PIPELINE ========================

    /**
     * Thu âm thanh nội bộ thiết bị (Internal Audio) qua Android 10+ AudioPlaybackCapture
     * Hoàn toàn không qua Micro, không lẫn tạp âm môi trường bên ngoài.
     */
    private fun startSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) {
            Log.w(TAG, "System audio yêu cầu Android 10+ và MediaProjection")
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
                Log.e(TAG, "AudioRecord thiết bị không khởi tạo được")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord!!.startRecording()
            Log.d(TAG, "✅ Bắt đầu thu âm thanh nội bộ thiết bị (${SAMPLE_RATE}Hz mono PCM16)")

            captureJob = serviceScope.launch(Dispatchers.IO) {
                runVoskAudioLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo AudioRecord thiết bị: ${e.message}", e)
        }
    }

    /**
     * Vòng lặp đọc âm thanh PCM trực tiếp và nạp vào Vosk Recognizer.
     * Tối ưu hóa độ trễ siêu thấp: đọc chunk 800 mẫu (50ms tại 16kHz)
     * và tái sử dụng bộ đệm bộ nhớ (0ms GC pause).
     */
    private suspend fun runVoskAudioLoop() {
        val chunkSize = 800 // 50ms tại 16kHz
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

            // Chuyển ShortArray sang Little-Endian ByteArray
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
                    Log.d(TAG, "Vosk Final: $text")
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

    private var lastTranslatedTime = 0L
    private var lastPartialTranslated = ""

    /**
     * Cắt câu quá dài tại ranh giới từ để dịch chuẩn hơn
     */
    private fun truncateForTranslation(text: String, maxLen: Int = 120): String {
        if (text.length <= maxLen) return text
        val sub = text.substring(0, maxLen)
        val punctIdx = sub.lastIndexOfAny(charArrayOf('.', '!', '?', ',', ';'))
        if (punctIdx > maxLen / 2) return sub.substring(0, punctIdx + 1).trim()
        val spaceIdx = sub.lastIndexOf(' ')
        return if (spaceIdx > 0) sub.substring(0, spaceIdx).trim() else sub.trim()
    }

    private fun handleRecognizedText(originalText: String, isFinal: Boolean) {
        serviceScope.launch {
            try {
                var translatedText: String? = null
                val now = System.currentTimeMillis()

                if (isFinal) {
                    val textToTranslate = truncateForTranslation(originalText)
                    translatedText = translationEngine?.translate(textToTranslate, srcLang, tgtLang)
                    lastPartialTranslated = ""
                } else {
                    // Debounce 120ms
                    if (originalText != lastPartialTranslated &&
                        (now - lastTranslatedTime > 120 || originalText.length > lastPartialTranslated.length + 5)) {
                        lastTranslatedTime = now
                        lastPartialTranslated = originalText
                        val textToTranslate = truncateForTranslation(originalText)
                        translatedText = translationEngine?.translate(textToTranslate, srcLang, tgtLang)
                    }
                }

                // Cập nhật trực tiếp lên cửa sổ nổi
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
                "Thu Âm Thanh Thiết Bị (TransLive)",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TransLive – Dịch Âm Thanh Thiết Bị")
            .setContentText("Đang thu & dịch âm thanh phát từ ứng dụng trong máy")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
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
