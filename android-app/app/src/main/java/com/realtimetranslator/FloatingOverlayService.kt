package com.realtimetranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * FloatingOverlayService - Quản lý cửa sổ nổi trên màn hình
 * Tương thích hoàn toàn Android 10 đến Android 14/15
 */
class FloatingOverlayService : Service() {

    private val TAG = "FloatingOverlayService"
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null  // params riêng cho bubble

    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private var tvOriginalTag: TextView? = null
    private var tvTranslatedTag: TextView? = null
    private var scrollSubtitleArea: android.widget.ScrollView? = null
    private var isOverlayAdded = false
    private var isBubbleAdded = false
    private var currentSizeLevel = 1 // 0: Nhỏ, 1: Vừa, 2: Lớn
    private var srcLang = "en"
    private var tgtLang = "vi"

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_UPDATE_TEXT = "ACTION_UPDATE_TEXT"
        const val ACTION_UPDATE_LANG = "ACTION_UPDATE_LANG"
        const val ACTION_UPDATE_FONT_SIZE = "ACTION_UPDATE_FONT_SIZE"
        const val ACTION_STOP = "ACTION_STOP"

        const val EXTRA_SRC_LANG = "EXTRA_SRC_LANG"
        const val EXTRA_TGT_LANG = "EXTRA_TGT_LANG"
        const val EXTRA_ORIGINAL_TEXT = "EXTRA_ORIGINAL_TEXT"
        const val EXTRA_TRANSLATED_TEXT = "EXTRA_TRANSLATED_TEXT"
        const val EXTRA_FONT_SIZE = "EXTRA_FONT_SIZE"

        private const val CHANNEL_ID = "FloatingOverlayChannel"
        private const val NOTIFICATION_ID = 101

        // In-memory direct listener để cập nhật văn bản cực nhanh, không qua Intent IPC
        var instance: FloatingOverlayService? = null
            private set

        fun updateSubtitles(orig: String, trans: String?) {
            instance?.let { service ->
                service.tvOriginal?.post {
                    if (orig.isNotEmpty()) service.tvOriginal?.text = orig
                    if (!trans.isNullOrEmpty()) service.tvTranslated?.text = trans
                    service.scrollSubtitleArea?.post {
                        service.scrollSubtitleArea?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForegroundSafe()
        // Không gọi initOverlay ở onCreate để tránh tạo 2 lần với onStartCommand
    }

    private fun startForegroundSafe() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "FloatingOverlayService hoạt động ở chế độ service tiêu chuẩn: ${e.message}")
        }
    }

    private fun initOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Chưa cấp quyền SYSTEM_ALERT_WINDOW (Hiển thị trên ứng dụng khác)")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "⚠️ Cần cấp quyền 'Hiển thị trên ứng dụng khác'!", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }

        // Tuyệt đối chỉ tạo 1 lần duy nhất, nếu đã có thì chỉ bật lại VISIBLE
        if (isOverlayAdded && floatingView != null) {
            floatingView?.visibility = View.VISIBLE
            bubbleView?.visibility = View.GONE
            return
        }

        try {
            isOverlayAdded = true
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // BẮT BUỘC: Dùng ContextThemeWrapper để không bị crash Theme / Attribute khi inflate trong Service
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(this, R.style.Theme_RealtimeTranslator)
            val inflater = LayoutInflater.from(themedContext)

            floatingView = inflater.inflate(R.layout.layout_floating_widget, null)
            bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val targetWidth = minOf((330 * density).toInt(), (screenWidth * 0.92f).toInt())
            val yOffset = (100 * density).toInt()

            // Params cho floating widget chính
            params = WindowManager.LayoutParams(
                targetWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = yOffset
            }

            // Params RIÊNG cho bubble
            bubbleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (20 * density).toInt()
                y = (150 * density).toInt()
            }

            tvOriginal = floatingView?.findViewById(R.id.tvOriginalText)
            tvTranslated = floatingView?.findViewById(R.id.tvTranslatedText)
            tvOriginalTag = floatingView?.findViewById(R.id.tvOriginalTag)
            tvTranslatedTag = floatingView?.findViewById(R.id.tvTranslatedTag)
            scrollSubtitleArea = floatingView?.findViewById(R.id.scrollSubtitleArea)

            setupFloatingInteractions()
            setupBubbleInteractions()

            floatingView?.visibility = View.VISIBLE
            windowManager?.addView(floatingView, params)
            Log.d(TAG, "Đã thêm cửa sổ nổi vào WindowManager thành công")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "✅ Cửa sổ dịch nổi đã sẵn sàng!", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo Overlay: ${e.message}", e)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(this, "Lỗi vẽ khung nổi: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupFloatingInteractions() {
        val dragHandle = floatingView?.findViewById<View>(R.id.layoutDragHandle)
        val btnMinimize = floatingView?.findViewById<View>(R.id.btnMinimize)
        val btnClose = floatingView?.findViewById<View>(R.id.btnCloseOverlay)
        val btnSwap = floatingView?.findViewById<View>(R.id.btnSwapOverlay)
        val btnResize = floatingView?.findViewById<View>(R.id.btnResizeOverlay)
        val viewResizeCorner = floatingView?.findViewById<View>(R.id.viewResizeCorner)

        // 1. Nút bấm đổi cỡ nhanh (⤢): Nhỏ -> Vừa -> Lớn
        btnResize?.setOnClickListener {
            currentSizeLevel = (currentSizeLevel + 1) % 3
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels

            when (currentSizeLevel) {
                0 -> { // Nhỏ
                    params?.width = (270 * density).toInt()
                    tvOriginal?.textSize = 13f
                    tvTranslated?.textSize = 13f
                    android.widget.Toast.makeText(this, "Cỡ khung: Nhỏ (Compact)", android.widget.Toast.LENGTH_SHORT).show()
                }
                1 -> { // Vừa (Mặc định)
                    params?.width = minOf((340 * density).toInt(), (screenWidth * 0.92f).toInt())
                    tvOriginal?.textSize = 15f
                    tvTranslated?.textSize = 15f
                    android.widget.Toast.makeText(this, "Cỡ khung: Vừa (Standard)", android.widget.Toast.LENGTH_SHORT).show()
                }
                2 -> { // Lớn
                    params?.width = (screenWidth * 0.96f).toInt()
                    tvOriginal?.textSize = 17f
                    tvTranslated?.textSize = 17f
                    android.widget.Toast.makeText(this, "Cỡ khung: Lớn (Full)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            try {
                windowManager?.updateViewLayout(floatingView, params)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi updateViewLayout size: ${e.message}")
            }
        }

        // 2. Kéo góc dưới phải (⤡) để chỉnh độ rộng tùy ý
        viewResizeCorner?.setOnTouchListener(object : View.OnTouchListener {
            private var initialWidth = 0
            private var initialTouchX = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialWidth = params?.width ?: floatingView?.width ?: 300
                        initialTouchX = event.rawX
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val density = resources.displayMetrics.density
                        val minWidth = (220 * density).toInt()
                        val maxWidth = (resources.displayMetrics.widthPixels * 0.98f).toInt()
                        val newWidth = (initialWidth + dx).coerceIn(minWidth, maxWidth)

                        params?.width = newWidth
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        // Kéo thả di chuyển widget
        dragHandle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX + (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(floatingView, params)
                        } catch (e: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        // Nút thu nhỏ thành bong bóng (_)
        btnMinimize?.setOnClickListener {
            floatingView?.visibility = View.GONE
            try {
                if (!isBubbleAdded) {
                    windowManager?.addView(bubbleView, bubbleParams)
                    isBubbleAdded = true
                } else {
                    bubbleView?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi hiện bubble: ${e.message}")
            }
        }

        // Nút đóng hoàn toàn (✕)
        btnClose?.setOnClickListener {
            try {
                // Dừng hẳn dịch vụ thu âm để không bị lỗi gọi ngầm
                stopService(Intent(this, AudioCaptureService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi dừng AudioCaptureService: ${e.message}")
            }
            // Ẩn lập tức trên màn hình
            floatingView?.visibility = View.GONE
            bubbleView?.visibility = View.GONE
            stopSelf()
        }

        // Đảo chiều ngôn ngữ nhanh
        btnSwap?.setOnClickListener {
            val temp = srcLang
            srcLang = tgtLang
            tgtLang = temp
            updateLanguageTags()

            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_UPDATE_LANG
                putExtra(AudioCaptureService.EXTRA_SRC_LANG, srcLang)
                putExtra(AudioCaptureService.EXTRA_TGT_LANG, tgtLang)
            }
            startService(intent)
        }

        // Chuyển đổi nguồn âm thanh nhanh: Âm thanh máy (🔊) <-> Discord / Loa ngoài (🎙️)
        val btnAudioSource = floatingView?.findViewById<TextView>(R.id.btnAudioSource)
        btnAudioSource?.text = if (AudioCaptureService.currentAudioSource == AudioCaptureService.SOURCE_SPEAKER) "🎙️" else "🔊"
        btnAudioSource?.setOnClickListener {
            val newSource = if (AudioCaptureService.currentAudioSource == AudioCaptureService.SOURCE_INTERNAL) {
                AudioCaptureService.SOURCE_SPEAKER
            } else {
                AudioCaptureService.SOURCE_INTERNAL
            }

            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_SWITCH_SOURCE
                putExtra(AudioCaptureService.EXTRA_AUDIO_SOURCE, newSource)
            }
            startService(intent)

            if (newSource == AudioCaptureService.SOURCE_SPEAKER) {
                btnAudioSource.text = "🎙️"
                android.widget.Toast.makeText(
                    this,
                    "🎙️ Chế độ: Discord / Loa ngoài\n(Bật loa ngoài Discord để thu tiếng nói)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                btnAudioSource.text = "🔊"
                android.widget.Toast.makeText(
                    this,
                    "🔊 Chế độ: Âm thanh trong máy\n(YouTube, TikTok, Netflix, Game)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupBubbleInteractions() {
        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var startClickTime = 0L

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = bubbleParams?.x ?: 0
                        initialY = bubbleParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        startClickTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            bubbleParams?.x = initialX + dx
                            bubbleParams?.y = initialY + dy
                            try {
                                windowManager?.updateViewLayout(bubbleView, bubbleParams)
                            } catch (e: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val duration = System.currentTimeMillis() - startClickTime
                        val dx = Math.abs(event.rawX - initialTouchX)
                        val dy = Math.abs(event.rawY - initialTouchY)
                        if (duration < 350 && dx < 20 && dy < 20) {
                            // Chạm vào bong bóng -> Mở lại khung nổi
                            bubbleView?.visibility = View.GONE
                            floatingView?.visibility = View.VISIBLE
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: "en"
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: "vi"
                updateLanguageTags()
                // Khởi tạo overlay tại đây (an toàn hơn, chắc chắn quyền đã cấp)
                initOverlay()
            }
            ACTION_UPDATE_TEXT -> {
                val orig = intent.getStringExtra(EXTRA_ORIGINAL_TEXT) ?: ""
                val trans = intent.getStringExtra(EXTRA_TRANSLATED_TEXT) ?: ""
                if (orig.isNotEmpty()) tvOriginal?.text = orig
                if (trans.isNotEmpty()) tvTranslated?.text = trans
            }
            ACTION_UPDATE_LANG -> {
                srcLang = intent.getStringExtra(EXTRA_SRC_LANG) ?: srcLang
                tgtLang = intent.getStringExtra(EXTRA_TGT_LANG) ?: tgtLang
                updateLanguageTags()
            }
            ACTION_UPDATE_FONT_SIZE -> {
                val size = intent.getIntExtra(EXTRA_FONT_SIZE, 15).toFloat()
                tvOriginal?.textSize = size
                tvTranslated?.textSize = size
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun updateLanguageTags() {
        if (srcLang.startsWith("en", ignoreCase = true)) {
            tvOriginalTag?.text = "EN"
            tvTranslatedTag?.text = "VI"
        } else {
            tvOriginalTag?.text = "VI"
            tvTranslatedTag?.text = "EN"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cửa Sổ Dịch Âm Thanh",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cửa sổ dịch đang chạy")
            .setContentText("Nhấn để mở giao diện cài đặt")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isOverlayAdded = false
        isBubbleAdded = false
        try {
            if (floatingView != null && floatingView?.parent != null) {
                windowManager?.removeView(floatingView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi removeView floatingView onDestroy: ${e.message}")
        }
        try {
            if (bubbleView != null && bubbleView?.parent != null) {
                windowManager?.removeView(bubbleView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi removeView bubbleView onDestroy: ${e.message}")
        }
        floatingView = null
        bubbleView = null
    }
}
