# Paper Scanner - Android Document Scanner

A beautiful, user-friendly Android document scanner app with a warm Ghibli-inspired design.

## Features

- **Real-time Document Detection** - Uses edge detection algorithms to find document boundaries in the camera preview
- **Live Rectangle Overlay** - Soft green rectangle appears around detected documents with rounded corner markers
- **Focus Area Guide** - Dashed rectangle shows where to position documents when none detected
- **Back Camera Scanning** - Uses rear camera with CameraX for high-quality captures
- **Multi-Image Projects** - Organize scans into named project folders
- **Import from Gallery** - Import multiple existing images into any project
- **PDF Export** - Export entire project as a multi-page PDF with one tap
- **Configurable Export Folder** - Browse and select PDF export folder, or type a custom path
- **Image Filters** - Apply Color, Grayscale, or Black & White (adaptive threshold) filters
- **Image Enhancement** - Auto-contrast enhancement for better readability
- **Image Rotation** - Rotate images in 90° increments
- **Quality Control** - Adjustable JPEG compression quality (10-100%)
- **Auto Capture** - Automatically capture when a document is detected
- **Flash Control** - Toggle camera flash/torch
- **Swipe to Delete** - Swipe images to remove them from a project
- **Ghibli-Inspired UI** - Warm nature tones, rounded corners, soft shadows, friendly design

## Design

The app uses a Ghibli-inspired color palette:
- **Primary**: Soft forest green `#5B8C5A`
- **Accent**: Warm peach `#E8A87C`
- **Background**: Warm cream `#FFF9F0`
- **Cards**: Soft white with rounded corners and subtle shadows
- **Buttons**: Rounded pill shapes with ripple effects

## Architecture

```
com.paperscanner/
├── data/
│   ├── Project.kt          - Project & ScanImage data classes
│   ├── ProjectManager.kt   - File-based project persistence (JSON)
│   └── AppSettings.kt      - SharedPreferences for app settings
├── processing/
│   ├── DocumentDetector.kt - Real-time edge-based document detection
│   ├── ImageFilter.kt      - Grayscale, B&W, enhance, rotate, crop
│   └── PdfExporter.kt      - Android PdfDocument PDF generation
└── ui/
    ├── ProjectListActivity.kt  - Home screen with project list
    ├── CameraActivity.kt       - Camera with live detection overlay
    ├── ImageEditorActivity.kt  - Image grid with filters & export
    ├── SettingsActivity.kt     - Quality, mode, path, and behavior settings
    └── ScannerOverlayView.kt   - Custom view for detection overlay
```

## Build Instructions

### Requirements

- Android Studio Hedgehog (2023.1.1) or later
- **JDK 17** (required by AGP 8.2.0 — JDK 21+ will fail the `core-for-system-modules` transform)
- Android SDK 34 (compileSdk/targetSdk)
- Min SDK 24 (Android 7.0)

### Build from Android Studio

1. Open the project in Android Studio
2. Let Gradle sync and download dependencies
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. The debug APK outputs to `app/build/outputs/apk/debug/`

### Build Release APK from Command Line

```powershell
# Set JDK 17 (required)
$env:JAVA_HOME = "C:\path\to\jdk-17"

# Build
.\gradlew assembleRelease
```

The signed release APK outputs to `app/build/outputs/apk/release/app-release.apk`.

### Signing Configuration

The release build is configured to sign with a keystore (`release.keystore`) in the project root:

```kotlin
signingConfigs {
    create("release") {
        storeFile = file("../release.keystore")
        storePassword = "paperscanner"
        keyAlias = "paperscanner"
        keyPassword = "paperscanner"
    }
}
```

> **Note:** The included `release.keystore` is for development/testing. For production distribution, generate your own keystore with a strong password and keep it secure. Never commit real keystores to version control.

To generate your own keystore:

```powershell
keytool -genkeypair -v -keystore release.keystore -alias paperscanner `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass <your-password> -keypass <your-password> `
  -dname "CN=Paper Scanner, OU=Dev, O=PaperScanner, L=City, ST=State, C=US"
```

**Install on device:**
```bash
adb install app/build/outputs/apk/release/app-release.apk
```

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| CameraX | 1.3.1 | Camera preview, capture, and analysis |
| Android PdfDocument | built-in | PDF generation (no external dependency) |
| Material Components | 1.11.0 | UI components |
| Kotlin Coroutines | 1.7.3 | Async processing |
| AndroidX Core / AppCompat / RecyclerView / CardView | latest | Framework |

## How It Works

### Document Detection
The app analyzes camera frames in real-time using a Sobel edge detection operator on the luminance channel. It finds the largest high-edge-density rectangular region and displays it as an overlay. When confidence exceeds a threshold in auto-capture mode, it automatically snaps the photo.

### Black & White Filter
Uses adaptive thresholding - for each pixel, it calculates the mean luminance of surrounding pixels and applies a threshold, producing clean B&W documents even under uneven lighting.

### PDF Export
Images are processed with their selected filter, scaled down if very large (max 3000px), and rendered onto PDF pages using Android's built-in `PdfDocument` API. Each image gets its own page sized to fit the image plus margins. The PDF is saved to the configured export folder and opened with the system PDF viewer.

### Folder Browser
The PDF export folder can be configured in Settings. Tap "Browse…" to open the system folder picker (ACTION_OPEN_DOCUMENT_TREE), or tap the path display to type a custom relative path.

## License

MIT License
