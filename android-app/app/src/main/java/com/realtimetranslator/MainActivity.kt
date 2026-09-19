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
 * MainActivity – Giao diện điều khiển chính
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private var sourceLanguage = "en"
    private var targetLanguage = "vi"

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
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khởi động AudioCaptureService: ${e.message}")
            }
            isServiceRunning = true
            updateUI()
            Toast.makeText(this, "✅ Đã bật dịch âm thanh thiết bị!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Chuyển sang chế độ thu âm Micro", Toast.LENGTH_SHORT).show()
            startMicTranslationService()
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

        // Nguồn âm thanh toggle
        binding.switchAudioSource.setOnCheckedChangeListener { _, isChecked ->
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = if (isChecked) AudioCaptureService.ACTION_USE_SYSTEM_AUDIO
                         else AudioCaptureService.ACTION_USE_MIC
            }
            if (isServiceRunning) startService(intent)
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

        updateLanguageUI()
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
            // 2. Quyền Micro
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
                Toast.makeText(this, "❌ Cần quyền Microphone để dịch âm thanh!", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            startTranslationService()
        }
    }

    private fun startTranslationService() {
        // 1. Khởi động FloatingOverlayService luôn để người dùng thấy ngay cửa sổ nổi
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

        // 2. Bật dịch âm thanh thiết bị (MediaProjection) hoặc Microphone
        if (binding.switchAudioSource.isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi tạo ScreenCaptureIntent: ${e.message}, dùng mic")
                startMicTranslationService()
            }
        } else {
            startMicTranslationService()
        }
    }

    private fun isXiaomiDevice(): Boolean {
        val m = Build.MANUFACTURER?.lowercase(java.util.Locale.ROOT) ?: ""
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
    }

    private fun startMicTranslationService() {
        try {
            val micIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_SRC_LANG, sourceLanguage)
                putExtra(AudioCaptureService.EXTRA_TGT_LANG, targetLanguage)
            }
            ContextCompat.startForegroundService(this, micIntent)
            isServiceRunning = true
            updateUI()
            Toast.makeText(this, "✅ Cửa sổ dịch đã hiển thị!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startMicTranslationService: ${e.message}")
        }
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
            binding.tvStatus.text = "🟢 Đang dịch real-time • Cửa sổ nổi đang hiển thị"
            binding.cardStatus.visibility = View.VISIBLE
        } else {
            binding.btnToggleService.text = "▶  Bắt đầu dịch"
            binding.btnToggleService.setBackgroundColor(
                ContextCompat.getColor(this, R.color.primary)
            )
            binding.tvStatus.text = "⚪ Chưa khởi động"
            binding.cardStatus.visibility = View.VISIBLE
        }
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
