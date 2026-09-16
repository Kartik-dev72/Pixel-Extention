# PixelPlayer+ 🎵

<p align="center">
  <img src="assets/icon.png" alt="App Icon" width="128"/>
</p>

<p align="center">
  <strong>Privacy-first Android music player with offline playback, lyrics, equalizer controls, casting, and online streaming support</strong><br>
  Built with Jetpack Compose and Material 3 Expressive
</p>

<p align="center">
  <img src="assets/screenshot1.jpg" alt="Screenshot 1" width="200" style="border-radius:26px;"/>
  <img src="assets/screenshot2.jpg" alt="Screenshot 2" width="200" style="border-radius:26px;"/>
  <img src="assets/screenshot3.jpg" alt="Screenshot 3" width="200" style="border-radius:26px;"/>
  <img src="assets/screenshot4.jpg" alt="Screenshot 4" width="200" style="border-radius:26px;"/>
</p>

<p align="center">
  <a href="https://github.com/Aaryan1101/PixelPlayer-Plus/releases/latest">
    <img src="https://img.shields.io/github/v/release/Aaryan1101/PixelPlayer-Plus?include_prereleases&logo=github&style=for-the-badge&label=Latest%20Release" alt="Latest Release">
  </a>
  <a href="https://github.com/Aaryan1101/PixelPlayer-Plus/releases">
    <img src="https://img.shields.io/github/downloads/Aaryan1101/PixelPlayer-Plus/total?logo=github&style=for-the-badge" alt="Total Downloads">
  </a>
  <img src="https://img.shields.io/badge/Android-11%2B-green?style=for-the-badge&logo=android" alt="Android 11+">
  <img src="https://img.shields.io/badge/Kotlin-100%25-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
</p>

---

## 🌟 Overview

PixelPlayer+ is a modern Android music player designed for both offline listening and online discovery. It combines the elegance of Material 3 Expressive with advanced playback controls, library organization, synced lyrics, equalizer presets, casting support, and online streaming integration.

This version merges the polished UX of the enhanced PixelPlayer+ fork with the privacy-first philosophy of PixelPlayer Plus, creating a feature-rich and customizable music experience for Android users.

---

## ✨ Features

### 🎨 Modern UI / UX
- Material 3 Expressive design
- Material You dynamic theming
- Smooth animations and micro-interactions
- Dark / light theme support
- Customizable corner radius and navigation settings
- Dynamic album art color extraction

### 🎵 Powerful Playback
- Offline music playback
- Online streaming support via YouTube / NewPipe / Piped
- Media3 ExoPlayer backend
- Background playback with media session integration
- Gapless playback and custom transitions
- Shuffle, repeat, queue management
- Drag-and-drop reordering
- Mixed playlists with local and online tracks

### 📚 Library Management
- Multi-format support: MP3, FLAC, AAC, OGG, WAV, and more
- Browse by songs, albums, artists, genres, and folders
- Full-text library search
- Smart artist parsing with configurable delimiters
- Album artist grouping
- Folder filtering and scanning control

### 🎤 Lyrics
- Synced lyrics support (LRC via LRCLIB)
- Lyrics editing and customization
- Scrolling display while listening

### 🔊 Audio Control
- Equalizer presets and fine-tuning
- Audio enhancements for deeper listening
- Full media controls and playback customization

### 🖼️ Artwork & Metadata
- Automatic album art fetching
- Artist image enhancement from Deezer
- Smart caching for offline access
- Metadata enrichment for online content
- Tag editing support with TagLib

### 🌐 Online Features
- YouTube search and streaming
- Piped API integration
- NewPipe extractor support
- Privacy-friendly online streaming options
- Easy music discovery without leaving the app

### 📲 Connectivity
- Chromecast support
- Android Auto compatibility
- Home screen widgets
- Casting to supported devices

### ⚙️ Advanced Features
- AI playlist generation support
- Download and caching management
- Background processing optimization
- Performance improvements for smoother startup and UI responsiveness
- Better resource handling and playback reliability

---

## 🛠️ Tech Stack

| Category | Technology |
|----------|------------|
| **Language** | [Kotlin](https://kotlinlang.org/) 100% |
| **UI Framework** | [Jetpack Compose](https://developer.android.com/jetpack/compose) |
| **Design System** | [Material Design 3](https://m3.material.io/) |
| **Audio Engine** | [Media3 ExoPlayer](https://developer.android.com/guide/topics/media/media3) + FFmpeg |
| **Architecture** | MVVM with StateFlow/SharedFlow |
| **DI** | [Hilt](https://dagger.dev/hilt/) |
| **Database** | [Room](https://developer.android.com/training/data-storage/room) |
| **Networking** | [Retrofit](https://square.github.io/retrofit/) + OkHttp |
| **Online Services** | [NewPipe](https://github.com/TeamNewPipe/NewPipe), [Piped](https://piped.video/) |
| **Image Loading** | [Coil](https://coil-kt.github.io/coil/) |
| **Async** | Kotlin Coroutines & Flow |
| **Background Tasks** | WorkManager |
| **Metadata** | [TagLib](https://github.com/nicholaus/taglib-android) |
| **Widgets** | [Glance](https://developer.android.com/jetpack/compose/glance) |

---

## 📱 Requirements

- Android 11 (API 30) or higher
- 4GB RAM recommended for smooth performance
- Internet connection for online features
- Storage space for caching and downloads

---

## 🚀 Getting Started

### Prerequisites

- Android Studio Ladybug | 2024.2.1 or newer
- Android SDK 29+
- JDK 17 (recommended)

### Installation

1. Clone the repository
   ```sh
   git clone https://github.com/Kartik-dev72/PixelPlayer_Plus.git
   ```

2. Open in Android Studio
   - Open Android Studio
   - Select "Open an Existing Project"
   - Navigate to the cloned directory

3. Sync and build
   - Wait for Gradle to sync dependencies
   - Build the project (Build → Make Project)

4. Configure API keys if needed
   - Add any required API keys to `local.properties`
   - Update API endpoints in network modules if necessary

5. Run the app
   - Connect a device or start an emulator
   - Click Run (▶️)

---

## ⬇️ Download

<p align="center">
  <a href="https://github.com/Kartik-dev72/PixelPlayer_Plus/releases">
    <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/main/get-it-on-github.png" alt="Get it on GitHub" height="60">
  </a>
</p>

---

## 📂 Project Structure

```text
app/src/main/java/com/theveloper/pixelplay/
├── data/
│   ├── database/       # Room entities, DAOs, migrations
│   ├── model/          # Domain models (Song, Album, Artist, etc.)
│   ├── network/        # API services (LRCLIB, Deezer, YouTube, Piped)
│   │   ├── deezer/     # Deezer API integration
│   │   ├── youtube/    # YouTube / NewPipe extraction
│   │   ├── piped/      # Piped API integration
│   │   └── lyrics/     # LRCLIB lyrics service
│   ├── preferences/    # DataStore preferences
│   ├── repository/     # Data repositories
│   ├── service/        # Music service, HTTP server
│   └── worker/         # WorkManager sync workers
├── di/                 # Hilt dependency injection modules
├── presentation/
│   ├── components/     # Reusable Compose components
│   ├── navigation/     # Navigation graph
│   ├── screens/        # Screen composables
│   └── viewmodel/      # ViewModels
├── ui/
│   ├── glancewidget/   # Home screen widgets
│   └── theme/          # Colors, typography, theming
├── utils/              # Extensions and utilities
└── test/               # Unit and instrumentation tests
```

---

## 🔒 Privacy & Offline First

- Designed with a privacy-first approach
- Offline-first playback experience
- Local library management without forced account setup
- Optional online features when internet is available

---

## 📄 License

This project is licensed under the MIT License - see [LICENSE](LICENSE) for details.

---

## 🙏 Acknowledgments

- Original PixelPlayer by [theovilardo](https://github.com/theovilardo)
- NewPipe for YouTube extraction
- Piped for privacy-friendly streaming
- Deezer for artist metadata and artwork
- All contributors and testers

---

<p align="center">
  Made with ❤️ by <a href="https://github.com/Kartik-dev72">Kartik</a>
  <br>
  <small>Based on <a href="https://github.com/theovilardo/PixelPlayer">PixelPlayer by theovilardo</a></small>
</p>
