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

    companion object {
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
    }
}
