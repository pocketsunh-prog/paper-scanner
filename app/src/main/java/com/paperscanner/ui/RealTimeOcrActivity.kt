package com.paperscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.widget.*
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.paperscanner.R
import com.paperscanner.processing.SpeechHelper
import com.paperscanner.processing.TranslationHelper
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class RealTimeOcrActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: OcrOverlayView
    private lateinit var fabClose: FloatingActionButton
    private lateinit var fabTranslate: FloatingActionButton
    private lateinit var fabCapture: FloatingActionButton
    private lateinit var fabSpeak: FloatingActionButton
    private lateinit var tvDetectedText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var tvZoomLevel: TextView

    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService
    private var lastDetectedText = ""
    private var selectedSourceLang = "english"
    private var selectedTargetLang = "chinese"
    private var isProcessing = false
    private var lastTranslatedText = ""

    private lateinit var speech: SpeechHelper

    init {
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime_ocr)

        speech = SpeechHelper(this)

        viewFinder = findViewById(R.id.view_finder)
        overlayView = findViewById(R.id.ocr_overlay)
        fabClose = findViewById(R.id.fab_close)
        fabTranslate = findViewById(R.id.fab_translate)
        fabCapture = findViewById(R.id.fab_capture_ocr)
        fabSpeak = findViewById(R.id.fab_speak)
        tvDetectedText = findViewById(R.id.tv_detected_text)
        tvTranslatedText = findViewById(R.id.tv_translated_text)
        progressBar = findViewById(R.id.progress_bar)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        tvZoomLevel = findViewById(R.id.tv_zoom_level)

        btnZoomIn.setOnClickListener { zoomBy(0.1f) }
        btnZoomOut.setOnClickListener { zoomBy(-0.1f) }

        fabClose.setOnClickListener { finish() }

        fabTranslate.setOnClickListener {
            showLanguageSelectionDialog()
        }

        fabCapture.setOnClickListener {
            if (lastDetectedText.isNotEmpty()) {
                showFullResultDialog()
            } else {
                Toast.makeText(this, "No text detected", Toast.LENGTH_SHORT).show()
            }
        }

        // Read the translated text (or the original) out loud; tap again to stop
        fabSpeak.setOnClickListener {
            if (speech.isSpeaking) {
                speech.stop()
                return@setOnClickListener
            }

            val text = lastTranslatedText.ifBlank { lastDetectedText }
            if (text.isBlank()) {
                Toast.makeText(this, R.string.no_text_detected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Use a speaker that matches the language being read out
            SpeechHelper.localeForOcrLanguage(selectedTargetLang)?.let { speech.useLanguage(it) }
            speech.speak(text)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 10
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )

                // Initialize zoom display and listen for changes
                val initialZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                updateZoomDisplay(initialZoom)
                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    updateZoomDisplay(zoomState.zoomRatio)
                }

                setupPinchToZoom()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        isProcessing = true

        try {
            val mediaImage = imageProxy.image ?: run {
                imageProxy.close()
                isProcessing = false
                return
            }

            val inputImage = InputImage.fromMediaImage(
                mediaImage, imageProxy.imageInfo.rotationDegrees
            )

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val detectedText = visionText.text
                    lastDetectedText = detectedText

                    runOnUiThread {
                        overlayView.updateTextBlocks(visionText)
                        tvDetectedText.text = detectedText.ifEmpty { "Point camera at text" }
                        progressBar.visibility = View.GONE
                    }

                    // Live translate
                    if (detectedText.isNotEmpty()) {
                        translateLive(detectedText)
                    }

                    isProcessing = false
                }
                .addOnFailureListener {
                    isProcessing = false
                }
        } catch (e: Exception) {
            isProcessing = false
        } finally {
            imageProxy.close()
        }
    }

    private fun translateLive(text: String) {
        lifecycleScope.launch {
            try {
                val result = TranslationHelper.translate(text, selectedTargetLang, selectedSourceLang)
                lastTranslatedText = result.translatedText
                runOnUiThread {
                    tvTranslatedText.text = result.translatedText
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvTranslatedText.text = "Translation unavailable"
                }
            }
        }
    }

    private fun showLanguageSelectionDialog() {
        val languages = arrayOf(
            "English → 简体中文",
            "English → 繁體中文",
            "English → 日本語",
            "English → 한국어",
            "English → Français",
            "English → Deutsch",
            "English → Español",
            "简体中文 → English",
            "繁體中文 → English",
            "日本語 → English",
            "한국어 → English"
        )

        AlertDialog.Builder(this)
            .setTitle("Select Translation Direction")
            .setItems(languages) { _, which ->
                when (which) {
                    0 -> { selectedSourceLang = "english"; selectedTargetLang = "chinese" }
                    1 -> { selectedSourceLang = "english"; selectedTargetLang = "traditional" }
                    2 -> { selectedSourceLang = "english"; selectedTargetLang = "japanese" }
                    3 -> { selectedSourceLang = "english"; selectedTargetLang = "korean" }
                    4 -> { selectedSourceLang = "english"; selectedTargetLang = "french" }
                    5 -> { selectedSourceLang = "english"; selectedTargetLang = "german" }
                    6 -> { selectedSourceLang = "english"; selectedTargetLang = "spanish" }
                    7 -> { selectedSourceLang = "chinese"; selectedTargetLang = "english" }
                    8 -> { selectedSourceLang = "traditional"; selectedTargetLang = "english" }
                    9 -> { selectedSourceLang = "japanese"; selectedTargetLang = "english" }
                    10 -> { selectedSourceLang = "korean"; selectedTargetLang = "english" }
                }
                Toast.makeText(this, "Translate: ${languages[which]}", Toast.LENGTH_SHORT).show()
                // Force re-translate current text
                if (lastDetectedText.isNotEmpty()) {
                    translateLive(lastDetectedText)
                }
            }
            .show()
    }

    private fun showFullResultDialog() {
        val message = buildString {
            appendLine("Original:")
            appendLine(lastDetectedText)
            appendLine()
            appendLine("Translated ($selectedTargetLang):")
            appendLine(lastTranslatedText.ifEmpty { "N/A" })
        }

        val scrollView = ScrollView(this).apply {
            setPadding(48, 32, 48, 32)
        }

        val textView = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(resources.getColor(R.color.text_primary, null))
            setTextIsSelectable(true)
        }

        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("OCR Result")
            .setView(scrollView)
            .setPositiveButton("Copy Original") { _, _ ->
                copyToClipboard(lastDetectedText)
            }
            .setNeutralButton("Copy Translated") { _, _ ->
                copyToClipboard(lastTranslatedText)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("OCR Text", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun zoomBy(delta: Float) {
        val camera = camera ?: return
        val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        val newZoom = (currentZoom + delta).coerceIn(1f, camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f)
        camera.cameraControl.setZoomRatio(newZoom)
        updateZoomDisplay(newZoom)
    }

    private fun setupPinchToZoom() {
        var lastDistance = 0f
        viewFinder.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        lastDistance = spacing(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val currentDistance = spacing(event)
                        if (lastDistance > 0) {
                            val delta = (currentDistance - lastDistance) / 200f
                            val cam = camera ?: return@setOnTouchListener false
                            val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
                            val newZoom = (currentZoom + delta).coerceIn(1f, maxZoom)
                            cam.cameraControl.setZoomRatio(newZoom)
                            updateZoomDisplay(newZoom)
                        }
                        lastDistance = currentDistance
                    }
                }
            }
            false
        }
    }

    private fun spacing(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun updateZoomDisplay(zoomRatio: Float) {
        tvZoomLevel.text = String.format("%.1fx", zoomRatio)
    }

    override fun onPause() {
        super.onPause()
        speech.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        speech.shutdown()
        TranslationHelper.releaseAll()
    }
}
