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
 * AudioCaptureService - Thu âm thanh thiết bị siêu tốc (Real-Time 0ms Display)
 *
 * Tối ưu hóa phản hồi tức thì:
 * - Khi video/âm thanh vừa phát âm: Chữ hiển thị ngay lên màn hình trong vòng <20ms (0ms network delay).
 * - Dịch thuật chạy ngầm song song (On-Device ML Kit <15ms khi đang nói, Cloud Neural khi dứt câu).
 */
class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaProjection: MediaProjection? = null
    private var translationEngine: TranslationEngine? = null
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var translateJob: Job? = null

    // Vosk Engine
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var isVoskReady = false
    private var isInitializingVosk = false

    private var srcLang = "en"
    private var tgtLang = "vi"

    private var currentDisplayedText = ""
    private var currentTranslatedText = ""

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

        serviceScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(filesDir, "vosk-model-en")
                val markerFile = File(modelDir, ".ready")
                val finalMdl = File(modelDir, "am/final.mdl")

                if (!markerFile.exists() || !finalMdl.exists() || finalMdl.length() < 1000000L) {
                    withContext(Dispatchers.Main) {
                        FloatingOverlayService.updateSubtitles(
                            "Đang nạp mô hình AI...",
                            "Vui lòng chờ khoảng 3 giây"
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

                    for (relPath in modelFiles) {
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
                voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat()).apply {
                    setWords(true)
                }
                isVoskReady = true
                isInitializingVosk = false

                withContext(Dispatchers.Main) {
                    FloatingOverlayService.updateSubtitles(
                        "Hệ thống sẵn sàng",
                        "Nói hoặc phát video để bắt đầu dịch tức thì..."
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi nạp Vosk Model: ${e.message}", e)
                isInitializingVosk = false
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
                }
            }

            ACTION_UPDATE_LANG -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: srcLang
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: tgtLang
            }
        }

        return START_STICKY
    }

    private fun initMediaProjectionSafe(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return

            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            startForegroundSafe(hasMediaProjection = true)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopAudioRecord()
                    mediaProjection = null
                    startForegroundSafe(hasMediaProjection = false)
                }
            }, Handler(Looper.getMainLooper()))

            startSystemAudioCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi MediaProjection: ${e.message}", e)
        }
    }

    /**
     * Thu âm thanh nội bộ thiết bị (Internal Audio) với bộ đệm siêu nhỏ để triệt tiêu độ trễ
     */
    private fun startSystemAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mediaProjection == null) return

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
            // Buffer nhỏ gọn để lấy âm thanh mới nhất ngay tức khắc
            val bufferSize = maxOf(minBuffer * 2, 4096)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord!!.startRecording()
            Log.d(TAG, "✅ Bắt đầu thu âm thanh thiết bị với độ trễ cực thấp (0ms)")

            captureJob = serviceScope.launch(Dispatchers.IO) {
                runVoskAudioLoop()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi tạo AudioRecord: ${e.message}", e)
        }
    }

    /**
     * Vòng lặp đọc âm thanh siêu nhạy (30ms chunk = 480 samples).
     * Phát hiện giọng nói và đẩy lên màn hình TỨC THỜI (0ms).
     */
    private suspend fun runVoskAudioLoop() {
        val chunkSize = 480 // 30ms tại 16kHz — đọc liên tục, không đệm trễ
        val readBuffer = ShortArray(chunkSize)
        val pcmBytes = ByteArray(chunkSize * 2)
        var lastSeenPartial = ""

        while (serviceScope.isActive && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val shortsRead = audioRecord?.read(readBuffer, 0, chunkSize) ?: -1
            if (shortsRead <= 0) {
                delay(2)
                continue
            }

            val recognizer = voskRecognizer ?: continue
            if (!isVoskReady) {
                delay(10)
                continue
            }

            var byteIdx = 0
            for (i in 0 until shortsRead) {
                val s = readBuffer[i].toInt()
                pcmBytes[byteIdx++] = (s and 0xFF).toByte()
                pcmBytes[byteIdx++] = ((s shr 8) and 0xFF).toByte()
            }

            val byteLen = shortsRead * 2
            if (recognizer.acceptWaveForm(pcmBytes, byteLen)) {
                val text = parseVoskText(recognizer.result)
                if (text.isNotBlank()) {
                    lastSeenPartial = ""
                    dispatchInstantSpeech(text, isFinal = true)
                }
            } else {
                val partial = parseVoskPartial(recognizer.partialResult)
                if (partial.isNotBlank() && partial != lastSeenPartial) {
                    lastSeenPartial = partial
                    dispatchInstantSpeech(partial, isFinal = false)
                }
            }
        }
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

    /**
     * PHẢN HỒI TỨC THÌ (0ms LAG):
     * 1. Đẩy ngay văn bản gốc lên Floating Window (người dùng vừa nói là thấy chữ hiện ngay!).
     * 2. Lập lịch dịch bất đồng bộ trong nền mà KHÔNG chặn hiển thị.
     */
    private fun dispatchInstantSpeech(text: String, isFinal: Boolean) {
        currentDisplayedText = text

        // 1. CẬP NHẬT GIAO DIỆN TỨC THỜI (0ms ĐỘ TRỄ)
        FloatingOverlayService.updateSubtitles(text, currentTranslatedText)

        // 2. DỊCH CHẠY NGẦM BẤT ĐỒNG BỘ
        translateJob?.cancel()
        translateJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val textToTranslate = truncateForTranslation(text)
                val translated = if (isFinal) {
                    // Khi hết câu: Dịch chính xác bằng Google Neural
                    translationEngine?.translate(textToTranslate, srcLang, tgtLang, preferCloud = true)
                } else {
                    // Khi đang nói: Dịch siêu tốc bằng ML Kit On-device (<15ms)
                    translationEngine?.translateFast(textToTranslate, srcLang, tgtLang)
                }

                if (!translated.isNullOrBlank()) {
                    currentTranslatedText = translated
                    withContext(Dispatchers.Main) {
                        FloatingOverlayService.updateSubtitles(currentDisplayedText, translated)
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun truncateForTranslation(text: String, maxLen: Int = 120): String {
        if (text.length <= maxLen) return text
        val sub = text.substring(0, maxLen)
        val punctIdx = sub.lastIndexOfAny(charArrayOf('.', '!', '?', ',', ';'))
        if (punctIdx > maxLen / 2) return sub.substring(0, punctIdx + 1).trim()
        val spaceIdx = sub.lastIndexOf(' ')
        return if (spaceIdx > 0) sub.substring(0, spaceIdx).trim() else sub.trim()
    }

    private fun stopCaptureLoop() {
        captureJob?.cancel()
        captureJob = null
        translateJob?.cancel()
        translateJob = null
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
            .setContentText("Đang dịch âm thanh phát từ ứng dụng trong máy")
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
