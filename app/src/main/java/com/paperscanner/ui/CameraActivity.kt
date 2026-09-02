package com.paperscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

class CameraActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var overlayView: ScannerOverlayView
    private lateinit var btnCapture: Button
    private lateinit var btnFlash: ImageButton
    private lateinit var btnClose: ImageButton
    private lateinit var btnAutoCapture: ToggleButton
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
        progressBar = findViewById(R.id.progress_bar)
        tvStatus = findViewById(R.id.tv_status)

        overlayView.setShowFocusArea(settings.showFocusArea)
        flashEnabled = settings.flashEnabled
        autoCaptureEnabled = settings.autoCapture
        updateFlashIcon()
        btnAutoCapture.isChecked = autoCaptureEnabled

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
                    tvStatus.text = getString(R.string.document_detected)
                    tvStatus.setBackgroundResource(R.drawable.ghibli_button_primary)
                } else {
                    lastDetectedBounds = null
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
                    // Crop to detected bounds and apply color mode
                    cropAndSaveImage(photoFile.absolutePath)

                    // Map camera color mode to ImageFilterMode
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

                    progressBar.visibility = View.GONE
                    btnCapture.isEnabled = true

                    Toast.makeText(
                        this@CameraActivity,
                        "Image captured",
                        Toast.LENGTH_SHORT
                    ).show()
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

    private fun cropAndSaveImage(imagePath: String) {
        try {
            val bounds = lastDetectedBounds

            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return

            val processedBitmap = if (bounds != null) {
                // Map detection bounds from analysis resolution to full image resolution
                val scaleX = bitmap.width.toFloat() / analysisWidth
                val scaleY = bitmap.height.toFloat() / analysisHeight

                val cropLeft = (bounds.left * analysisWidth * scaleX).toInt().coerceIn(0, bitmap.width - 1)
                val cropTop = (bounds.top * analysisHeight * scaleY).toInt().coerceIn(0, bitmap.height - 1)
                val cropRight = (bounds.right * analysisWidth * scaleX).toInt().coerceIn(cropLeft + 1, bitmap.width)
                val cropBottom = (bounds.bottom * analysisHeight * scaleY).toInt().coerceIn(cropTop + 1, bitmap.height)

                // Add small padding
                val pad = 8
                val finalLeft = (cropLeft - pad).coerceAtLeast(0)
                val finalTop = (cropTop - pad).coerceAtLeast(0)
                val finalRight = (cropRight + pad).coerceAtMost(bitmap.width)
                val finalBottom = (cropBottom + pad).coerceAtMost(bitmap.height)

                val cropped = Bitmap.createBitmap(
                    bitmap,
                    finalLeft,
                    finalTop,
                    finalRight - finalLeft,
                    finalBottom - finalTop
                )

                // Apply camera color mode
                val colored = applyColorMode(cropped)
                if (colored != cropped) cropped.recycle()
                bitmap.recycle()
                colored
            } else {
                // No bounds detected - apply color mode to full image
                val colored = applyColorMode(bitmap)
                if (colored != bitmap) bitmap.recycle()
                colored
            }

            // Save processed bitmap
            val fos = java.io.FileOutputStream(imagePath)
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, settings.jpegQuality, fos)
            fos.flush()
            fos.close()
            processedBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            if (flashEnabled) android.R.drawable.ic_menu_camera
            else android.R.drawable.ic_menu_close_clear_cancel
        )
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
