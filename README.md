# 🔊 TransLive – AI Realtime Device Audio Translator (Android App)

<p align="center">
  <img src="web-simulator/logo.jpg" alt="TransLive Logo" width="120" style="border-radius: 28px;" />
</p>

[![GitHub Release](https://img.shields.io/github/v/release/Dohuycuong24050069/Audio-device-translation-?color=brightgreen&label=Latest%20Release)](https://github.com/Dohuycuong24050069/Audio-device-translation-/releases/latest)
[![Platform Android](https://img.shields.io/badge/Platform-Android%2010%2B-blue.svg)](https://developer.android.com)
[![Offline AI](https://img.shields.io/badge/AI-Vosk%20Offline-orange.svg)](https://alphacephei.com/vosk/)

A high-performance Android application for **Real-Time Internal Device Audio Translation** featuring an interactive **Floating Overlay Subtitle Window** that renders over any application (YouTube, TikTok, Netflix, Games, Reels, Podcast, etc.).

Powered by **Vosk Offline Speech-to-Text (STT)** and **Google Neural Translation** — **100% Free Forever, Zero Microphone Ambience, Pure Internal Device Audio Only**.

> 📥 **Direct APK Download**: **[TransLive_v1.0.7.apk (v1.0.7)](https://github.com/Dohuycuong24050069/Audio-device-translation-/releases/latest)** (~141 MB, Ready to install on Android).

---

## ✨ Key Features (v1.0.7)

### 1. 🔊 100% Pure Internal Device Audio (Không mic, không tạp âm ngoài)
- **Thu trực tiếp âm thanh phát ra trong máy**: Ứng dụng kết nối trực tiếp vào luồng xuất âm thanh hệ thống qua `AudioPlaybackCapture` (yêu cầu Android 10+).
- **Loại bỏ hoàn toàn Micro**: Không thu bất kỳ âm thanh nào từ môi trường ngoài, người dùng nói chuyện bên ngoài không ảnh hưởng đến phụ đề, bảo mật 100% sự riêng tư.
- **Tương thích hoàn hảo**: YouTube, TikTok, Facebook Reels, Netflix, Twitch, Game (PUBG, Genshin, v.v.), Podcast, Trình duyệt Web.

> ⚠️ **Lưu ý về Discord / Cuộc gọi thoại VoIP**:
> Hệ điều hành Android áp dụng chính sách bảo mật nghiêm ngặt (`USAGE_VOICE_COMMUNICATION`), tuyệt đối không cho phép bất kỳ ứng dụng thứ 3 nào được phép ghi lại luồng âm thanh đàm thoại cuộc gọi (Discord, Zalo Call, Messenger Call, Phone Call) qua giao thức chụp âm thanh hệ thống. Vì vậy ứng dụng được thiết kế tối ưu riêng biệt cho việc dịch âm thanh media/nội dung giải trí trong máy.

### 2. 📜 Smart Rolling Subtitles & Neural Translation
- **Phụ đề trượt thông minh (Rolling Subtitles)**: Câu nói dài không còn bị cắt bớt bằng dấu ba chấm `...`. Ứng dụng tự động giữ lại cụm từ mới nhất để bạn luôn đọc kịp dòng hội thoại đang diễn ra theo thời gian thực.
- **Tự động ngắt và làm mới sau khoảng lặng (Auto Sentence Reset)**: Khi người nói dứt câu quá 4.5 giây, khung phụ đề tự động làm mới để đón nhận câu tiếp theo.
- **Dịch tự nhiên theo ngữ cảnh (Neural GTX)**: Bản dịch tiếng Việt mượt mà, đúng văn phong tự nhiên thay vì dịch từng từ thô cứng.
- **Cuộn phụ đề mượt mà**: Tích hợp thanh cuộn mượt cho phép xem lại toàn bộ câu dài.

### 3. 🎨 Giao diện & Icon hiện đại mới (Modern Flat Aesthetic)
- Logo và Icon ứng dụng được thiết kế lại tối giản, phẳng (flat design) với dải sóng âm hiện đại (Indigo-Violet), không còn hiệu ứng nhựa bóng neon cũ.

### 4. 🪟 Khung phụ đề nổi (Floating Overlay Widget)
- **Cửa sổ dịch 2 dòng**: Dòng trên hiển thị tiếng gốc nhận diện ngay lập tức; dòng dưới hiển thị bản dịch tiếng Việt.
- **Nút đảo chiều ngôn ngữ nhanh (`⇄`)**: Đảo qua lại giữa Tiếng Anh ⇄ Tiếng Việt chỉ với 1 chạm.
- **Nút phóng to / thu nhỏ (`⤢`) & Bong bóng nổi (`—`)**: Thu gọn thành bong bóng nhỏ khi không dùng đến, chạm vào để mở lại.

---

## 📂 Project Structure

```
Audio-device-translation-/
├── android-app/                          # Native Android Project (Kotlin + Gradle)
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── assets/
│   │   │   │   └── model-en-us/          # Offline Vosk Acoustic & Language Model
│   │   │   ├── java/com/realtimetranslator/
│   │   │   │   ├── MainActivity.kt       # Permissions, Source toggle & Service launcher
│   │   │   │   ├── AudioCaptureService.kt# 50ms Vosk PCM loop & Internal Audio capture
│   │   │   │   ├── FloatingOverlayService.kt # WindowManager overlay & bubble controller
│   │   │   │   └── TranslationEngine.kt  # Google ML Kit on-device translation client
│   │   │   ├── res/                      # Layouts (floating card, bubble) and drawables
│   │   │   └── AndroidManifest.xml       # Foreground Service types (mediaProjection, mic)
│   │   └── build.gradle.kts              # Vosk AAR, JNA, ML Kit & Android 14 configuration
│   └── gradle/wrapper/                   # Gradle 8.4 wrapper
│
├── web-simulator/                        # Browser-based Interactive Prototype
│   ├── index.html                        # Virtual device viewport & draggable subtitle widget
│   ├── style.css                         # Dark glassmorphism aesthetics
│   ├── app.js                            # Web Speech API & simulator logic
│   └── server.ps1                        # Lightweight local preview HTTP server
│
├── LiveAudioTranslator_Vosk_Offline.apk  # Prebuilt APK ready to install
└── README.md
```

---

## 🛠️ Technology Stack

| Component | Technology | Description |
|---|---|---|
| **Language** | Kotlin 1.9+, Java 17 | Type-safe native Android implementation |
| **Speech-to-Text** | `com.alphacephei:vosk-android:0.3.75` | Kaldi-based offline speech recognition |
| **Native Bindings** | `net.java.dev.jna:jna:5.13.0` | C/C++ native runtime bridging for Vosk |
| **Translation Engine** | Google ML Kit Translate (On-Device) | Local neural machine translation model |
| **Audio Capture** | `AudioPlaybackCapture` (Android 10+) | Low-level internal PCM stream interception |
| **Window System** | Android `WindowManager` | System-level floating overlay & draggable bubble |
| **Architecture** | Android Foreground Services + Coroutines | Non-blocking streaming I/O with background persistence |

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34 (Android 14)
- Physical device or emulator running **Android 10 (API 29)** or higher for internal device audio capture (Microphone mode works from Android 7.0+).

### Building from Source

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/Dohuycuong24050069/Audio-device-translation-.git
   cd Audio-device-translation-/android-app
   ```

2. **Assemble Debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```
   The generated APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

3. **Install via ADB**:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 📱 How to Use

1. **Install and Launch** the app on your Android device.
2. Grant the required permissions when prompted:
   - **Display over other apps** (`SYSTEM_ALERT_WINDOW`): Required to show the floating subtitle window.
   - **Microphone**: Required for audio recording.
   - **Screen / Audio capture**: Required on Android 10+ to intercept internal system audio.
3. Tap **"▶ Bắt đầu dịch" (Start Translation)**.
4. The floating subtitle window will appear. Switch to **YouTube, TikTok, Netflix, or any game**:
   - Spoken English audio is transcribed and translated to Vietnamese in real-time right above your active app!
5. **Overlay Controls**:
   - **Drag header**: Reposition anywhere on screen.
   - **`⇄`**: Swap source/target languages.
   - **`⤢`**: Switch window size (Compact / Standard / Full).
   - **Bottom-right corner**: Drag to freely resize width.
   - **`—`**: Minimize to a compact floating bubble.
   - **`✕`**: Completely stop all translation and background services.

---

## 🔒 Privacy & Permissions

- **100% On-Device Processing**: No voice data, audio streams, or transcribed text are ever transmitted to any external server.
- **Required Permissions**:
  - `RECORD_AUDIO`: Required for capturing device sound / microphone.
  - `FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Required by Android 14+ for background audio capture.
  - `SYSTEM_ALERT_WINDOW`: Required for rendering the floating subtitle widget over other apps.

---

## 📄 License

This project is licensed under the MIT License — feel free to use, modify, and distribute.
