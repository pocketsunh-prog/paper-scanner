package com.paperscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.paperscanner.R
import com.paperscanner.data.AppSettings
import com.paperscanner.data.ProjectManager
import com.paperscanner.data.ScanImage
import com.paperscanner.processing.DocumentDetector
import com.paperscanner.processing.ImageFilter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: ScannerOverlayView
    private lateinit var btnCapture: Button
    private lateinit var btnFlash: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnAutoCapture: ToggleButton
    private lateinit var btnZoomIn: ImageButton
    private lateinit var btnZoomOut: ImageButton
    private lateinit var tvZoomLevel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var projectManager: ProjectManager
    private lateinit var settings: AppSettings
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var projectId: String = ""
    private var flashEnabled = false
    private var autoCaptureEnabled = false
    private val isCapturing = AtomicBoolean(false)
    private var lastAnalysisTime = 0L

    // Detection results for cropping
    private var lastDetectedBounds: android.graphics.RectF? = null
    private var lastDetectedCorners: List<Pair<Float, Float>>? = null
    private var analysisWidth = 640
    private var analysisHeight = 480

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        projectId = intent.getStringExtra("project_id") ?: ""
        if (projectId.isEmpty()) {
            Toast.makeText(this, "No project selected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        projectManager = ProjectManager(this)
        settings = AppSettings(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        viewFinder = findViewById(R.id.view_finder)
        overlayView = findViewById(R.id.overlay_view)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_flash)
        btnClose = findViewById(R.id.btn_close)
        btnAutoCapture = findViewById(R.id.btn_auto_capture)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        tvZoomLevel = findViewById(R.id.tv_zoom_level)
        progressBar = findViewById(R.id.progress_bar)
        tvStatus = findViewById(R.id.tv_status)

        overlayView.setShowFocusArea(settings.showFocusArea)
        flashEnabled = settings.flashEnabled
        autoCaptureEnabled = settings.autoCapture
        updateFlashIcon()
        btnAutoCapture.isChecked = autoCaptureEnabled

        // Tap-to-capture: tap inside detected rectangle to capture
        overlayView.onTapToCapture = { captureImage() }

        btnClose.setOnClickListener { finish() }
        btnCapture.setOnClickListener { captureImage() }
        btnFlash.setOnClickListener {
            flashEnabled = !flashEnabled
            settings.flashEnabled = flashEnabled
            updateFlashIcon()
            camera?.cameraControl?.enableTorch(flashEnabled)
        }
        btnAutoCapture.setOnCheckedChangeListener { _, isChecked ->
            autoCaptureEnabled = isChecked
            settings.autoCapture = isChecked
        }

        // Real-time OCR button
        val fabRealTimeOcr = findViewById<FloatingActionButton>(R.id.fab_realtime_ocr)
        fabRealTimeOcr.setOnClickListener {
            startActivity(android.content.Intent(this, RealTimeOcrActivity::class.java))
        }

        // Mode toggle (Auto / Manual)
        val toggleMode = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggle_mode)
        val btnModeAuto = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_mode_auto)
        val btnModeManual = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_mode_manual)

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btn_mode_auto -> {
                        overlayView.autoMode = true
                        tvStatus.text = getString(R.string.point_at_document)
                    }
                    R.id.btn_mode_manual -> {
                        overlayView.autoMode = false
                        tvStatus.text = "Position rectangle over document"
                    }
                }
                overlayView.invalidate()
            }
        }
        // Start in auto mode
        toggleMode.check(R.id.btn_mode_auto)

        // Zoom controls
        btnZoomIn.setOnClickListener { zoomBy(0.1f) }
        btnZoomOut.setOnClickListener { zoomBy(-0.1f) }

        // Pinch-to-zoom gesture on preview
        setupPinchToZoom()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Get resolution from settings
            val captureResolution = getCaptureResolution()
            val analysisResolution = getAnalysisResolution()

            val preview = Preview.Builder()
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                captureResolution,
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                captureResolution,
                                androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(analysisResolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processFrame(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )
                if (flashEnabled) {
                    camera?.cameraControl?.enableTorch(true)
                }
                // Initialize zoom display and listen for changes
                val initialZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                updateZoomDisplay(initialZoom)
                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    updateZoomDisplay(zoomState.zoomRatio)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getCaptureResolution(): Size {
        return when (settings.cameraResolution) {
            "LOW" -> Size(1280, 720)      // 720p
            "HIGH" -> Size(3840, 2160)     // 4K (or max available)
            else -> Size(1920, 1080)       // 1080p (MEDIUM default)
        }
    }

    private fun getAnalysisResolution(): Size {
        // Analysis always uses smaller resolution for performance
        return Size(640, 480)
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < 150) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = currentTime

        try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = imageProxy.width
            val height = imageProxy.height
            analysisWidth = width
            analysisHeight = height

            val luminance = DocumentDetector.yuvToLuminance(bytes, width, height)
            val result = DocumentDetector.detectDocument(luminance, width, height)

            runOnUiThread {
                overlayView.updateDetection(result)

                if (result.found) {
                    lastDetectedBounds = result.bounds
                    lastDetectedCorners = result.corners
                    tvStatus.text = getString(R.string.document_detected)
                    tvStatus.setBackgroundResource(R.drawable.ghibli_button_primary)
                } else {
                    lastDetectedBounds = null
                    lastDetectedCorners = null
                    tvStatus.text = getString(R.string.point_at_document)
                    tvStatus.setBackgroundResource(R.drawable.ghibli_path_bg)
                }

                if (autoCaptureEnabled && result.found && result.confidence > 0.3f) {
                    if (isCapturing.compareAndSet(false, true)) {
                        captureImage()
                        isCapturing.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore frame processing errors
        } finally {
            imageProxy.close()
        }
    }

    private fun captureImage() {
        // In auto mode, require corner detection
        if (overlayView.autoMode && (lastDetectedCorners == null || lastDetectedCorners?.size != 4)) {
            Toast.makeText(this, "No document detected", Toast.LENGTH_SHORT).show()
            return
        }

        val imageCapture = imageCapture ?: return
        progressBar.visibility = View.VISIBLE
        btnCapture.isEnabled = false

        val photoFile = projectManager.generateImageFile(projectId)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Crop to rectangle area (auto: perspective, manual: rectangle)
                    val success = cropAndSaveImage(photoFile.absolutePath)

                    if (success) {
                        val filterMode = when (settings.cameraColorMode) {
                            "GRAYSCALE" -> com.paperscanner.data.ImageFilterMode.GRAYSCALE
                            "BLACK_WHITE" -> com.paperscanner.data.ImageFilterMode.BLACK_WHITE
                            else -> com.paperscanner.data.ImageFilterMode.COLOR
                        }

                        val scanImage = ScanImage(
                            filePath = photoFile.absolutePath,
                            filterMode = filterMode
                        )
                        val project = projectManager.getProject(projectId)
                        project?.images?.add(scanImage)
                        project?.let { projectManager.saveProject(it) }

                        Toast.makeText(
                            this@CameraActivity,
                            "Document captured",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        File(photoFile.absolutePath).delete()
                        Toast.makeText(
                            this@CameraActivity,
                            "Capture failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    progressBar.visibility = View.GONE
                    btnCapture.isEnabled = true
                }

                override fun onError(exception: ImageCaptureException) {
                    progressBar.visibility = View.GONE
                    btnCapture.isEnabled = true
                    Toast.makeText(
                        this@CameraActivity,
                        "Capture failed: ${exception.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    /**
     * Crops to rectangle area only.
     * Auto mode: perspective transform using detected corners.
     * Manual mode: rectangle crop using manual overlay rectangle.
     * Returns true if successful.
     */
    private fun cropAndSaveImage(imagePath: String): Boolean {
        try {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return false

            val cropped: Bitmap

            if (overlayView.autoMode) {
                // Auto mode: use detected corners for perspective transform
                val corners = lastDetectedCorners
                if (corners == null || corners.size != 4) {
                    bitmap.recycle()
                    return false
                }
                cropped = perspectiveCrop(bitmap, corners)
            } else {
                // Manual mode: use the manual rectangle overlay
                val rect = overlayView.getManualRect()
                val scaleX = bitmap.width.toFloat()
                val scaleY = bitmap.height.toFloat()

                val cropLeft = (rect.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
                val cropTop = (rect.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
                val cropRight = (rect.right * scaleX).toInt().coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = (rect.bottom * scaleY).toInt().coerceIn(cropTop + 1, bitmap.height)

                cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
            }

            // Apply color mode
            val colored = applyColorMode(cropped)
            if (colored != cropped) cropped.recycle()
            bitmap.recycle()

            // Save ONLY the inside rectangle area
            val fos = java.io.FileOutputStream(imagePath)
            colored.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, fos)
            fos.flush()
            fos.close()
            colored.recycle()
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Perspective transform crop: maps detected document corners to a clean rectangle.
     * Result contains only the document area with no background.
     */
    private fun perspectiveCrop(bitmap: Bitmap, corners: List<Pair<Float, Float>>): Bitmap {
        // Map normalized corners to full image pixel coordinates
        val tl = Pair(corners[0].first * bitmap.width, corners[0].second * bitmap.height)
        val tr = Pair(corners[1].first * bitmap.width, corners[1].second * bitmap.height)
        val br = Pair(corners[2].first * bitmap.width, corners[2].second * bitmap.height)
        val bl = Pair(corners[3].first * bitmap.width, corners[3].second * bitmap.height)

        // Calculate output dimensions from edge distances
        val widthTop = sqrt((tr.first - tl.first) * (tr.first - tl.first) + (tr.second - tl.second) * (tr.second - tl.second))
        val widthBottom = sqrt((br.first - bl.first) * (br.first - bl.first) + (br.second - bl.second) * (br.second - bl.second))
        val outputWidth = max(widthTop, widthBottom).toInt().coerceAtLeast(100)

        val heightLeft = sqrt((bl.first - tl.first) * (bl.first - tl.first) + (bl.second - tl.second) * (bl.second - tl.second))
        val heightRight = sqrt((br.first - tr.first) * (br.first - tr.first) + (br.second - tr.second) * (br.second - tr.second))
        val outputHeight = max(heightLeft, heightRight).toInt().coerceAtLeast(100)

        // Create output bitmap and apply perspective transform
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val src = floatArrayOf(
            tl.first, tl.second,
            tr.first, tr.second,
            br.first, br.second,
            bl.first, bl.second
        )

        val dst = floatArrayOf(
            0f, 0f,
            outputWidth.toFloat(), 0f,
            outputWidth.toFloat(), outputHeight.toFloat(),
            0f, outputHeight.toFloat()
        )

        val matrix = android.graphics.Matrix()
        matrix.setPolyToPoly(src, 0, dst, 0, 4)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(bitmap, matrix, paint)

        return output
    }

    private fun applyColorMode(bitmap: Bitmap): Bitmap {
        return when (settings.cameraColorMode) {
            "GRAYSCALE" -> ImageFilter.toGrayscale(bitmap)
            "BLACK_WHITE" -> ImageFilter.toBlackWhite(bitmap)
            else -> bitmap // COLOR - no filter
        }
    }

    private fun updateFlashIcon() {
        btnFlash.setImageResource(
            if (flashEnabled) R.drawable.ic_flash else R.drawable.ic_flash_off
        )
    }

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
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        lastDistance = spacing(event)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val currentDistance = spacing(event)
                        if (lastDistance > 0) {
                            val delta = (currentDistance - lastDistance) / 200f
                            val camera = camera ?: return@setOnTouchListener false
                            val currentZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                            val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
                            val newZoom = (currentZoom + delta).coerceIn(1f, maxZoom)
                            camera.cameraControl.setZoomRatio(newZoom)
                            updateZoomDisplay(newZoom)
                        }
                        lastDistance = currentDistance
                    }
                }
            }
            false
        }
    }

    private fun spacing(event: android.view.MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun updateZoomDisplay(zoomRatio: Float) {
        tvZoomLevel.text = String.format("%.1fx", zoomRatio)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
