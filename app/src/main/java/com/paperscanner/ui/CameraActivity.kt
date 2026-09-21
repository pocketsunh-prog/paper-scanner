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
import com.paperscanner.processing.DocumentOrientation
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.paperscanner.processing.ImageFilter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: ScannerOverlayView
    private lateinit var btnCapture: Button
    private lateinit var btnFlash: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnDetect: ImageButton
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
    // Detection display options, configured on the Settings screen
    private var showDetectionOutline = true
    private var showDetectionStatus = true
    private var detectionEnabled = true
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
        btnDetect = findViewById(R.id.btn_detect)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        tvZoomLevel = findViewById(R.id.tv_zoom_level)
        progressBar = findViewById(R.id.progress_bar)
        tvStatus = findViewById(R.id.tv_status)

        overlayView.setShowFocusArea(settings.showFocusArea)
        flashEnabled = settings.flashEnabled
        autoCaptureEnabled = settings.autoCapture
        updateFlashIcon()

        // Detection options live on the Settings screen
        showDetectionOutline = settings.showDetectionOutline
        showDetectionStatus = settings.showDetectionStatus
        detectionEnabled = settings.detectionEnabled
        overlayView.autoMode = settings.detectionMode != "MANUAL"
        overlayView.setShowDetectionOutline(showDetectionOutline)
        overlayView.setDetectionActive(detectionEnabled)
        updateDetectButton()
        tvStatus.visibility = if (showDetectionStatus) View.VISIBLE else View.GONE
        if (!overlayView.autoMode) {
            tvStatus.text = "Position rectangle over document"
        }

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

        // Quick on/off for document detection
        btnDetect.setOnClickListener {
            detectionEnabled = !detectionEnabled
            settings.detectionEnabled = detectionEnabled
            updateDetectButton()
            overlayView.setDetectionActive(detectionEnabled)

            if (detectionEnabled) {
                tvStatus.text = if (overlayView.autoMode) {
                    getString(R.string.point_at_document)
                } else {
                    "Position rectangle over document"
                }
            } else {
                lastDetectedBounds = null
                lastDetectedCorners = null
                overlayView.reset()
                tvStatus.text = getString(R.string.detection_off)
                Toast.makeText(this, R.string.detection_off, Toast.LENGTH_SHORT).show()
            }
        }

        // Real-time OCR button
        val fabRealTimeOcr = findViewById<FloatingActionButton>(R.id.fab_realtime_ocr)
        fabRealTimeOcr.setOnClickListener {
            startActivity(android.content.Intent(this, RealTimeOcrActivity::class.java))
        }

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

            // All use cases share one aspect ratio. If they differ, the analysis frame
            // shows a different field of view than the photo, and the detected
            // rectangle would not line up with what gets cropped.
            val aspectStrategy = aspectRatioStrategy()

            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspectStrategy)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                captureResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .build()
                .also { it.setSurfaceProvider(viewFinder.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspectStrategy)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                captureResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspectStrategy)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                analysisResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
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

    /** Status pill text, including which way the detected page is lying. */
    private fun detectionStatusText(orientation: DocumentOrientation): String {
        val label = when (orientation) {
            DocumentOrientation.HORIZONTAL -> getString(R.string.orientation_horizontal)
            DocumentOrientation.VERTICAL -> getString(R.string.orientation_vertical)
            DocumentOrientation.UNKNOWN -> null
        }
        return if (label == null) {
            getString(R.string.document_detected)
        } else {
            getString(R.string.document_detected_orientation, label)
        }
    }

    private fun getCaptureResolution(): Size {
        return when (settings.cameraResolution) {
            "LOW" -> Size(1280, 720)      // 720p
            "HIGH" -> Size(3840, 2160)     // 4K (or max available)
            else -> Size(1920, 1080)       // 1080p (MEDIUM default)
        }
    }

    private fun getAnalysisResolution(): Size {
        // Analysis uses a smaller resolution for performance, matching the capture ratio
        return when (settings.pictureSize) {
            "16:9" -> Size(640, 360)
            else -> Size(640, 480)
        }
    }

    /**
     * One aspect ratio for preview, analysis and capture, taken from the Aspect Ratio
     * setting. Keeping them identical is what makes the detected rectangle line up
     * with the photo that gets cropped. CameraX only exposes 4:3 and 16:9, so the
     * "1:1" option falls back to 4:3 like it always effectively did.
     */
    private fun aspectRatioStrategy(): AspectRatioStrategy =
        when (settings.pictureSize) {
            "16:9" -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }

    private fun processFrame(imageProxy: ImageProxy) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAnalysisTime < 150) {
            imageProxy.close()
            return
        }
        lastAnalysisTime = currentTime

        // Detection switched off: nothing to analyse, just let the preview run
        if (!detectionEnabled) {
            imageProxy.close()
            return
        }

        try {
            val plane = imageProxy.planes[0]
            val width = imageProxy.width
            val height = imageProxy.height
            analysisWidth = width
            analysisHeight = height

            // Read the Y plane honouring its padding, otherwise the image shears
            val luminance = DocumentDetector.yuvToLuminance(
                plane.buffer, width, height, plane.rowStride, plane.pixelStride
            )

            val rotation = imageProxy.imageInfo.rotationDegrees
            val result = DocumentDetector.detectDocument(luminance, width, height, rotation)

            runOnUiThread {
                // Tell the overlay how the frame is rotated so the outline lines up
                overlayView.setFrameInfo(width, height, rotation)
                overlayView.updateDetection(result)

                if (result.found) {
                    lastDetectedBounds = result.bounds
                    lastDetectedCorners = result.corners
                    tvStatus.text = detectionStatusText(result.orientation)
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
        // With detection on in auto mode we need a page rectangle to crop to
        if (detectionEnabled && overlayView.autoMode &&
            (lastDetectedCorners == null || lastDetectedCorners?.size != 4)
        ) {
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
     * Crops the captured photo down to the page.
     *
     * Auto mode: crops (and de-rotates) to exactly the detected rectangle.
     * Manual mode: crops to the rectangle the user dragged into place.
     * With detection off, the full frame is kept.
     */
    private fun cropAndSaveImage(imagePath: String): Boolean {
        try {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return false

            val cropped: Bitmap

            if (!detectionEnabled) {
                // Detection off: keep the whole frame
                cropped = bitmap
            } else if (overlayView.autoMode) {
                val corners = lastDetectedCorners
                if (corners == null || corners.size != 4) {
                    bitmap.recycle()
                    return false
                }
                cropped = rectangleCrop(bitmap, corners)
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

            // Save ONLY the inside rectangle area
            val fos = java.io.FileOutputStream(imagePath)
            colored.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, fos)
            fos.flush()
            fos.close()

            // Recycle every distinct bitmap once, whatever path we took above
            linkedSetOf(colored, cropped, bitmap).forEach { candidate ->
                if (!candidate.isRecycled) candidate.recycle()
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Crop the photo to exactly the detected rectangle, straightening it first when
     * the rectangle is tilted. The output contains only what the outline covered.
     */
    private fun rectangleCrop(bitmap: Bitmap, corners: List<Pair<Float, Float>>): Bitmap {
        // Map normalized corners onto the photo
        val points = corners.map {
            Pair(it.first * bitmap.width, it.second * bitmap.height)
        }
        val tl = points[0]
        val tr = points[1]
        val br = points[2]
        val bl = points[3]

        fun distance(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            sqrt((b.first - a.first) * (b.first - a.first) + (b.second - a.second) * (b.second - a.second))

        val outputWidth = max(distance(tl, tr), distance(bl, br)).toInt().coerceAtLeast(1)
        val outputHeight = max(distance(tl, bl), distance(tr, br)).toInt().coerceAtLeast(1)

        val centerX = (tl.first + tr.first + br.first + bl.first) / 4f
        val centerY = (tl.second + tr.second + br.second + bl.second) / 4f
        val angle = Math.toDegrees(
            atan2((tr.second - tl.second).toDouble(), (tr.first - tl.first).toDouble())
        ).toFloat()

        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // Place the rectangle's centre at the output centre and align its axes
        canvas.save()
        canvas.translate(outputWidth / 2f, outputHeight / 2f)
        canvas.rotate(-angle)
        canvas.translate(-centerX, -centerY)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()

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

    /** Dim the detection button when detection is switched off. */
    private fun updateDetectButton() {
        btnDetect.alpha = if (detectionEnabled) 1f else 0.4f
        btnDetect.background?.alpha = if (detectionEnabled) 255 else 90
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
