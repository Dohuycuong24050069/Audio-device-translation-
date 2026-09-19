package com.realtimetranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.realtimetranslator.databinding.ActivityMainBinding

/**
 * MainActivity – Giao diện điều khiển chính của TransLive
 * Chuyên dịch âm thanh nội bộ thiết bị (Internal Audio Only)
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private var sourceLanguage = "en"
    private var targetLanguage = "vi"
    private var selectedAudioSource = AudioCaptureService.SOURCE_INTERNAL

    // Launcher cho quyền MediaProjection (thu âm thanh thiết bị)
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
                putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, selectedAudioSource)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi động AudioCaptureService: ${e.message}")
            }
            isServiceRunning = true
            updateUI()
            Toast.makeText(this, "✅ Đã bật thu âm thanh thiết bị!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Không cấp quyền quay màn hình, tự động bật chế độ Loa ngoài!", Toast.LENGTH_SHORT).show()
            selectAudioSource(AudioCaptureService.SOURCE_SPEAKER)
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
                putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, AudioCaptureService.SOURCE_SPEAKER)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {}
            isServiceRunning = true
            updateUI()
        }
    }

    // Launcher cho quyền overlay (SYSTEM_ALERT_WINDOW)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            checkAndRequestAudioPermission()
        } else {
            Toast.makeText(this, "❌ Cần quyền 'Hiển thị trên ứng dụng khác' để vẽ khung nổi!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupUI() {
        // Nút bật/tắt dịch
        binding.btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                stopTranslationService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // Đảo chiều ngôn ngữ
        binding.btnSwapLanguage.setOnClickListener {
            sourceLanguage = if (sourceLanguage == "en") "vi" else "en"
            targetLanguage = if (targetLanguage == "vi") "en" else "vi"
            updateLanguageUI()

            if (isServiceRunning) {
                val intent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_UPDATE_LANG
                    putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                    putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
                }
                startService(intent)

                val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_UPDATE_LANG
                    putExtra(FloatingOverlayService.EXTRA_SRC_LANG, sourceLanguage)
                    putExtra(FloatingOverlayService.EXTRA_TGT_LANG, targetLanguage)
                }
                startService(overlayIntent)
            }
            Toast.makeText(this, "↔ Đảo chiều: ${ if (sourceLanguage == "en") "EN → VI" else "VI → EN" }", Toast.LENGTH_SHORT).show()
        }

        // Slider cỡ chữ floating widget
        binding.seekbarFontSize.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val sizesp = 12 + progress
                binding.tvFontSizeValue.text = "${sizesp}sp"
                val intent = Intent(this@MainActivity, FloatingOverlayService::class.java).apply {
                    action = FloatingOverlayService.ACTION_UPDATE_FONT_SIZE
                    putExtra(FloatingOverlayService.EXTRA_FONT_SIZE, sizesp)
                }
                if (isServiceRunning) startService(intent)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        // Nút chọn chế độ nguồn âm thanh
        binding.btnModeInternal.setOnClickListener {
            selectAudioSource(AudioCaptureService.SOURCE_INTERNAL)
        }
        binding.btnModeSpeaker.setOnClickListener {
            selectAudioSource(AudioCaptureService.SOURCE_SPEAKER)
        }

        updateLanguageUI()
        updateSourceModeUI()
    }

    private fun selectAudioSource(source: String) {
        selectedAudioSource = source
        updateSourceModeUI()

        if (isServiceRunning) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_SWITCH_SOURCE
                putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, selectedAudioSource)
            }
            startService(intent)
        }
    }

    private fun updateSourceModeUI() {
        if (selectedAudioSource == AudioCaptureService.SOURCE_INTERNAL) {
            binding.tvModeInternalTitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.tvModeSpeakerTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnModeInternal.alpha = 1.0f
            binding.btnModeSpeaker.alpha = 0.6f
        } else {
            binding.tvModeInternalTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvModeSpeakerTitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.btnModeInternal.alpha = 0.6f
            binding.btnModeSpeaker.alpha = 1.0f
        }
    }

    private fun checkPermissionsAndStart() {
        when {
            // 1. Quyền vẽ cửa sổ nổi
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "Vui lòng cho phép 'Hiển thị trên ứng dụng khác'", Toast.LENGTH_LONG).show()
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                } catch (e: Exception) {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    overlayPermissionLauncher.launch(fallbackIntent)
                }
            }
            // 2. Quyền Audio Record (yêu cầu bởi AudioRecord)
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED -> {
                checkAndRequestAudioPermission()
            }
            // 3. Quyền Thông báo Android 13+
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED -> {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            else -> startTranslationService()
        }
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        } else {
            startTranslationService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startTranslationService()
            } else {
                Toast.makeText(this, "❌ Cần cấp quyền để thu âm thanh!", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            startTranslationService()
        }
    }

    private fun startTranslationService() {
        // 1. Khởi động FloatingOverlayService
        try {
            val overlayIntent = Intent(this, FloatingOverlayService::class.java).apply {
                action = FloatingOverlayService.ACTION_START
                putExtra(FloatingOverlayService.EXTRA_SRC_LANG, sourceLanguage)
                putExtra(FloatingOverlayService.EXTRA_TGT_LANG, targetLanguage)
            }
            try {
                startService(overlayIntent)
            } catch (e: Exception) {
                ContextCompat.startForegroundService(this, overlayIntent)
            }
            isServiceRunning = true
            updateUI()
            Toast.makeText(this, "✅ Đang khởi động cửa sổ dịch nổi...", Toast.LENGTH_SHORT).show()
            if (isXiaomiDevice()) {
                Toast.makeText(this, "💡 Xiaomi/Redmi: Nhớ bật 'Hiển thị cửa sổ pop-up khi chạy dưới nền' trong Cài đặt app!", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi bật FloatingOverlayService: ${e.message}")
            Toast.makeText(this, "Lỗi khởi động khung nổi: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // 2. Thu âm thanh
        if (selectedAudioSource == AudioCaptureService.SOURCE_SPEAKER) {
            // Chế độ Discord / Loa ngoài: Không cần xin quyền quay màn hình MediaProjection!
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
                putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, AudioCaptureService.SOURCE_SPEAKER)
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi động AudioCaptureService: ${e.message}")
            }
            Toast.makeText(this, "🎙️ Đã bật thu âm Discord & Loa ngoài! Bật loa ngoài Discord để nghe rõ.", Toast.LENGTH_LONG).show()
        } else {
            // Chế độ âm thanh trong máy: Yêu cầu MediaProjection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi tạo ScreenCaptureIntent: ${e.message}")
                    Toast.makeText(this, "Lỗi khởi động thu âm thiết bị: ${e.message}", Toast.LENGTH_LONG).show()
                    stopTranslationService()
                }
            } else {
                Toast.makeText(this, "⚠️ Thu âm nội bộ yêu cầu Android 10+. Tự động chuyển sang Loa ngoài.", Toast.LENGTH_SHORT).show()
                selectAudioSource(AudioCaptureService.SOURCE_SPEAKER)
                val intent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_START
                    putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                    putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
                    putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, AudioCaptureService.SOURCE_SPEAKER)
                }
                ContextCompat.startForegroundService(this, intent)
            }
        }
    }

    private fun isXiaomiDevice(): Boolean {
        val m = Build.MANUFACTURER?.lowercase(java.util.Locale.ROOT) ?: ""
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    private fun stopTranslationService() {
        try {
            stopService(Intent(this, FloatingOverlayService::class.java))
            stopService(Intent(this, AudioCaptureService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi dừng service: ${e.message}")
        }
        isServiceRunning = false
        updateUI()
        Toast.makeText(this, "Đã dừng dịch", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            binding.btnToggleService.text = "⏹  Dừng dịch"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_danger)
            )
            val modeText = if (selectedAudioSource == AudioCaptureService.SOURCE_SPEAKER) {
                "🎙️ Đang thu Discord / Loa ngoài"
            } else {
                "🔊 Đang thu âm thanh trong máy"
            }
            binding.tvStatus.text = "🟢 $modeText • Khung nổi đang bật"
            binding.cardStatus.visibility = View.VISIBLE
        } else {
            binding.btnToggleService.text = "▶  Bắt đầu dịch"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, R.color.primary)
            )
            val modeText = if (selectedAudioSource == AudioCaptureService.SOURCE_SPEAKER) {
                "Discord / Loa ngoài"
            } else {
                "Âm thanh trong máy"
            }
            binding.tvStatus.text = "⚪ Sẵn sàng thu âm ($modeText)"
            binding.cardStatus.visibility = View.VISIBLE
        }
        updateSourceModeUI()
    }

    private fun updateLanguageUI() {
        if (sourceLanguage == "en") {
            binding.tvSourceLang.text = "English"
            binding.tvTargetLang.text = "Tiếng Việt"
        } else {
            binding.tvSourceLang.text = "Tiếng Việt"
            binding.tvTargetLang.text = "English"
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
