# 🎙️ TransLive – AI Realtime Audio Translator (100% Offline Android App)

<p align="center">
  <img src="web-simulator/logo.jpg" alt="TransLive Logo" width="120" style="border-radius: 24px;" />
</p>

[![GitHub Release](https://img.shields.io/github/v/release/Dohuycuong24050069/Audio-device-translation-?color=brightgreen&label=Latest%20Release)](https://github.com/Dohuycuong24050069/Audio-device-translation-/releases/latest)
[![Platform Android](https://img.shields.io/badge/Platform-Android%2010%2B-blue.svg)](https://developer.android.com)
[![Offline AI](https://img.shields.io/badge/AI-Vosk%20Offline-orange.svg)](https://alphacephei.com/vosk/)

A high-performance Android application for **Real-Time Internal Device Audio & Microphone Translation** featuring an interactive **Floating Overlay Subtitle Window** that renders over any application (YouTube, TikTok, Netflix, Games, Reels, etc.).

Powered by **Vosk Offline Speech-to-Text (STT)** and **Google ML Kit On-Device Translation** — **100% Free Forever, No API Keys Required, and Fully Private**.

> 📥 **Direct APK Download**: **[TransLive_v1.0.1.apk (v1.0.1)](https://github.com/Dohuycuong24050069/Audio-device-translation-/releases/latest)** (~144 MB, Ready to install on Android).

---

## ✨ Key Features

### 1. 🧠 100% Offline Speech Recognition & Translation
- **Vosk AI Offline STT**: Bundles the `vosk-model-small-en-us-0.15` model directly inside Android assets. Automatically uncompressed to private internal app storage on first launch with deterministic verification. Zero network requests, no Google Cloud speech quotas, and zero recurring costs.
- **Ultra-Low Latency Pipeline (50ms)**: Samples audio at 16 kHz Mono PCM using an optimized 800-sample buffer with pre-allocated byte arrays, completely eliminating Garbage Collection (GC) pauses for instant transcription.
- **Google ML Kit On-Device Translation**: Translates English to Vietnamese (and vice-versa) on-device with sub-30ms inference speed.
- **Live-Streaming Sentence Translation**: Translates incrementally as speech occurs (~200ms debounce), so Vietnamese subtitles update in parallel with spoken words rather than waiting for sentence completion.

### 2. 🔊 Dual Audio Source Capture
- **Internal Device Audio (Android 10+)**: Utilizes `AudioPlaybackCaptureConfiguration` combined with `MediaProjection` to capture clean internal audio from YouTube, TikTok, podcasts, and games with zero ambient noise or microphone echo.
- **Microphone Mode**: One-tap switch in `MainActivity` to capture and transcribe external ambient speech and conversations.

### 3. 🪟 Interactive Floating Overlay Widget
- **Dual Subtitle Display**:
  - **Top Row**: Real-time speech transcript from internal audio/mic.
  - **Bottom Row**: Instant Vietnamese translation.
- **Dynamic Resizing**:
  - **Cycle Presets (`⤢`)**: Toggle between Compact (270dp), Standard (340dp), and Full-width mode with automatic font scaling.
  - **Freeform Corner Drag (`viewResizeCorner`)**: Drag the bottom-right handle to adjust overlay width smoothly.
- **Minimization & Floating Bubble (`—`)**:
  - Tap `—` to collapse into a floating circular bubble.
  - Smooth dragging and tap-to-restore (<350ms click detection).
- **Fast Language Swap (`⇄`)**: Invert translation direction on the fly.
- **Touch-Conflict-Free Controls**: Control buttons have isolated touch targets with ripple effects, preventing accidental window dragging when pressing buttons.
- **Single-Instance Enforcement**: Strict lifecycle management preventing duplicate overlay windows.

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
