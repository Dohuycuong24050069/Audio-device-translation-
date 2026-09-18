# 🎙️ LiveAudio Realtime Translator (Android & Web Simulator)

Ứng dụng di động **Dịch Âm Thanh Thiết Bị & Micro Thời Gian Thực (Real-Time)** với **Cửa Sổ Nổi (Floating Overlay)** độc đáo đè lên tất cả ứng dụng khác trên Android.

---

## ✨ Tính Năng Nổi Bật

- ⚡ **Dịch sát thời gian thực (Real-time Streaming)**: Dịch 2 chiều giữa **Tiếng Anh (EN) ⇄ Tiếng Việt (VI)** với độ trễ siêu thấp.
- 🪟 **Cửa sổ nổi (Floating Overlay Widget)**:
  - **Dòng trên**: Hiển thị phụ đề tiếng Anh nhận dạng trực tiếp từ âm thanh thiết bị/micro chạy liên tục.
  - **Dòng dưới**: Hiển thị bản dịch tiếng Việt xuất hiện tức thì sát theo lời nói.
  - Kéo thả di chuyển tự do khắp màn hình (`WindowManager.addView`).
  - Nút đảo chiều nhanh `⇄` (EN ⇄ VI) và nút thu nhỏ `—` thành bong bóng nổi tròn (Floating Bubble).
- 🔊 **Dịch âm thanh thiết bị (Internal Device Audio)**:
  - Sử dụng Android 10+ `AudioPlaybackCaptureConfiguration` & `MediaProjection` để thu âm thanh trực tiếp từ **YouTube, TikTok, Facebook Reels, Phim, Game** mà không bị lẫn tạp âm môi trường.
  - Hỗ trợ chuyển đổi linh hoạt sang Microphone để dịch giọng nói ngoài đời thực.
- 🧠 **Google ML Kit On-Device Translation**:
  - Dịch hoàn toàn ngoại tuyến (**Offline**), bảo mật 100% dữ liệu âm thanh, không mất chi phí API key, hoạt động ổn định mọi lúc mọi nơi.
- 📱 **Tương thích toàn diện**:
  - Hỗ trợ từ **Android 7.0 đến Android 14/15** (API 24–34).
  - Tích hợp chuẩn bảo mật Android 14 với `MediaProjection.Callback` và Foreground Service Type.

---

## 📂 Cấu Trúc Dự Án

```
realtime-translator/
├── android-app/                   # Mã nguồn Native Android (Kotlin + Gradle)
│   ├── app/src/main/java/com/realtimetranslator/
│   │   ├── MainActivity.kt          # Giao diện điều khiển & cấp quyền
│   │   ├── FloatingOverlayService.kt # Dịch vụ vẽ cửa sổ nổi đè màn hình
│   │   ├── AudioCaptureService.kt   # Thu âm thanh thiết bị / micro real-time
│   │   └── TranslationEngine.kt     # Động cơ dịch Google ML Kit On-Device
│   ├── app/src/main/res/            # Layout XML, Drawables, Mipmap vector icons
│   └── app/build.gradle.kts         # Cấu hình SDK 34, minSdk 24, Release signing
│
├── web-simulator/                 # Bộ giả lập chạy trực tiếp trên trình duyệt
│   ├── index.html                 # Giao diện Smartphone + Floating Widget Vector
│   ├── style.css                  # Dark Mode Glassmorphism cao cấp
│   └── app.js                     # Web Speech API + MyMemory Translate + Drag & Drop
│
└── RealtimeTranslator.apk         # File cài đặt APK hoàn chỉnh (~63 MB)
```

---

## 🚀 Hướng Dẫn Cài Đặt & Sử Dụng

### 1. Cài đặt APK trên điện thoại Android
1. Tải file [`RealtimeTranslator.apk`](RealtimeTranslator.apk).
2. Mở file trên điện thoại -> Chọn **Cài đặt** (Nếu có cảnh báo Play Protect, chọn *"Chi tiết khác"* -> *"Vẫn cài đặt"*).
3. Mở app -> Bấm **"Bắt đầu dịch"**:
   - Cho phép quyền **"Hiển thị trên ứng dụng khác"** (để vẽ khung nổi).
   - Cho phép quyền **"Microphone"** và **"Bắt đầu chụp màn hình/âm thanh"** (để thu âm nội bộ).
4. Cửa sổ dịch nổi sẽ xuất hiện ngay trên màn hình. Mở bất kỳ video YouTube hay game nào để xem dịch phụ đề tức thì!

### 2. Chạy thử trên máy tính (Web Simulator)
Mở file `web-simulator/index.html` bằng trình duyệt Google Chrome hoặc Edge để trải nghiệm tương tác kéo thả khung dịch và nói vào micro.

---

## 🛠️ Công Nghệ Sử Dụng

- **Ngôn ngữ**: Kotlin, Java, JavaScript, HTML5, CSS3
- **Android Framework**: WindowManager, MediaProjection, AudioPlaybackCapture, ForegroundService
- **AI & NLP**: Google ML Kit Translate, Android SpeechRecognizer, Web Speech API
- **Build System**: Gradle 8.4, Android Gradle Plugin 8.2.2, JDK 17
