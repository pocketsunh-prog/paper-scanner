package com.paperscanner.data

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("paper_scanner_settings", Context.MODE_PRIVATE)

    var jpegQuality: Int
        get() = prefs.getInt(KEY_JPEG_QUALITY, 85)
        set(value) = prefs.edit().putInt(KEY_JPEG_QUALITY, value).apply()

    var imageFilterMode: ImageFilterMode
        get() = ImageFilterMode.valueOf(prefs.getString(KEY_FILTER_MODE, "COLOR") ?: "COLOR")
        set(value) = prefs.edit().putString(KEY_FILTER_MODE, value.name).apply()

    var autoCapture: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()

    var flashEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLASH, false)
        set(value) = prefs.edit().putBoolean(KEY_FLASH, value).apply()

    var showFocusArea: Boolean
        get() = prefs.getBoolean(KEY_SHOW_FOCUS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_FOCUS, value).apply()

    var pdfExportPath: String
        get() = prefs.getString(KEY_PDF_EXPORT_PATH, "PDFs") ?: "PDFs"
        set(value) = prefs.edit().putString(KEY_PDF_EXPORT_PATH, value).apply()

    var pdfTreeUri: String?
        get() = prefs.getString(KEY_PDF_TREE_URI, null)
        set(value) = prefs.edit().putString(KEY_PDF_TREE_URI, value).apply()

    // Camera settings
    var cameraResolution: String
        get() = prefs.getString(KEY_CAMERA_RESOLUTION, "MEDIUM") ?: "MEDIUM"
        set(value) = prefs.edit().putString(KEY_CAMERA_RESOLUTION, value).apply()

    var pictureSize: String
        get() = prefs.getString(KEY_PICTURE_SIZE, "4:3") ?: "4:3"
        set(value) = prefs.edit().putString(KEY_PICTURE_SIZE, value).apply()

    var cameraColorMode: String
        get() = prefs.getString(KEY_CAMERA_COLOR, "COLOR") ?: "COLOR"
        set(value) = prefs.edit().putString(KEY_CAMERA_COLOR, value).apply()

    // Text-to-speech settings
    /** Name of the selected TTS voice ("speaker"). Null = pick a sensible default. */
    var ttsVoiceName: String?
        get() = prefs.getString(KEY_TTS_VOICE, null)
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    /** BCP-47 tag of the preferred speaking language. Defaults to Hong Kong Cantonese. */
    var ttsLanguageTag: String
        get() = prefs.getString(KEY_TTS_LANGUAGE, DEFAULT_TTS_LANGUAGE_TAG)
            ?: DEFAULT_TTS_LANGUAGE_TAG
        set(value) = prefs.edit().putString(KEY_TTS_LANGUAGE, value).apply()

    /** Speech rate multiplier, 0.5x..2.0x */
    var ttsSpeechRate: Float
        get() = prefs.getFloat(KEY_TTS_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_RATE, value.coerceIn(0.5f, 2.0f)).apply()

    /** Speech pitch multiplier, 0.5x..2.0x */
    var ttsPitch: Float
        get() = prefs.getFloat(KEY_TTS_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_PITCH, value.coerceIn(0.5f, 2.0f)).apply()

    /** Start reading automatically when the Text-to-Speech screen opens with text. */
    var ttsAutoSpeak: Boolean
        get() = prefs.getBoolean(KEY_TTS_AUTO_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_AUTO_SPEAK, value).apply()

    // Document detection settings (edited on the Settings screen)
    /** Quick on/off for document detection, toggled from the camera screen. */
    var detectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_DETECTION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_DETECTION_ENABLED, value).apply()

    /** "AUTO" hugs the detected page corners, "MANUAL" uses the draggable rectangle. */
    var detectionMode: String
        get() = prefs.getString(KEY_DETECTION_MODE, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_DETECTION_MODE, value).apply()

    /** Draw the green outline around the detected page. */
    var showDetectionOutline: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETECTION_OUTLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DETECTION_OUTLINE, value).apply()

    /** Show the "Document detected" / "Point camera at a document" pill. */
    var showDetectionStatus: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DETECTION_STATUS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DETECTION_STATUS, value).apply()

    companion object {
        /** Speaker used until the user picks another one: Hong Kong (Cantonese). */
        const val DEFAULT_TTS_LANGUAGE_TAG = "zh-HK"

        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_FILTER_MODE = "filter_mode"
        private const val KEY_AUTO_CAPTURE = "auto_capture"
        private const val KEY_FLASH = "flash"
        private const val KEY_SHOW_FOCUS = "show_focus_area"
        private const val KEY_PDF_EXPORT_PATH = "pdf_export_path"
        private const val KEY_PDF_TREE_URI = "pdf_tree_uri"
        private const val KEY_CAMERA_RESOLUTION = "camera_resolution"
        private const val KEY_PICTURE_SIZE = "picture_size"
        private const val KEY_CAMERA_COLOR = "camera_color"
        private const val KEY_TTS_VOICE = "tts_voice_name"
        private const val KEY_TTS_LANGUAGE = "tts_language_tag"
        private const val KEY_TTS_RATE = "tts_speech_rate"
        private const val KEY_TTS_PITCH = "tts_pitch"
        private const val KEY_TTS_AUTO_SPEAK = "tts_auto_speak"
        private const val KEY_DETECTION_MODE = "detection_mode"
        private const val KEY_DETECTION_ENABLED = "detection_enabled"
        private const val KEY_SHOW_DETECTION_OUTLINE = "show_detection_outline"
        private const val KEY_SHOW_DETECTION_STATUS = "show_detection_status"
    }
}
