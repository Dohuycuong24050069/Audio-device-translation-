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

    private var tvOriginal: TextView? = null
    private var tvTranslated: TextView? = null
    private var tvOriginalTag: TextView? = null
    private var tvTranslatedTag: TextView? = null

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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafe()
        initOverlay()
    }

    private fun startForegroundSafe() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi startForeground: ${e.message}, thử startForeground cơ bản")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "Lỗi startForeground cơ bản: ${e2.message}")
            }
        }
    }

    private fun initOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Chưa cấp quyền SYSTEM_ALERT_WINDOW (Hiển thị trên ứng dụng khác)")
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(this)

            floatingView = inflater.inflate(R.layout.layout_floating_widget, null)
            bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 200
            }

            tvOriginal = floatingView?.findViewById(R.id.tvOriginalText)
            tvTranslated = floatingView?.findViewById(R.id.tvTranslatedText)
            tvOriginalTag = floatingView?.findViewById(R.id.tvOriginalTag)
            tvTranslatedTag = floatingView?.findViewById(R.id.tvTranslatedTag)

            setupFloatingInteractions()
            setupBubbleInteractions()

            floatingView?.visibility = View.VISIBLE
            windowManager?.addView(floatingView, params)
            Log.d(TAG, "Đã thêm cửa sổ nổi vào WindowManager thành công")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo Overlay: ${e.message}", e)
        }
    }

    private fun setupFloatingInteractions() {
        val dragHandle = floatingView?.findViewById<View>(R.id.layoutDragHandle)
        val btnMinimize = floatingView?.findViewById<View>(R.id.btnMinimize)
        val btnClose = floatingView?.findViewById<View>(R.id.btnCloseOverlay)
        val btnSwap = floatingView?.findViewById<View>(R.id.btnSwapOverlay)

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

        // Nút thu nhỏ thành bong bóng
        btnMinimize?.setOnClickListener {
            floatingView?.visibility = View.GONE
            try {
                if (bubbleView?.windowToken == null) {
                    windowManager?.addView(bubbleView, params)
                } else {
                    bubbleView?.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi hiện bubble: ${e.message}")
            }
        }

        // Nút đóng
        btnClose?.setOnClickListener {
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
    }

    private fun setupBubbleInteractions() {
        bubbleView?.setOnClickListener {
            bubbleView?.visibility = View.GONE
            floatingView?.visibility = View.VISIBLE
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
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
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params?.x = initialX + (event.rawX - initialTouchX).toInt()
                        params?.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager?.updateViewLayout(bubbleView, params)
                        } catch (e: Exception) {}
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
                if (floatingView?.windowToken == null) {
                    initOverlay()
                } else {
                    floatingView?.visibility = View.VISIBLE
                }
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
            .setSmallIcon(R.drawable.bg_bubble)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (floatingView != null && floatingView?.windowToken != null) {
                windowManager?.removeView(floatingView)
            }
            if (bubbleView != null && bubbleView?.windowToken != null) {
                windowManager?.removeView(bubbleView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi removeView onDestroy: ${e.message}")
        }
    }
}
